package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * beta.5 RC2 durable one-shot supply-event engine.
 *
 * A supply event injects stock through ShopAdminService instead of writing stock.yml
 * directly. Before the stock mutation, a PREPARED ledger record is persisted. After
 * the stock mutation, the record advances to APPLIED and then COMPLETED. On startup
 * or command refresh, PREPARED/APPLIED records are reconciled against the durable
 * stock snapshot. Ambiguous evidence fails closed and requires an explicit recovery.
 */
public final class MarketSupplyService {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");

    private final CdrVephilimEconomy plugin;
    private final AdminAuditService adminAudit;
    private final Logger logger;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final File historyFile;
    private final File stockFile;
    private final Map<String, SupplyEntry> entries = new LinkedHashMap<>();

    private boolean healthy = true;
    private String healthDetail = "OK";

    public MarketSupplyService(CdrVephilimEconomy plugin, AdminAuditService adminAudit) {
        this.plugin = plugin;
        this.adminAudit = adminAudit;
        this.logger = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "market-supply.yml");
        this.backupFile = new File(plugin.getDataFolder(), "market-supply.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "market-supply.yml.tmp");
        this.historyFile = new File(new File(plugin.getDataFolder(), "logs"), "market-supply.log");
        this.stockFile = new File(plugin.getDataFolder(), "stock.yml");
    }

    public synchronized void load() throws IOException {
        ensureDefaultFile();
        Map<String, SupplyEntry> loaded = readStrict(file);
        entries.clear();
        entries.putAll(loaded);
        healthy = true;
        healthDetail = "OK";
        recoverPendingAutomatically();
    }

    public synchronized String statusSummary() {
        long completed = entries.values().stream().filter(entry -> entry.state().terminal()).count();
        long unresolved = entries.values().stream().filter(entry -> !entry.state().terminal()).count();
        return (healthy ? "OK" : "BLOCKED")
                + "(schema=v" + SCHEMA_VERSION
                + ", entries=" + entries.size()
                + ", terminal=" + completed
                + ", unresolved=" + unresolved
                + ", detail=" + compact(healthDetail) + ")";
    }

    public synchronized Optional<SupplyEntry> find(String rawId) {
        if (rawId == null) return Optional.empty();
        return Optional.ofNullable(entries.get(rawId.trim().toLowerCase(Locale.ROOT)));
    }

    public synchronized List<SupplyEntry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public synchronized List<String> showLines(String rawId) {
        SupplyEntry entry = find(rawId).orElse(null);
        if (entry == null) return List.of("§c[CVE Supply] Supply event tidak ditemukan: " + rawId);
        return List.of(
                "§6[CVE Supply] §f" + entry.id() + " §7- " + entry.state(),
                "§7scope=§f" + entry.shopId() + "/" + entry.listingId()
                        + " §7amount=§f+" + entry.amount(),
                "§7stock=§f" + entry.beforeStock() + " -> " + entry.afterStock(),
                "§7createdBy=§f" + entry.actor() + " §7createdAt=§f" + entry.createdAt(),
                "§7updatedAt=§f" + entry.updatedAt()
                        + " §7announcement=§f" + (entry.announcement().isBlank() ? "-" : entry.announcement())
        );
    }

