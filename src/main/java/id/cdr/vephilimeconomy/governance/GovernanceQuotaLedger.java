package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Durable rolling quota ledger for role-only economy staff/manager mutations.
 *
 * Reservations are persisted before the command executor performs the actual mutation.
 * This is intentionally conservative: if the downstream mutation later fails, the
 * reservation remains until the rolling window expires. The design prefers a temporary
 * false-positive quota charge over a crash/retry path that could bypass anti-abuse limits.
 */
public final class GovernanceQuotaLedger {
    private static final int SCHEMA = 1;

    private final CdrVephilimEconomy plugin;
    private final AdminAuditService audit;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final Map<UUID, List<UsageEvent>> events = new LinkedHashMap<>();

    private boolean healthy;
    private String healthDetail = "not loaded";

    public GovernanceQuotaLedger(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
        this.file = new File(plugin.getDataFolder(), "governance-usage.yml");
        this.backupFile = new File(plugin.getDataFolder(), "governance-usage.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "governance-usage.yml.tmp");
    }

    public synchronized Result load() {
        try {
            if (!file.isFile()) {
                persist(Map.of());
            }
            Map<UUID, List<UsageEvent>> loaded = readStrict(file);
            events.clear();
            events.putAll(loaded);
            healthy = true;
            pruneInMemory(Instant.now());
            healthDetail = "schema=v" + SCHEMA + ", events=" + eventCount();
            return Result.ok("Quota ledger loaded: " + healthDetail + ".");
        } catch (IOException exception) {
            healthy = false;
            healthDetail = exception.getMessage();
            plugin.getLogger().severe("Governance quota fail-closed: " + healthDetail);
            return Result.fail("Quota ledger load gagal: " + healthDetail);
        }
    }

    public synchronized Decision reserve(GovernanceService.Assignment assignment,
                                         UsageKind kind,
                                         double weight,
                                         String shopId,
                                         String listingId,
                                         String detail) {
        if (!plugin.getConfig().getBoolean("governance.quota.enabled", true)) {
            return Decision.allowed("quota disabled");
        }
        if (assignment == null || assignment.role() == GovernanceRole.ROYAL_TREASURER) {
            return Decision.allowed("quota bypass");
        }
        if (!healthy) {
            return Decision.blocked("Quota ledger tidak sehat; direct mutation role diblokir fail-closed: " + healthDetail);
        }
        if (!Double.isFinite(weight) || weight < 0.0D) {
            return Decision.blocked("Quota weight invalid.");
        }
        if (weight == 0.0D) {
            return Decision.allowed("zero-impact mutation");
        }

        Instant now = Instant.now();
        pruneInMemory(now);
        RolePolicy policy = policy(assignment.role());
        List<UsageEvent> userEvents = events.getOrDefault(assignment.uuid(), List.of());

        UsageEvent newest = userEvents.stream()
                .max(Comparator.comparing(UsageEvent::at))
                .orElse(null);
        if (newest != null && policy.cooldownSeconds() > 0L) {
            long elapsedMillis = Math.max(0L, Duration.between(newest.at(), now).toMillis());
            long cooldownMillis = policy.cooldownSeconds() * 1000L;
            if (elapsedMillis < cooldownMillis) {
                long remainingMillis = cooldownMillis - elapsedMillis;
                long remainingSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
                return Decision.blocked("Governance cooldown aktif. Tunggu " + remainingSeconds
                        + " detik sebelum direct mutation berikutnya.");
            }
        }

        Instant cutoff = now.minusSeconds(policy.windowMinutes() * 60L);
        double used = userEvents.stream()
                .filter(value -> value.kind() == kind)
                .filter(value -> !value.at().isBefore(cutoff))
                .mapToDouble(UsageEvent::weight)
                .sum();
        double limit = kind == UsageKind.PRICE_PERCENT
                ? policy.maxPricePercent()
                : policy.maxStockDelta();
        if (used + weight > limit + 0.0000001D) {
            return Decision.blocked("Rolling quota " + kind.displayName() + " tercapai: used="
                    + format(used) + ", requested=" + format(weight) + ", limit=" + format(limit)
                    + " dalam " + policy.windowMinutes() + " menit. Tunggu window berkurang atau minta operator melakukan perubahan.");
        }

        UsageEvent event = new UsageEvent(
                UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                now,
                assignment.lastKnownName(),
                assignment.role(),
                kind,
                weight,
                normalize(shopId),
                normalize(listingId),
                compact(detail)
        );

        Map<UUID, List<UsageEvent>> candidate = deepCopy(events);
        List<UsageEvent> candidateEvents = new ArrayList<>(candidate.getOrDefault(assignment.uuid(), List.of()));
        candidateEvents.add(event);
        candidate.put(assignment.uuid(), candidateEvents);
        try {
            persist(candidate);
            events.clear();
            events.putAll(candidate);
            healthDetail = "schema=v" + SCHEMA + ", events=" + eventCount();
        } catch (IOException exception) {
            healthy = false;
            healthDetail = "quota reservation persistence failed: " + exception.getMessage();
            plugin.getLogger().severe("Governance quota fail-closed: " + healthDetail);
            return Decision.blocked("Quota reservation gagal dipersist; direct role mutation diblokir fail-closed.");
        }

        try {
            audit.record(assignment.lastKnownName(), "GOVERNANCE_QUOTA_RESERVED",
                    "role=" + assignment.role() + "; kind=" + kind + "; weight=" + format(weight)
                            + "; shop=" + event.shopId() + "; listing=" + event.listingId()
                            + "; event=" + event.id());
        } catch (IOException exception) {
            plugin.getLogger().warning("Quota reservation tersimpan tetapi admin audit gagal: " + exception.getMessage());
        }
        return Decision.allowed("quota reserved: used=" + format(used + weight) + "/" + format(limit));
    }

    public synchronized String statusSummary() {
        return "healthy=" + healthy + ", " + healthDetail;
    }

    public synchronized String describe(UUID uuid, GovernanceRole role) {
        if (!healthy) {
            return "quota=UNHEALTHY(" + healthDetail + ")";
        }
        if (role == GovernanceRole.ROYAL_TREASURER) {
            return "quota=bypass(ROYAL_TREASURER)";
        }
        Instant now = Instant.now();
        pruneInMemory(now);
        RolePolicy policy = policy(role);
        Instant cutoff = now.minusSeconds(policy.windowMinutes() * 60L);
        List<UsageEvent> userEvents = events.getOrDefault(uuid, List.of());
        double price = userEvents.stream()
                .filter(value -> value.kind() == UsageKind.PRICE_PERCENT && !value.at().isBefore(cutoff))
                .mapToDouble(UsageEvent::weight).sum();
        double stock = userEvents.stream()
                .filter(value -> value.kind() == UsageKind.STOCK_DELTA && !value.at().isBefore(cutoff))
                .mapToDouble(UsageEvent::weight).sum();
        UsageEvent newest = userEvents.stream().max(Comparator.comparing(UsageEvent::at)).orElse(null);
        String last = newest == null ? "none" : newest.at().toString();
        return "quotaWindow=" + policy.windowMinutes() + "m"
                + ", price=" + format(price) + "/" + format(policy.maxPricePercent())
                + ", stock=" + format(stock) + "/" + format(policy.maxStockDelta())
                + ", cooldown=" + policy.cooldownSeconds() + "s"
                + ", last=" + last;
    }

    private RolePolicy policy(GovernanceRole role) {
        String node = role == GovernanceRole.ECONOMY_STAFF
                ? "governance.quota.economy-staff"
                : "governance.quota.economy-manager";
        long window = clamp(plugin.getConfig().getLong(node + ".window-minutes", 60L), 1L, 10080L);
        long cooldown = clamp(plugin.getConfig().getLong(node + ".cooldown-seconds",
                role == GovernanceRole.ECONOMY_STAFF ? 15L : 5L), 0L, 3600L);
        double maxPrice = sanitizeLimit(plugin.getConfig().getDouble(node + ".max-price-percent-sum",
                role == GovernanceRole.ECONOMY_STAFF ? 40.0D : 100.0D));
        double maxStock = sanitizeLimit(plugin.getConfig().getDouble(node + ".max-stock-delta-sum",
                role == GovernanceRole.ECONOMY_STAFF ? 256.0D : 4096.0D));
        return new RolePolicy(window, cooldown, maxPrice, maxStock);
    }

    private void pruneInMemory(Instant now) {
        long retentionHours = clamp(plugin.getConfig().getLong("governance.quota.retention-hours", 48L), 1L, 720L);
        Instant cutoff = now.minusSeconds(retentionHours * 3600L);
        List<UUID> empty = new ArrayList<>();
        for (Map.Entry<UUID, List<UsageEvent>> entry : events.entrySet()) {
            List<UsageEvent> kept = entry.getValue().stream()
                    .filter(value -> !value.at().isBefore(cutoff))
                    .sorted(Comparator.comparing(UsageEvent::at))
                    .toList();
            if (kept.isEmpty()) empty.add(entry.getKey());
            else entry.setValue(new ArrayList<>(kept));
        }
        empty.forEach(events::remove);
    }

    private int eventCount() {
        return events.values().stream().mapToInt(List::size).sum();
    }

    private Map<UUID, List<UsageEvent>> readStrict(File source) throws IOException {
        YamlConfiguration yaml = loadStrict(source);
        int schema = yaml.getInt("meta.schema", -1);
        if (schema != SCHEMA) {
            throw new IOException("governance-usage.yml schema tidak didukung: " + schema + " (expected " + SCHEMA + ")");
        }
        Map<UUID, List<UsageEvent>> loaded = new LinkedHashMap<>();
        ConfigurationSection users = yaml.getConfigurationSection("users");
        if (users == null) return loaded;

        for (String rawUuid : users.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException exception) {
                throw new IOException("UUID quota invalid: " + rawUuid);
            }
            ConfigurationSection user = users.getConfigurationSection(rawUuid);
            if (user == null) throw new IOException("Quota user section invalid: " + rawUuid);
            ConfigurationSection eventSection = user.getConfigurationSection("events");
            if (eventSection == null) continue;
            List<UsageEvent> list = new ArrayList<>();
            for (String id : eventSection.getKeys(false)) {
                ConfigurationSection section = eventSection.getConfigurationSection(id);
                if (section == null) throw new IOException("Quota event invalid: " + id);
                Instant at;
                try {
                    at = Instant.parse(section.getString("at", ""));
                } catch (DateTimeParseException exception) {
                    throw new IOException("Quota event timestamp invalid: " + id);
                }
                GovernanceRole role;
                UsageKind kind;
                try {
                    role = GovernanceRole.valueOf(section.getString("role", "").toUpperCase(Locale.ROOT));
                    kind = UsageKind.valueOf(section.getString("kind", "").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Quota event role/kind invalid: " + id);
                }
                double weight = section.getDouble("weight", Double.NaN);
                if (!Double.isFinite(weight) || weight < 0.0D) {
                    throw new IOException("Quota event weight invalid: " + id);
                }
                String name = section.getString("name", "").trim();
                String shop = normalize(section.getString("shop", ""));
                String listing = normalize(section.getString("listing", ""));
                String detail = compact(section.getString("detail", ""));
                list.add(new UsageEvent(id, at, name, role, kind, weight, shop, listing, detail));
            }
            list.sort(Comparator.comparing(UsageEvent::at));
            loaded.put(uuid, list);
        }
        return loaded;
    }

    private void persist(Map<UUID, List<UsageEvent>> source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA);
        yaml.set("meta.updated-at", Instant.now().toString());
        ConfigurationSection users = yaml.createSection("users");
        for (Map.Entry<UUID, List<UsageEvent>> entry : source.entrySet()) {
            ConfigurationSection user = users.createSection(entry.getKey().toString());
            ConfigurationSection eventSection = user.createSection("events");
            for (UsageEvent event : entry.getValue()) {
                ConfigurationSection section = eventSection.createSection(event.id());
                section.set("at", event.at().toString());
                section.set("name", event.playerName());
                section.set("role", event.role().name());
                section.set("kind", event.kind().name());
                section.set("weight", event.weight());
                section.set("shop", event.shopId());
                section.set("listing", event.listingId());
                section.set("detail", event.detail());
            }
        }

        if (file.isFile()) {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        yaml.save(tempFile);
        readStrict(tempFile);
        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    private YamlConfiguration loadStrict(File source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private static Map<UUID, List<UsageEvent>> deepCopy(Map<UUID, List<UsageEvent>> source) {
        Map<UUID, List<UsageEvent>> copy = new LinkedHashMap<>();
        source.forEach((uuid, list) -> copy.put(uuid, new ArrayList<>(list)));
        return copy;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        String compact = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
    }

    private static double sanitizeLimit(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, value);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    public enum UsageKind {
        PRICE_PERCENT("price-percent"),
        STOCK_DELTA("stock-delta");

        private final String displayName;

        UsageKind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private record RolePolicy(long windowMinutes, long cooldownSeconds,
                              double maxPricePercent, double maxStockDelta) {
    }

    private record UsageEvent(String id, Instant at, String playerName, GovernanceRole role,
                              UsageKind kind, double weight, String shopId, String listingId, String detail) {
    }

    public record Decision(boolean allowed, String message) {
        public static Decision allowed(String message) {
            return new Decision(true, message);
        }

        public static Decision blocked(String message) {
            return new Decision(false, message == null || message.isBlank() ? "quota blocked" : message);
        }
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message == null || message.isBlank() ? "unknown error" : message);
        }
    }
}
