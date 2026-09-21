package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.transaction.TransactionType;
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
import java.time.Duration;
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
 * beta.5 RC1 durable RP market-event engine.
 *
 * Events are temporary price modifiers layered after beta.4's bounded dynamic
 * price. They never directly mutate player balance, inventory, or shop stock.
 * This keeps the transaction journal as the single mutation boundary while
 * allowing RP events to influence the quote the player actually sees.
 */
public final class MarketEventService {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final double MIN_COMBINED_MULTIPLIER = 0.25D;
    private static final double MAX_COMBINED_MULTIPLIER = 4.0D;

    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final File historyFile;
    private final Logger logger;
    private final CdrVephilimEconomy plugin;
    private final AdminAuditService adminAudit;
    private final Map<String, MarketEvent> events = new LinkedHashMap<>();

    private boolean healthy = true;
    private String healthDetail = "OK";

    /** Runtime read-only instance used by DynamicPricingService. */
    public MarketEventService(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "market-events.yml");
        this.backupFile = new File(dataFolder, "market-events.yml.bak");
        this.tempFile = new File(dataFolder, "market-events.yml.tmp");
        this.historyFile = new File(new File(dataFolder, "logs"), "market-events.log");
        this.logger = logger;
        this.plugin = null;
        this.adminAudit = null;
    }

    /** Administrative instance used by the /cve market command listener. */
    public MarketEventService(CdrVephilimEconomy plugin, AdminAuditService adminAudit) {
        this(plugin.getDataFolder(), plugin.getLogger(), plugin, adminAudit);
    }

    private MarketEventService(File dataFolder, Logger logger,
                               CdrVephilimEconomy plugin, AdminAuditService adminAudit) {
        this.file = new File(dataFolder, "market-events.yml");
        this.backupFile = new File(dataFolder, "market-events.yml.bak");
        this.tempFile = new File(dataFolder, "market-events.yml.tmp");
        this.historyFile = new File(new File(dataFolder, "logs"), "market-events.log");
        this.logger = logger;
        this.plugin = plugin;
        this.adminAudit = adminAudit;
    }

    public synchronized void load() throws IOException {
        ensureDefaultFile();
        events.clear();
        healthy = true;
        healthDetail = "OK";

        YamlConfiguration yaml = loadStrict(file);
        validateYaml(yaml);
        ConfigurationSection section = yaml.getConfigurationSection("events");
        if (section == null) {
            return;
        }

        for (String rawId : section.getKeys(false)) {
            String id = normalizeId(rawId, false);
            ConfigurationSection eventSection = section.getConfigurationSection(rawId);
            if (eventSection == null) {
                continue;
            }
            MarketEvent event = parseEvent(id, eventSection);
            events.put(id, event);
        }
    }

    public synchronized Modifier modifierFor(String rawShopId, String rawListingId,
                                             TransactionType type, Instant now) {
        if (!healthy || events.isEmpty()) {
            return Modifier.identity();
        }
        String shopId = normalizeScope(rawShopId);
        String listingId = normalizeScope(rawListingId);
        Instant instant = now == null ? Instant.now() : now;

        double multiplier = 1.0D;
        int active = 0;
        List<String> ids = new ArrayList<>();
        for (MarketEvent event : events.values()) {
            if (!event.activeAt(instant) || !event.matches(shopId, listingId)) {
                continue;
            }
            double factor = type == TransactionType.BUY
                    ? event.buyMultiplier()
                    : event.sellMultiplier();
            multiplier *= factor;
            active++;
            ids.add(event.id());
        }

        if (active == 0) {
            return Modifier.identity();
        }
        multiplier = clamp(multiplier, MIN_COMBINED_MULTIPLIER, MAX_COMBINED_MULTIPLIER);
        return new Modifier(multiplier, active, List.copyOf(ids));
    }

    public synchronized Optional<MarketEvent> find(String rawId) {
        if (rawId == null) return Optional.empty();
        return Optional.ofNullable(events.get(rawId.trim().toLowerCase(Locale.ROOT)));
    }

    public synchronized List<MarketEvent> all() {
        return Collections.unmodifiableList(new ArrayList<>(events.values()));
    }

    public synchronized String statusSummary() {
        Instant now = Instant.now();
        long active = events.values().stream().filter(event -> event.activeAt(now)).count();
        long expired = events.values().stream().filter(event -> event.enabled() && !event.endsAt().isAfter(now)).count();
        return (healthy ? "OK" : "BLOCKED")
                + "(schema=v" + SCHEMA_VERSION
                + ", events=" + events.size()
                + ", active=" + active
                + ", expired=" + expired
                + ", detail=" + compact(healthDetail) + ")";
    }

    public synchronized List<String> listLines() {
        if (events.isEmpty()) {
            return List.of("§7Belum ada market event.");
        }
        Instant now = Instant.now();
        List<String> lines = new ArrayList<>();
        for (MarketEvent event : events.values()) {
            String state = event.activeAt(now) ? "§aACTIVE"
                    : event.enabled() && !event.startsAt().isAfter(now) ? "§7EXPIRED" : "§eINACTIVE";
            lines.add("§f" + event.id() + " §7| " + state
                    + " §7| " + event.type()
                    + " §7| scope=§f" + event.shopId() + "/" + event.listingId()
                    + " §7| buy=x§f" + event.buyMultiplier()
                    + " §7sell=x§f" + event.sellMultiplier()
                    + " §7| until=§f" + event.endsAt());
        }
        return List.copyOf(lines);
    }

    public synchronized List<String> showLines(String rawId) {
        MarketEvent event = find(rawId).orElse(null);
        if (event == null) {
            return List.of("§c[CVE Market] Event tidak ditemukan: " + rawId);
        }
        Instant now = Instant.now();
        return List.of(
                "§6[CVE Market] §f" + event.id() + " §7- " + event.type(),
                "§7state=§f" + (event.activeAt(now) ? "ACTIVE" : "INACTIVE")
                        + " §7enabled=§f" + event.enabled()
                        + " §7scope=§f" + event.shopId() + "/" + event.listingId(),
                "§7buyMultiplier=§f" + event.buyMultiplier()
                        + " §7sellMultiplier=§f" + event.sellMultiplier(),
                "§7startsAt=§f" + event.startsAt() + " §7endsAt=§f" + event.endsAt(),
                "§7createdBy=§f" + event.createdBy()
                        + " §7announcement=§f" + (event.announcement().isBlank() ? "-" : event.announcement())
        );
    }

    public synchronized Result createPreset(String actor, String rawId, EventType type,
                                            String rawShopId, String rawListingId,
                                            double multiplier, int durationMinutes,
                                            String announcement) {
        if (plugin == null) return Result.fail("Administrative market service tidak tersedia.");
        if (!mutationAuditReady()) return Result.fail("Admin audit tidak writable; market mutation ditolak fail-closed.");

        String id;
        String shopId;
        String listingId;
        try {
            id = normalizeId(rawId, false);
            shopId = normalizeId(rawShopId, true);
            listingId = normalizeId(rawListingId, true);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        if (events.containsKey(id)) return Result.fail("Event ID sudah ada: " + id);
        if (durationMinutes < 1 || durationMinutes > 10_080) {
            return Result.fail("Durasi harus 1-10080 menit (maks 7 hari).");
        }
        if (!Double.isFinite(multiplier)) return Result.fail("Multiplier harus finite.");

        double buy;
        double sell;
        switch (type) {
            case SCARCITY -> {
                if (multiplier < 1.0D || multiplier > 3.0D) {
                    return Result.fail("SCARCITY multiplier harus 1.0-3.0.");
                }
                buy = multiplier;
                sell = multiplier;
            }
            case KINGDOM_BUY_BONUS -> {
                if (multiplier < 1.0D || multiplier > 3.0D) {
                    return Result.fail("KINGDOM_BUY_BONUS multiplier harus 1.0-3.0.");
                }
                buy = 1.0D;
                sell = multiplier;
            }
            case DISCOUNT -> {
                if (multiplier < 0.25D || multiplier > 1.0D) {
                    return Result.fail("DISCOUNT multiplier harus 0.25-1.0.");
                }
                buy = multiplier;
                sell = 1.0D;
            }
            default -> throw new IllegalStateException("Unhandled event type " + type);
        }

        Instant start = Instant.now();
        Instant end = start.plus(Duration.ofMinutes(durationMinutes));
        String cleanAnnouncement = sanitizeMessage(announcement);
        MarketEvent event = new MarketEvent(id, type, true, shopId, listingId,
                buy, sell, start, end, sanitize(actor), cleanAnnouncement);
        return persistMutation(actor, "MARKET_EVENT_CREATE", eventSummary(event), yaml -> writeEvent(yaml, event),
                cleanAnnouncement.isBlank() ? "Event pasar " + id + " dimulai." : cleanAnnouncement);
    }

    public synchronized Result end(String actor, String rawId, String reason) {
        if (plugin == null) return Result.fail("Administrative market service tidak tersedia.");
        if (!mutationAuditReady()) return Result.fail("Admin audit tidak writable; market mutation ditolak fail-closed.");

        String id;
        try {
            id = normalizeId(rawId, false);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        MarketEvent current = events.get(id);
        if (current == null) return Result.fail("Event tidak ditemukan: " + id);
        if (!current.enabled()) return Result.fail("Event sudah dinonaktifkan: " + id);

        String cleanReason = sanitizeMessage(reason);
        String detail = "id=" + id + (cleanReason.isBlank() ? "" : "; reason=" + cleanReason);
        return persistMutation(actor, "MARKET_EVENT_END", detail, yaml -> {
            String path = "events." + id;
            yaml.set(path + ".enabled", false);
            yaml.set(path + ".ended-at", Instant.now().toString());
            yaml.set(path + ".ended-by", sanitize(actor));
            yaml.set(path + ".end-reason", cleanReason);
        }, "Event pasar " + id + " telah berakhir" + (cleanReason.isBlank() ? "." : ": " + cleanReason));
    }

    private Result persistMutation(String actor, String action, String detail,
                                   Mutation mutation, String broadcastMessage) {
        try {
            adminAudit.record(actor, action + "_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Gagal menulis admin audit request: " + compact(exception.getMessage()));
        }

        byte[] original;
        try {
            ensureDefaultFile();
            original = Files.readAllBytes(file.toPath());
            YamlConfiguration yaml = loadStrict(file);
            validateYaml(yaml);
            mutation.apply(yaml);
            yaml.set("meta.schema", SCHEMA_VERSION);
            yaml.set("meta.updated-at", Instant.now().toString());
            validateYaml(yaml);

            yaml.save(tempFile);
            validateYaml(loadStrict(tempFile));
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            moveReplace(tempFile, file);
        } catch (Exception exception) {
            cleanupTemp();
            auditRejected(actor, action, detail, exception.getMessage());
            return Result.fail("Market event candidate ditolak: " + compact(exception.getMessage()));
        }

        CdrVephilimEconomy.ReloadResult reload = plugin.reloadRuntime();
        if (!reload.success()) {
            String rollback;
            try {
                Files.write(file.toPath(), original);
                CdrVephilimEconomy.ReloadResult restored = plugin.reloadRuntime();
                rollback = restored.success() ? "runtime restored" : "RESTORE RELOAD FAILED: " + restored.message();
            } catch (Exception exception) {
                rollback = "RESTORE FILE FAILED: " + compact(exception.getMessage());
            }
            cleanupTemp();
            auditRejected(actor, action, detail, "runtime apply failed; " + rollback);
            return Result.fail("Runtime apply gagal; " + rollback);
        }

        try {
            load();
            adminAudit.record(actor, action + "_SUCCESS", detail);
            appendHistory(actor, action, detail);
        } catch (IOException exception) {
            logger.warning("Market mutation berhasil tetapi audit/history refresh gagal: " + exception.getMessage());
        }
        cleanupTemp();
        broadcast(broadcastMessage);
        return Result.ok(detail);
    }

    private boolean mutationAuditReady() {
        return adminAudit != null && adminAudit.isWritable();
    }

    private void auditRejected(String actor, String action, String detail, String reason) {
        if (adminAudit == null) return;
        try {
            adminAudit.record(actor, action + "_REJECTED", detail + "; reason=" + compact(reason));
        } catch (IOException ignored) {
            logger.warning("Gagal menulis market rejection audit.");
        }
    }

    private void appendHistory(String actor, String action, String detail) throws IOException {
        File parent = historyFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create market history directory");
        }
        String line = Instant.now() + " | actor=" + sanitize(actor)
                + " | action=" + sanitize(action)
                + " | detail=" + sanitize(detail) + System.lineSeparator();
        Files.writeString(historyFile.toPath(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    private void broadcast(String message) {
        if (plugin == null || message == null || message.isBlank()) return;
        plugin.getServer().broadcastMessage("§6[Pasar Kerajaan] §f" + message);
    }

    private void ensureDefaultFile() throws IOException {
        if (file.exists()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin data folder");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA_VERSION);
        yaml.set("meta.created-at", Instant.now().toString());
        yaml.createSection("events");
        yaml.save(file);
    }

    private static void writeEvent(YamlConfiguration yaml, MarketEvent event) {
        String path = "events." + event.id();
        yaml.set(path + ".type", event.type().name());
        yaml.set(path + ".enabled", event.enabled());
        yaml.set(path + ".shop", event.shopId());
        yaml.set(path + ".listing", event.listingId());
        yaml.set(path + ".buy-multiplier", event.buyMultiplier());
        yaml.set(path + ".sell-multiplier", event.sellMultiplier());
        yaml.set(path + ".starts-at", event.startsAt().toString());
        yaml.set(path + ".ends-at", event.endsAt().toString());
        yaml.set(path + ".created-by", event.createdBy());
        yaml.set(path + ".announcement", event.announcement());
    }

    private static void validateYaml(YamlConfiguration yaml) throws IOException {
        if (yaml.getInt("meta.schema", -1) != SCHEMA_VERSION) {
            throw new IOException("market-events.yml schema tidak didukung");
        }
        ConfigurationSection events = yaml.getConfigurationSection("events");
        if (events == null) return;
        for (String rawId : events.getKeys(false)) {
            String id;
            try {
                id = normalizeId(rawId, false);
            } catch (IllegalArgumentException exception) {
                throw new IOException(exception.getMessage(), exception);
            }
            ConfigurationSection section = events.getConfigurationSection(rawId);
            if (section == null) throw new IOException("Event section invalid: " + id);
            parseEvent(id, section);
        }
    }

    private static MarketEvent parseEvent(String id, ConfigurationSection section) throws IOException {
        EventType type;
        try {
            type = EventType.valueOf(section.getString("type", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Event type invalid: " + id, exception);
        }
        boolean enabled = section.getBoolean("enabled", true);
        String shop;
        String listing;
        try {
            shop = normalizeId(section.getString("shop", "*"), true);
            listing = normalizeId(section.getString("listing", "*"), true);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Event scope invalid: " + id + "; " + exception.getMessage(), exception);
        }
        double buy = section.getDouble("buy-multiplier", 1.0D);
        double sell = section.getDouble("sell-multiplier", 1.0D);
        if (!Double.isFinite(buy) || !Double.isFinite(sell)
                || buy < 0.25D || buy > 3.0D || sell < 0.25D || sell > 3.0D) {
            throw new IOException("Event multiplier invalid: " + id);
        }
        Instant start = parseInstant(section.getString("starts-at"), id, "starts-at");
        Instant end = parseInstant(section.getString("ends-at"), id, "ends-at");
        if (!end.isAfter(start)) throw new IOException("Event ends-at harus setelah starts-at: " + id);
        String createdBy = sanitize(section.getString("created-by", "SYSTEM"));
        String announcement = sanitizeMessage(section.getString("announcement", ""));
        return new MarketEvent(id, type, enabled, shop, listing, buy, sell, start, end, createdBy, announcement);
    }

    private static Instant parseInstant(String raw, String id, String field) throws IOException {
        try {
            return Instant.parse(raw);
        } catch (Exception exception) {
            throw new IOException("Event " + id + " memiliki " + field + " invalid", exception);
        }
    }

    private static YamlConfiguration loadStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private void cleanupTemp() {
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException exception) {
            logger.warning("Gagal membersihkan market-events.yml.tmp: " + exception.getMessage());
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

    private static String normalizeId(String value, boolean allowWildcard) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (allowWildcard && normalized.equals("*")) return "*";
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ID harus cocok [a-z0-9_-], maksimal 48 karakter"
                    + (allowWildcard ? " atau *" : "") + ": " + value);
        }
        return normalized;
    }

    private static String normalizeScope(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String eventSummary(MarketEvent event) {
        return "id=" + event.id()
                + "; type=" + event.type()
                + "; scope=" + event.shopId() + "/" + event.listingId()
                + "; buy=x" + event.buyMultiplier()
                + "; sell=x" + event.sellMultiplier()
                + "; starts=" + event.startsAt()
                + "; ends=" + event.endsAt();
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(YamlConfiguration yaml) throws Exception;
    }

    public enum EventType {
        SCARCITY,
        KINGDOM_BUY_BONUS,
        DISCOUNT
    }

    public record MarketEvent(
            String id,
            EventType type,
            boolean enabled,
            String shopId,
            String listingId,
            double buyMultiplier,
            double sellMultiplier,
            Instant startsAt,
            Instant endsAt,
            String createdBy,
            String announcement
    ) {
        public boolean activeAt(Instant now) {
            return enabled && !now.isBefore(startsAt) && now.isBefore(endsAt);
        }

        public boolean matches(String shop, String listing) {
            return (shopId.equals("*") || shopId.equals(shop))
                    && (listingId.equals("*") || listingId.equals(listing));
        }
    }

    public record Modifier(double multiplier, int activeEvents, List<String> eventIds) {
        private static Modifier identity() {
            return new Modifier(1.0D, 0, List.of());
        }
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }
}
