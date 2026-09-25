package id.cdr.vephilimeconomy.discount;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persistent BUY-only personal discounts configured by an administrator.
 *
 * <p>The market/dynamic/event price is calculated first. The personal discount
 * is then applied as the final BUY-price layer. SELL prices are intentionally
 * unaffected.</p>
 */
public final class PlayerDiscountService {
    public static final double MAX_DISCOUNT_PERCENT = 90.0D;
    private static final int SCHEMA = 1;
    private static final Pattern SAFE_SCOPE = Pattern.compile("[a-z0-9_-]{1,48}");
    private static volatile PlayerDiscountService INSTANCE;

    private final CdrVephilimEconomy plugin;
    private final AdminAuditService audit;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final File initializedMarker;
    private final Map<UUID, Profile> profiles = new LinkedHashMap<>();

    private boolean healthy;
    private String healthDetail = "not loaded";

    private PlayerDiscountService(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
        this.file = new File(plugin.getDataFolder(), "discounts.yml");
        this.backupFile = new File(plugin.getDataFolder(), "discounts.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "discounts.yml.tmp");
        this.initializedMarker = new File(plugin.getDataFolder(), "discounts.yml.initialized");
    }

    public static synchronized PlayerDiscountService install(CdrVephilimEconomy plugin, AdminAuditService audit) {
        if (INSTANCE != null && INSTANCE.plugin == plugin) {
            return INSTANCE;
        }
        PlayerDiscountService service = new PlayerDiscountService(plugin, audit);
        Result loaded = service.load();
        if (!loaded.success()) {
            plugin.getLogger().severe("Personal discount runtime tidak sehat: " + loaded.message());
            plugin.getLogger().severe("Personal discount dinonaktifkan sampai discounts.yml direcover dan /cve discount reload berhasil.");
        }
        INSTANCE = service;
        return service;
    }

    public static PlayerDiscountService instance() {
        return INSTANCE;
    }

    public static double currentPercent(UUID playerId, String shopId) {
        PlayerDiscountService service = INSTANCE;
        if (service == null) {
            return 0.0D;
        }
        return service.percent(playerId, shopId);
    }

    public static double applyCurrentBuyDiscount(UUID playerId, String shopId, double marketPrice) {
        PlayerDiscountService service = INSTANCE;
        if (service == null) {
            return marketPrice;
        }
        return service.applyBuyDiscount(playerId, shopId, marketPrice);
    }

    public synchronized Result load() {
        profiles.clear();
        healthy = false;
        healthDetail = "loading";

        try {
            Map<UUID, Profile> loaded;
            boolean recovered = false;

            if (!file.isFile()) {
                if (initializedMarker.isFile()) {
                    if (!backupFile.isFile()) {
                        throw new IOException("discounts.yml hilang setelah pernah diinisialisasi dan backup tidak tersedia");
                    }
                    loaded = readStrict(backupFile);
                    restoreBackupToPrimary();
                    recovered = true;
                } else {
                    loaded = Collections.emptyMap();
                    persist(loaded);
                    writeInitializedMarker();
                }
            } else {
                try {
                    loaded = readStrict(file);
                } catch (IOException primaryFailure) {
                    if (!backupFile.isFile()) {
                        throw primaryFailure;
                    }
                    try {
                        loaded = readStrict(backupFile);
                        restoreBackupToPrimary();
                        recovered = true;
                        plugin.getLogger().warning("discounts.yml rusak; berhasil direcover dari discounts.yml.bak.");
                    } catch (IOException backupFailure) {
                        primaryFailure.addSuppressed(backupFailure);
                        throw primaryFailure;
                    }
                }
                if (!initializedMarker.isFile()) {
                    writeInitializedMarker();
                }
            }

            profiles.putAll(deepCopy(loaded));
            healthy = true;
            healthDetail = recovered ? "OK_RECOVERED_FROM_BACKUP" : "OK";
            return new Result(true, "discounts=" + ruleCount() + ", profiles=" + profiles.size()
                    + (recovered ? ", recovered=true" : ""));
        } catch (IOException exception) {
            profiles.clear();
            healthDetail = "ERROR: " + exception.getMessage();
            return new Result(false, healthDetail);
        }
    }

    public synchronized double percent(UUID playerId, String rawShopId) {
        if (!healthy || playerId == null || rawShopId == null) {
            return 0.0D;
        }
        Profile profile = profiles.get(playerId);
        if (profile == null) {
            return 0.0D;
        }
        String shopId = normalizeScope(rawShopId);
        Double exact = profile.discounts().get(shopId);
        if (exact != null) {
            return exact;
        }
        return profile.discounts().getOrDefault("*", 0.0D);
    }

    public synchronized double applyBuyDiscount(UUID playerId, String shopId, double marketPrice) {
        if (!Double.isFinite(marketPrice) || marketPrice <= 0.0D) {
            return marketPrice;
        }
        double percent = percent(playerId, shopId);
        if (percent <= 0.0D) {
            return marketPrice;
        }
        double discounted = marketPrice * (1.0D - (percent / 100.0D));
        return Math.max(0.01D, round2(discounted));
    }

    public synchronized Result set(String actor, UUID playerId, String lastName, String rawScope, double percent) {
        if (!healthy) {
            return new Result(false, "discount runtime tidak sehat: " + healthDetail);
        }
        if (audit == null || !audit.isWritable()) {
            return new Result(false, "admin audit tidak writable; mutation discount ditolak fail-closed");
        }
        if (playerId == null) {
            return new Result(false, "player UUID tidak valid");
        }
        String scope = normalizeScope(rawScope);
        if (!validScope(scope)) {
            return new Result(false, "scope harus shop id valid atau *");
        }
        if (!Double.isFinite(percent) || percent <= 0.0D || percent > MAX_DISCOUNT_PERCENT) {
            return new Result(false, "discount harus > 0 dan <= " + MAX_DISCOUNT_PERCENT + "%");
        }
        percent = round2(percent);

        String detail = "player=" + playerId + "; name=" + safeName(lastName)
                + "; scope=" + scope + "; percent=" + percent;
        try {
            audit.record(actor, "PLAYER_DISCOUNT_SET_REQUEST", detail);
        } catch (IOException exception) {
            return new Result(false, "gagal menulis admin audit REQUEST: " + exception.getMessage());
        }

        Map<UUID, Profile> candidate = deepCopy(profiles);
        Profile old = candidate.get(playerId);
        Map<String, Double> discounts = old == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(old.discounts());
        discounts.put(scope, percent);
        candidate.put(playerId, new Profile(safeName(lastName), discounts));

        try {
            persist(candidate);
            profiles.clear();
            profiles.putAll(deepCopy(candidate));
        } catch (IOException exception) {
            recordFailure(actor, "PLAYER_DISCOUNT_SET_FAILED", detail + "; error=" + exception.getMessage());
            return new Result(false, "gagal menyimpan discount: " + exception.getMessage());
        }

        try {
            audit.record(actor, "PLAYER_DISCOUNT_SET_SUCCESS", detail);
            return new Result(true, "discount " + percent + "% untuk " + safeName(lastName) + " pada " + scope + " tersimpan");
        } catch (IOException exception) {
            return new Result(true, "discount sudah committed, tetapi SUCCESS audit gagal: " + exception.getMessage());
        }
    }

    public synchronized Result remove(String actor, UUID playerId, String lastName, String rawScope) {
        if (!healthy) {
            return new Result(false, "discount runtime tidak sehat: " + healthDetail);
        }
        if (audit == null || !audit.isWritable()) {
            return new Result(false, "admin audit tidak writable; mutation discount ditolak fail-closed");
        }
        String scope = normalizeScope(rawScope);
        if (playerId == null || !validScope(scope)) {
            return new Result(false, "player/scope tidak valid");
        }
        Profile current = profiles.get(playerId);
        if (current == null || !current.discounts().containsKey(scope)) {
            return new Result(false, "discount untuk scope tersebut tidak ditemukan");
        }

        String detail = "player=" + playerId + "; name=" + safeName(lastName) + "; scope=" + scope;
        try {
            audit.record(actor, "PLAYER_DISCOUNT_REMOVE_REQUEST", detail);
        } catch (IOException exception) {
            return new Result(false, "gagal menulis admin audit REQUEST: " + exception.getMessage());
        }

        Map<UUID, Profile> candidate = deepCopy(profiles);
        Profile old = candidate.get(playerId);
        Map<String, Double> discounts = new LinkedHashMap<>(old.discounts());
        discounts.remove(scope);
        if (discounts.isEmpty()) {
            candidate.remove(playerId);
        } else {
            candidate.put(playerId, new Profile(old.lastName(), discounts));
        }

        try {
            persist(candidate);
            profiles.clear();
            profiles.putAll(deepCopy(candidate));
        } catch (IOException exception) {
            recordFailure(actor, "PLAYER_DISCOUNT_REMOVE_FAILED", detail + "; error=" + exception.getMessage());
            return new Result(false, "gagal menyimpan removal discount: " + exception.getMessage());
        }

        try {
            audit.record(actor, "PLAYER_DISCOUNT_REMOVE_SUCCESS", detail);
            return new Result(true, "discount " + scope + " untuk " + safeName(lastName) + " dihapus");
        } catch (IOException exception) {
            return new Result(true, "removal sudah committed, tetapi SUCCESS audit gagal: " + exception.getMessage());
        }
    }

    public synchronized Result clear(String actor, UUID playerId, String lastName) {
        if (!healthy) {
            return new Result(false, "discount runtime tidak sehat: " + healthDetail);
        }
        if (audit == null || !audit.isWritable()) {
            return new Result(false, "admin audit tidak writable; mutation discount ditolak fail-closed");
        }
        if (playerId == null || !profiles.containsKey(playerId)) {
            return new Result(false, "player tidak memiliki personal discount");
        }

        String detail = "player=" + playerId + "; name=" + safeName(lastName) + "; allScopes=true";
        try {
            audit.record(actor, "PLAYER_DISCOUNT_CLEAR_REQUEST", detail);
        } catch (IOException exception) {
            return new Result(false, "gagal menulis admin audit REQUEST: " + exception.getMessage());
        }

        Map<UUID, Profile> candidate = deepCopy(profiles);
        candidate.remove(playerId);
        try {
            persist(candidate);
            profiles.clear();
            profiles.putAll(deepCopy(candidate));
        } catch (IOException exception) {
            recordFailure(actor, "PLAYER_DISCOUNT_CLEAR_FAILED", detail + "; error=" + exception.getMessage());
            return new Result(false, "gagal clear discount: " + exception.getMessage());
        }

        try {
            audit.record(actor, "PLAYER_DISCOUNT_CLEAR_SUCCESS", detail);
            return new Result(true, "semua personal discount untuk " + safeName(lastName) + " dihapus");
        } catch (IOException exception) {
            return new Result(true, "clear sudah committed, tetapi SUCCESS audit gagal: " + exception.getMessage());
        }
    }

    public synchronized String describe(UUID playerId, String fallbackName) {
        Profile profile = profiles.get(playerId);
        if (profile == null) {
            return safeName(fallbackName) + " tidak memiliki personal discount.";
        }
        List<String> entries = new ArrayList<>();
        profile.discounts().forEach((scope, percent) -> entries.add(scope + "=" + percent + "%"));
        return profile.lastName() + " (" + playerId + ") -> " + String.join(", ", entries);
    }

    public synchronized List<String> listLines() {
        if (profiles.isEmpty()) {
            return List.of("§7Belum ada personal discount.");
        }
        List<String> lines = new ArrayList<>();
        profiles.forEach((uuid, profile) -> {
            List<String> values = new ArrayList<>();
            profile.discounts().forEach((scope, percent) -> values.add(scope + "=" + percent + "%"));
            lines.add("§e" + profile.lastName() + " §7(" + uuid + ") §f" + String.join(", ", values));
        });
        return Collections.unmodifiableList(lines);
    }

    public synchronized String statusSummary() {
        return "health=" + healthDetail + ", profiles=" + profiles.size() + ", rules=" + ruleCount()
                + ", maxDiscount=" + MAX_DISCOUNT_PERCENT + "%";
    }

    public synchronized boolean healthy() {
        return healthy;
    }

    private int ruleCount() {
        int count = 0;
        for (Profile profile : profiles.values()) {
            count += profile.discounts().size();
        }
        return count;
    }

    private Map<UUID, Profile> readStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        if (yaml.getInt("meta.schema", -1) != SCHEMA) {
            throw new IOException("unsupported discounts schema in " + source.getName());
        }

        Map<UUID, Profile> loaded = new LinkedHashMap<>();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return loaded;
        }
        for (String rawUuid : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid player UUID in discounts.yml: " + rawUuid);
            }
            String base = "players." + rawUuid;
            String name = safeName(yaml.getString(base + ".last-name", rawUuid));
            ConfigurationSection discounts = yaml.getConfigurationSection(base + ".discounts");
            if (discounts == null || discounts.getKeys(false).isEmpty()) {
                continue;
            }
            Map<String, Double> rules = new LinkedHashMap<>();
            for (String rawScope : discounts.getKeys(false)) {
                String scope = normalizeScope(rawScope);
                if (!validScope(scope)) {
                    throw new IOException("invalid discount scope for " + rawUuid + ": " + rawScope);
                }
                Object rawValue = discounts.get(rawScope);
                if (!(rawValue instanceof Number number)) {
                    throw new IOException("discount percent is not numeric for " + rawUuid + "/" + rawScope);
                }
                double value = number.doubleValue();
                if (!Double.isFinite(value) || value <= 0.0D || value > MAX_DISCOUNT_PERCENT) {
                    throw new IOException("discount out of range for " + rawUuid + "/" + rawScope);
                }
                rules.put(scope, round2(value));
            }
            loaded.put(uuid, new Profile(name, rules));
        }
        return loaded;
    }

    private void persist(Map<UUID, Profile> candidate) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + parent);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA);
        yaml.set("meta.updated-at", Instant.now().toString());
        for (Map.Entry<UUID, Profile> entry : candidate.entrySet()) {
            String base = "players." + entry.getKey();
            yaml.set(base + ".last-name", entry.getValue().lastName());
            for (Map.Entry<String, Double> discount : entry.getValue().discounts().entrySet()) {
                yaml.set(base + ".discounts." + discount.getKey(), discount.getValue());
            }
        }
        yaml.save(tempFile);
        readStrict(tempFile);

        if (file.isFile()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        moveReplace(tempFile, file);
        if (!backupFile.isFile()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void restoreBackupToPrimary() throws IOException {
        Files.copy(backupFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        readStrict(tempFile);
        moveReplace(tempFile, file);
    }

    private void writeInitializedMarker() throws IOException {
        Files.writeString(initializedMarker.toPath(),
                "CdrVephilimEconomy discounts persistence initialized at " + Instant.now() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void recordFailure(String actor, String action, String detail) {
        try {
            audit.record(actor, action, detail);
        } catch (IOException ignored) {
            plugin.getLogger().severe("Gagal menulis " + action + " ke admin audit.");
        }
    }

    private static Map<UUID, Profile> deepCopy(Map<UUID, Profile> source) {
        Map<UUID, Profile> copy = new LinkedHashMap<>();
        source.forEach((uuid, profile) -> copy.put(uuid,
                new Profile(profile.lastName(), new LinkedHashMap<>(profile.discounts()))));
        return copy;
    }

    private static String normalizeScope(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validScope(String scope) {
        return "*".equals(scope) || SAFE_SCOPE.matcher(scope).matches();
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
        return cleaned.length() <= 32 ? cleaned : cleaned.substring(0, 32);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Profile(String lastName, Map<String, Double> discounts) {
    }

    public record Result(boolean success, String message) {
    }
}