    public synchronized Result create(String actor, String rawId, String rawShopId,
                                      String rawListingId, int amount, String announcement) {
        if (!healthy) {
            return Result.fail("Supply engine fail-closed: " + healthDetail);
        }
        if (!auditReady()) {
            return Result.fail("Admin audit tidak writable; supply mutation ditolak fail-closed.");
        }

        String id;
        String shopId;
        String listingId;
        try {
            id = normalizeId(rawId);
            shopId = normalizeId(rawShopId);
            listingId = normalizeId(rawListingId);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        if (entries.containsKey(id)) {
            return Result.fail("Supply event ID sudah pernah dipakai: " + id);
        }
        if (amount <= 0) {
            return Result.fail("Supply amount harus > 0.");
        }
        if (plugin.isEconomySafetyStopped()) {
            return Result.fail("Supply ditolak saat economy safety stop aktif.");
        }

        Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
        if (shop == null) return Result.fail("Shop runtime tidak ditemukan: " + shopId);
        ShopListing listing = shop.listings().get(listingId);
        if (listing == null) return Result.fail("Listing runtime tidak ditemukan: " + shopId + "/" + listingId);

        int before;
        try {
            before = readCurrentStock(shopId, listingId);
        } catch (IOException exception) {
            block("Stock snapshot tidak dapat dibaca sebelum supply: " + exception.getMessage());
            return Result.fail(healthDetail);
        }
        long candidate = (long) before + amount;
        if (candidate > listing.maxStock()) {
            return Result.fail("Supply melebihi max-stock. Current=" + before
                    + ", amount=" + amount + ", max=" + listing.maxStock() + ".");
        }

        String cleanActor = sanitize(actor);
        String cleanAnnouncement = sanitizeMessage(announcement);
        Instant now = Instant.now();
        SupplyEntry prepared = new SupplyEntry(id, SupplyState.PREPARED, cleanActor,
                shopId, listingId, amount, before, (int) candidate, now, now, cleanAnnouncement);
        String detail = summary(prepared);

        try {
            adminAudit.record(cleanActor, "MARKET_SUPPLY_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Supply dibatalkan karena admin audit request gagal: " + compact(exception.getMessage()));
        }

        entries.put(id, prepared);
        try {
            persist();
        } catch (IOException exception) {
            entries.remove(id);
            block("PREPARED supply journal gagal dipersist: " + exception.getMessage());
            auditBestEffort(cleanActor, "MARKET_SUPPLY_FAILED", detail + "; stage=PREPARE; error=" + exception.getMessage());
            return Result.fail(healthDetail);
        }

        ShopAdminService.Result stockResult = plugin.shopAdminService().changeRuntimeStock(
                cleanActor, shopId, listingId, ShopAdminService.StockOperation.ADD, amount);
        if (!stockResult.success()) {
            return finishFailedApply(prepared, stockResult.message());
        }

        int current;
        try {
            current = readCurrentStock(shopId, listingId);
        } catch (IOException exception) {
            block("Stock berubah tetapi verifikasi durable snapshot gagal: " + exception.getMessage());
            return Result.fail("Supply mungkin sudah diterapkan; engine dikunci untuk recovery: " + compact(exception.getMessage()));
        }
        if (current != prepared.afterStock()) {
            block("Supply ambiguous setelah apply: expected=" + prepared.afterStock() + ", current=" + current
                    + ", id=" + id);
            return Result.fail(healthDetail);
        }

        SupplyEntry applied = prepared.withState(SupplyState.APPLIED, Instant.now());
        entries.put(id, applied);
        try {
            persist();
        } catch (IOException exception) {
            block("Stock sudah berubah tetapi APPLIED evidence gagal dipersist untuk " + id + ": " + exception.getMessage());
            return Result.fail("Stock sudah berubah, tetapi supply journal belum final. Jangan ulang event; lakukan recovery. "
                    + compact(exception.getMessage()));
        }

        try {
            adminAudit.record(cleanActor, "MARKET_SUPPLY_SUCCESS", summary(applied));
        } catch (IOException exception) {
            block("Supply applied tetapi success admin audit gagal: " + exception.getMessage());
            return Result.fail("Stock sudah berubah; success audit gagal. Supply engine dikunci sampai recovery.");
        }

        SupplyEntry completed = applied.withState(SupplyState.COMPLETED, Instant.now());
        entries.put(id, completed);
        try {
            persist();
            appendHistory(cleanActor, "MARKET_SUPPLY_COMPLETED", summary(completed));
        } catch (IOException exception) {
            entries.put(id, applied);
            block("Supply applied tetapi completion evidence gagal dipersist: " + exception.getMessage());
            return Result.fail("Stock sudah berubah; completion journal gagal. Recovery diperlukan.");
        }

        broadcast(cleanAnnouncement.isBlank()
                ? "Kiriman kerajaan tiba: +" + amount + " stock " + shopId + "/" + listingId + "."
                : cleanAnnouncement);
        return Result.ok("Supply " + id + " selesai: " + before + " -> " + candidate + ".");
    }

    public synchronized Result recover(String actor, String rawId, RecoveryDecision decision) {
        if (!auditReady()) return Result.fail("Admin audit tidak writable; recovery ditolak.");
        String id;
        try {
            id = normalizeId(rawId);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        SupplyEntry entry = entries.get(id);
        if (entry == null) return Result.fail("Supply event tidak ditemukan: " + id);
        if (entry.state().terminal()) return Result.fail("Supply event sudah terminal: " + entry.state());

        int current;
        try {
            current = readCurrentStock(entry.shopId(), entry.listingId());
        } catch (IOException exception) {
            return Result.fail("Recovery tidak dapat membaca stock snapshot: " + exception.getMessage());
        }
        int expected = decision == RecoveryDecision.APPLIED ? entry.afterStock() : entry.beforeStock();
        if (current != expected) {
            return Result.fail("Recovery assertion tidak cocok. mode=" + decision
                    + ", expectedStock=" + expected + ", currentStock=" + current
                    + ". Rekonsiliasi stock dulu, lalu ulangi recovery.");
        }

        SupplyState state = decision == RecoveryDecision.APPLIED
                ? SupplyState.RECOVERED_APPLIED
                : SupplyState.RECOVERED_NOT_APPLIED;
        SupplyEntry recovered = entry.withState(state, Instant.now());
        entries.put(id, recovered);
        try {
            persist();
            adminAudit.record(sanitize(actor), "MARKET_SUPPLY_RECOVERY", summary(recovered));
            appendHistory(actor, "MARKET_SUPPLY_RECOVERY", summary(recovered));
            healthy = true;
            healthDetail = "OK";
            verifyNoAmbiguousPending();
            return Result.ok("Supply recovery " + id + " ditandai " + state + ".");
        } catch (IOException exception) {
            block("Supply recovery gagal dipersist: " + exception.getMessage());
            return Result.fail(healthDetail);
        }
    }

    private Result finishFailedApply(SupplyEntry prepared, String reason) {
        int current;
        try {
            current = readCurrentStock(prepared.shopId(), prepared.listingId());
        } catch (IOException exception) {
            block("Stock apply gagal dan current snapshot tidak dapat diverifikasi: " + exception.getMessage());
            return Result.fail(healthDetail);
        }
        if (current != prepared.beforeStock()) {
            block("Stock apply melaporkan gagal tetapi stock berubah: id=" + prepared.id()
                    + ", before=" + prepared.beforeStock() + ", current=" + current);
            return Result.fail(healthDetail);
        }
        SupplyEntry failed = prepared.withState(SupplyState.FAILED_NOT_APPLIED, Instant.now());
        entries.put(prepared.id(), failed);
        try {
            persist();
            auditBestEffort(prepared.actor(), "MARKET_SUPPLY_FAILED",
                    summary(failed) + "; reason=" + compact(reason));
            appendHistory(prepared.actor(), "MARKET_SUPPLY_FAILED",
                    summary(failed) + "; reason=" + compact(reason));
        } catch (IOException exception) {
            block("Failed-not-applied evidence gagal dipersist: " + exception.getMessage());
            return Result.fail(healthDetail);
        }
        return Result.fail("Supply tidak diterapkan: " + reason);
    }

    private void recoverPendingAutomatically() throws IOException {
        boolean changed = false;
        for (Map.Entry<String, SupplyEntry> mapEntry : new ArrayList<>(entries.entrySet())) {
            SupplyEntry entry = mapEntry.getValue();
            if (entry.state().terminal()) continue;

            int current;
            try {
                current = readCurrentStock(entry.shopId(), entry.listingId());
            } catch (IOException exception) {
                block("Pending supply " + entry.id() + " tidak dapat diverifikasi: " + exception.getMessage());
                return;
            }

            SupplyState recoveredState;
            if (current == entry.afterStock()) {
                recoveredState = SupplyState.RECOVERED_APPLIED;
            } else if (current == entry.beforeStock()) {
                recoveredState = SupplyState.RECOVERED_NOT_APPLIED;
            } else {
                block("Pending supply ambiguous: id=" + entry.id()
                        + ", before=" + entry.beforeStock()
                        + ", after=" + entry.afterStock()
                        + ", current=" + current);
                return;
            }

            SupplyEntry recovered = entry.withState(recoveredState, Instant.now());
            entries.put(entry.id(), recovered);
            changed = true;
            appendHistory("SYSTEM", "MARKET_SUPPLY_AUTO_RECOVERY", summary(recovered));
            auditBestEffort("SYSTEM", "MARKET_SUPPLY_AUTO_RECOVERY", summary(recovered));
        }
        if (changed) persist();
        verifyNoAmbiguousPending();
    }

    private void verifyNoAmbiguousPending() {
        boolean unresolved = entries.values().stream().anyMatch(entry -> !entry.state().terminal());
        if (!unresolved) {
            healthy = true;
            healthDetail = "OK";
        }
    }

    private int readCurrentStock(String shopId, String listingId) throws IOException {
        if (!stockFile.isFile()) throw new IOException("stock.yml tidak ditemukan");
        YamlConfiguration yaml = loadYamlStrict(stockFile);
        Object raw = yaml.get("shops." + shopId + "." + listingId);
        if (!(raw instanceof Number number)) {
            throw new IOException("Stock snapshot missing/non-number untuk " + shopId + "/" + listingId);
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Stock snapshot bukan integer valid untuk " + shopId + "/" + listingId);
        }
        return number.intValue();
    }

    private boolean auditReady() {
        return adminAudit != null && adminAudit.isWritable();
    }

    private void auditBestEffort(String actor, String action, String detail) {
        if (adminAudit == null) return;
        try {
            adminAudit.record(sanitize(actor), action, detail);
        } catch (IOException exception) {
            logger.warning("Gagal menulis market supply admin audit: " + exception.getMessage());
        }
    }

    private void broadcast(String message) {
        if (message == null || message.isBlank()) return;
        plugin.getServer().broadcastMessage("§6[Pasar Kerajaan] §f" + message);
    }

    private void appendHistory(String actor, String action, String detail) throws IOException {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create market supply history directory");
        }
        String line = Instant.now() + " | actor=" + sanitize(actor)
                + " | action=" + sanitize(action)
                + " | detail=" + sanitize(detail) + System.lineSeparator();
        Files.writeString(historyFile.toPath(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    private void ensureDefaultFile() throws IOException {
        if (file.isFile()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin data folder");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("meta.created-at", Instant.now().toString());
        yaml.createSection("entries");
        yaml.save(file);
    }

    private Map<String, SupplyEntry> readStrict(File source) throws IOException {
        YamlConfiguration yaml = loadYamlStrict(source);
        if (yaml.getInt("meta.schema", -1) != SCHEMA_VERSION) {
            throw new IOException("market-supply.yml schema tidak didukung");
        }
        Map<String, SupplyEntry> loaded = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("entries");
        if (section == null) return loaded;
        for (String rawId : section.getKeys(false)) {
            String id;
            try {
                id = normalizeId(rawId);
            } catch (IllegalArgumentException exception) {
                throw new IOException(exception.getMessage(), exception);
            }
            ConfigurationSection entrySection = section.getConfigurationSection(rawId);
            if (entrySection == null) throw new IOException("Supply entry invalid: " + id);
            loaded.put(id, parseEntry(id, entrySection));
        }
        return loaded;
    }

    private SupplyEntry parseEntry(String id, ConfigurationSection section) throws IOException {
        SupplyState state;
        try {
            state = SupplyState.valueOf(section.getString("state", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Supply state invalid: " + id, exception);
        }
        String actor = sanitize(section.getString("actor", "SYSTEM"));
        String shop;
        String listing;
        try {
            shop = normalizeId(section.getString("shop", ""));
            listing = normalizeId(section.getString("listing", ""));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Supply scope invalid: " + id, exception);
        }
        int amount = strictNonNegativeInt(section.get("amount"), id, "amount");
        int before = strictNonNegativeInt(section.get("before-stock"), id, "before-stock");
        int after = strictNonNegativeInt(section.get("after-stock"), id, "after-stock");
        if (amount <= 0 || (long) before + amount != after) {
            throw new IOException("Supply amount/before/after inconsistent: " + id);
        }
        Instant created = parseInstant(section.getString("created-at"), id, "created-at");
        Instant updated = parseInstant(section.getString("updated-at"), id, "updated-at");
        String announcement = sanitizeMessage(section.getString("announcement", ""));
        return new SupplyEntry(id, state, actor, shop, listing, amount, before, after,
                created, updated, announcement);
    }

    private void persist() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("meta.updated-at", Instant.now().toString());
        ConfigurationSection section = yaml.createSection("entries");
        for (SupplyEntry entry : entries.values()) {
            String path = entry.id();
            ConfigurationSection value = section.createSection(path);
            value.set("state", entry.state().name());
            value.set("actor", entry.actor());
            value.set("shop", entry.shopId());
            value.set("listing", entry.listingId());
            value.set("amount", entry.amount());
            value.set("before-stock", entry.beforeStock());
            value.set("after-stock", entry.afterStock());
            value.set("created-at", entry.createdAt().toString());
            value.set("updated-at", entry.updatedAt().toString());
            value.set("announcement", entry.announcement());
        }

        if (file.isFile()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        yaml.save(tempFile);
        readStrict(tempFile);
        moveReplace(tempFile, file);
        Files.deleteIfExists(tempFile.toPath());
    }

    private static YamlConfiguration loadYamlStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
            return yaml;
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void block(String detail) {
        healthy = false;
        healthDetail = compact(detail);
        logger.severe("Market supply fail-closed: " + healthDetail);
    }

    private static int strictNonNegativeInt(Object raw, String id, String field) throws IOException {
        if (!(raw instanceof Number number)) throw new IOException("Supply " + field + " missing: " + id);
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException("Supply " + field + " invalid: " + id);
        }
        return number.intValue();
    }

    private static Instant parseInstant(String raw, String id, String field) throws IOException {
        try {
            return Instant.parse(raw);
        } catch (Exception exception) {
            throw new IOException("Supply " + id + " memiliki " + field + " invalid", exception);
        }
    }

    private static String normalizeId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("ID harus cocok [a-z0-9_-], maksimal 48 karakter: " + raw);
        }
        return value;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }

    private static String sanitizeMessage(String value) {
        String clean = sanitize(value);
        return clean.length() <= 180 ? clean : clean.substring(0, 180);
    }

    private static String compact(String value) {
        String clean = sanitize(value);
        return clean.length() <= 180 ? clean : clean.substring(0, 177) + "...";
    }

    private static String summary(SupplyEntry entry) {
        return "id=" + entry.id()
                + "; state=" + entry.state()
                + "; scope=" + entry.shopId() + "/" + entry.listingId()
                + "; amount=" + entry.amount()
                + "; before=" + entry.beforeStock()
                + "; after=" + entry.afterStock();
    }

    public enum SupplyState {
        PREPARED(false),
        APPLIED(false),
        COMPLETED(true),
        FAILED_NOT_APPLIED(true),
        RECOVERED_APPLIED(true),
        RECOVERED_NOT_APPLIED(true);

        private final boolean terminal;

        SupplyState(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean terminal() {
            return terminal;
        }
    }

    public enum RecoveryDecision {
        APPLIED,
        NOT_APPLIED
    }

    public record SupplyEntry(
            String id,
            SupplyState state,
            String actor,
            String shopId,
            String listingId,
            int amount,
            int beforeStock,
            int afterStock,
            Instant createdAt,
            Instant updatedAt,
            String announcement
    ) {
        private SupplyEntry withState(SupplyState newState, Instant updated) {
            return new SupplyEntry(id, newState, actor, shopId, listingId, amount,
                    beforeStock, afterStock, createdAt, updated, announcement);
        }
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) {
            return new Result(false, message == null || message.isBlank() ? "unknown error" : message);
        }
    }
}
