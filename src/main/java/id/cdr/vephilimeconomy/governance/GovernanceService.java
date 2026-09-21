package id.cdr.vephilimeconomy.governance;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.market.MarketEventLifecycleService;
import id.cdr.vephilimeconomy.market.MarketSupplyCommandListener;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class GovernanceService {
    private static final int SCHEMA = 1;
    private static final String ADMIN_PERMISSION = "cdrvephilimeconomy.admin";
    private static final String GOVERNANCE_ADMIN_PERMISSION = "cdrvephilimeconomy.governance.admin";
    private static final Pattern SAFE_SCOPE = Pattern.compile("[a-z0-9_-]{1,48}");

    private final CdrVephilimEconomy plugin;
    private final AdminAuditService audit;
    private final File file;
    private final File backupFile;
    private final File tempFile;
    private final Map<UUID, Assignment> assignments = new LinkedHashMap<>();

    private boolean healthy;
    private String healthDetail = "not loaded";

    public GovernanceService(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
        this.file = new File(plugin.getDataFolder(), "governance.yml");
        this.backupFile = new File(plugin.getDataFolder(), "governance.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "governance.yml.tmp");

        // beta.5 RC3: GovernanceService no longer registers its own quota command
        // listener. CveCommand owns the single GovernanceQuotaCommandListener and
        // its single quota ledger, preventing every governed mutation from being
        // reserved twice. Market beta.5 listeners are bootstrapped here once,
        // because this service itself is created once during plugin enable.
        plugin.getServer().getPluginManager().registerEvents(
                new MarketSupplyCommandListener(plugin, audit), plugin);
        new MarketEventLifecycleService(plugin, audit).start();
    }

    public synchronized Result load() {
        try {
            if (!file.isFile()) {
                persist(Collections.emptyMap());
            }
            Map<UUID, Assignment> loaded = readStrict(file);
            assignments.clear();
            assignments.putAll(loaded);
            healthy = true;
            healthDetail = "schema=v" + SCHEMA + ", members=" + assignments.size();
            return Result.ok("Governance loaded: " + healthDetail + ".");
        } catch (IOException exception) {
            healthy = false;
            healthDetail = exception.getMessage();
            plugin.getLogger().severe("Governance fail-closed: " + healthDetail);
            return Result.fail("Governance load gagal: " + healthDetail);
        }
    }

    public synchronized Result reload() {
        return load();
    }

    public synchronized Result grant(String actor, String playerName, GovernanceRole role, String rawScope) {
        if (!healthy) {
            return Result.fail("Governance storage tidak sehat: " + healthDetail);
        }
        OfflinePlayer target = resolveKnownPlayer(playerName);
        if (target == null || target.getName() == null) {
            return Result.fail("Player harus pernah join server sebelum diberi role governance.");
        }

        String scope;
        try {
            scope = normalizeScope(rawScope);
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
        if (!scope.equals("*") && plugin.findRuntimeShop(scope).isEmpty()) {
            return Result.fail("Shop scope tidak ditemukan: " + scope);
        }

        String detail = "target=" + target.getName() + "; uuid=" + target.getUniqueId()
                + "; role=" + role + "; scope=" + scope;
        try {
            audit.record(actor, "GOVERNANCE_GRANT_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Grant dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }

        Map<UUID, Assignment> candidate = new LinkedHashMap<>(assignments);
        Assignment previous = candidate.get(target.getUniqueId());
        Set<String> scopes = new LinkedHashSet<>();
        if (previous != null && previous.role() == role) {
            scopes.addAll(previous.scopes());
        }
        scopes.add(scope);
        candidate.put(target.getUniqueId(), new Assignment(target.getUniqueId(), target.getName(), role, Set.copyOf(scopes)));

        try {
            persist(candidate);
            assignments.clear();
            assignments.putAll(candidate);
            audit.record(actor, "GOVERNANCE_GRANT_SUCCESS", detail);
            return Result.ok(target.getName() + " sekarang " + role + " scope=" + String.join(",", scopes) + ".");
        } catch (IOException exception) {
            tryRecord(actor, "GOVERNANCE_GRANT_FAILED", detail + "; error=" + exception.getMessage());
            return Result.fail("Grant gagal dipersist: " + exception.getMessage());
        }
    }

    public synchronized Result revoke(String actor, String playerName, String rawScope) {
        if (!healthy) {
            return Result.fail("Governance storage tidak sehat: " + healthDetail);
        }
        Optional<Map.Entry<UUID, Assignment>> found = findByName(playerName);
        if (found.isEmpty()) {
            return Result.fail("Assignment governance tidak ditemukan untuk " + playerName + ".");
        }

        String scope = rawScope == null ? "all" : rawScope.trim().toLowerCase(Locale.ROOT);
        if (!scope.equals("all")) {
            try {
                scope = normalizeScope(scope);
            } catch (IllegalArgumentException exception) {
                return Result.fail(exception.getMessage());
            }
        }

        Assignment old = found.get().getValue();
        String detail = "target=" + old.lastKnownName() + "; uuid=" + old.uuid() + "; scope=" + scope;
        try {
            audit.record(actor, "GOVERNANCE_REVOKE_REQUEST", detail);
        } catch (IOException exception) {
            return Result.fail("Revoke dibatalkan karena admin audit tidak dapat ditulis: " + exception.getMessage());
        }

        Map<UUID, Assignment> candidate = new LinkedHashMap<>(assignments);
        if (scope.equals("all")) {
            candidate.remove(old.uuid());
        } else {
            Set<String> remaining = new LinkedHashSet<>(old.scopes());
            if (!remaining.remove(scope)) {
                return Result.fail("Scope " + scope + " tidak dimiliki " + old.lastKnownName() + ".");
            }
            if (remaining.isEmpty()) {
                candidate.remove(old.uuid());
            } else {
                candidate.put(old.uuid(), new Assignment(old.uuid(), old.lastKnownName(), old.role(), Set.copyOf(remaining)));
            }
        }

        try {
            persist(candidate);
            assignments.clear();
            assignments.putAll(candidate);
            audit.record(actor, "GOVERNANCE_REVOKE_SUCCESS", detail);
            return Result.ok("Governance " + old.lastKnownName() + " diperbarui. scope=" + scope + " dicabut.");
        } catch (IOException exception) {
            tryRecord(actor, "GOVERNANCE_REVOKE_FAILED", detail + "; error=" + exception.getMessage());
            return Result.fail("Revoke gagal dipersist: " + exception.getMessage());
        }
    }

    public synchronized boolean authorize(CommandSender sender, String legacyPermission,
                                          GovernanceCapability capability, String shopId) {
        if (sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(legacyPermission)) {
            return true;
        }
        if (!healthy || !(sender instanceof Player player)) {
            return false;
        }
        Assignment assignment = assignments.get(player.getUniqueId());
        if (assignment == null || !assignment.role().allows(capability)) {
            return false;
        }
        if (shopId == null || shopId.isBlank()) {
            return !assignment.scopes().isEmpty();
        }
        String normalized = shopId.trim().toLowerCase(Locale.ROOT);
        return assignment.scopes().contains("*") || assignment.scopes().contains(normalized);
    }

    public synchronized boolean hasAnyShopAccess(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        if (!(sender instanceof Player player) || !healthy) {
            return false;
        }
        return assignments.containsKey(player.getUniqueId());
    }

    public boolean canAdminGovernance(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(GOVERNANCE_ADMIN_PERMISSION);
    }

    public boolean canViewGovernance(CommandSender sender) {
        return canAdminGovernance(sender) || sender.hasPermission("cdrvephilimeconomy.governance.view")
                || hasAnyShopAccess(sender);
    }

    public synchronized Result validatePriceChange(CommandSender sender, String shopId, double before, double after) {
        if (sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission("cdrvephilimeconomy.shop.price")) {
            return Result.ok("permission bypass");
        }
        Assignment assignment = assignment(sender);
        if (assignment == null) {
            return Result.fail("Tidak ada governance assignment aktif.");
        }
        if (assignment.role() == GovernanceRole.ROYAL_TREASURER) {
            return Result.ok("treasurer bypass");
        }
        if (before <= 0.0D && Double.compare(before, after) != 0) {
            return Result.fail("Perubahan harga dari basis 0 membutuhkan Royal Treasurer/admin.");
        }
        double percent = before == 0.0D ? 0.0D : Math.abs(after - before) / before * 100.0D;
        double limit = assignment.role() == GovernanceRole.ECONOMY_STAFF
                ? plugin.getConfig().getDouble("governance.limits.economy-staff.max-price-change-percent", 20.0D)
                : plugin.getConfig().getDouble("governance.limits.economy-manager.max-price-change-percent", 50.0D);
        if (percent > Math.max(0.0D, limit)) {
            return Result.fail("Perubahan harga " + format(percent) + "% melewati limit role "
                    + assignment.role() + " sebesar " + format(limit) + "% per operasi.");
        }
        return Result.ok("price change within governance limit");
    }

    public synchronized Result validateRuntimeStockChange(CommandSender sender, String shopId, int before, int after) {
        if (sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission("cdrvephilimeconomy.shop.stock")) {
            return Result.ok("permission bypass");
        }
        Assignment assignment = assignment(sender);
        if (assignment == null) {
            return Result.fail("Tidak ada governance assignment aktif.");
        }
        if (assignment.role() == GovernanceRole.ROYAL_TREASURER) {
            return Result.ok("treasurer bypass");
        }
        long delta = Math.abs((long) after - before);
        long limit = assignment.role() == GovernanceRole.ECONOMY_STAFF
                ? plugin.getConfig().getLong("governance.limits.economy-staff.max-runtime-stock-delta", 128L)
                : plugin.getConfig().getLong("governance.limits.economy-manager.max-runtime-stock-delta", 1024L);
        limit = Math.max(0L, limit);
        if (delta > limit) {
            return Result.fail("Perubahan stock delta=" + delta + " melewati limit role "
                    + assignment.role() + " sebesar " + limit + " per operasi.");
        }
        return Result.ok("stock change within governance limit");
    }

    public synchronized Optional<Assignment> assignmentFor(String playerName) {
        return findByName(playerName).map(Map.Entry::getValue);
    }

    public synchronized Optional<Assignment> assignmentFor(UUID uuid) {
        return Optional.ofNullable(assignments.get(uuid));
    }

    public synchronized List<String> listLines() {
        if (assignments.isEmpty()) {
            return List.of("§7Belum ada economy staff governance.");
        }
        List<Assignment> sorted = new ArrayList<>(assignments.values());
        sorted.sort(Comparator.comparing(Assignment::lastKnownName, String.CASE_INSENSITIVE_ORDER));
        List<String> lines = new ArrayList<>();
        for (Assignment assignment : sorted) {
            lines.add("§e" + assignment.lastKnownName() + " §7| role=§f" + assignment.role()
                    + " §7| scope=§f" + String.join(",", assignment.scopes()));
        }
        return lines;
    }

    public synchronized String statusSummary() {
        return "healthy=" + healthy + ", " + healthDetail + ", members=" + assignments.size();
    }

    public synchronized String describe(String playerName) {
        Optional<Assignment> optional = assignmentFor(playerName);
        if (optional.isEmpty()) {
            return playerName + " tidak memiliki governance assignment.";
        }
        Assignment assignment = optional.get();
        return assignment.lastKnownName() + " role=" + assignment.role()
                + ", scopes=" + String.join(",", assignment.scopes())
                + ", capabilities=" + assignment.role().capabilities();
    }

    private Assignment assignment(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return null;
        }
        return assignments.get(player.getUniqueId());
    }

    private OfflinePlayer resolveKnownPlayer(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        Player online = plugin.getServer().getPlayerExact(rawName);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(rawName);
        return offline.hasPlayedBefore() ? offline : null;
    }

    private Optional<Map.Entry<UUID, Assignment>> findByName(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        return assignments.entrySet().stream()
                .filter(entry -> entry.getValue().lastKnownName().equalsIgnoreCase(rawName.trim()))
                .findFirst();
    }

    private Map<UUID, Assignment> readStrict(File source) throws IOException {
        YamlConfiguration yaml = loadStrict(source);
        int schema = yaml.getInt("meta.schema", -1);
        if (schema != SCHEMA) {
            throw new IOException("governance.yml schema tidak didukung: " + schema + " (expected " + SCHEMA + ")");
        }

        Map<UUID, Assignment> loaded = new LinkedHashMap<>();
        ConfigurationSection members = yaml.getConfigurationSection("members");
        if (members == null) {
            return loaded;
        }
        for (String rawUuid : members.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException exception) {
                throw new IOException("UUID governance invalid: " + rawUuid);
            }
            ConfigurationSection section = members.getConfigurationSection(rawUuid);
            if (section == null) {
                throw new IOException("Member governance invalid: " + rawUuid);
            }
            String name = section.getString("name", "").trim();
            if (name.isBlank() || name.length() > 64) {
                throw new IOException("Nama governance invalid untuk " + rawUuid);
            }
            GovernanceRole role;
            try {
                role = GovernanceRole.valueOf(section.getString("role", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Role governance invalid untuk " + name);
            }
            Set<String> scopes = new LinkedHashSet<>();
            for (String rawScope : section.getStringList("scopes")) {
                scopes.add(normalizeScope(rawScope));
            }
            if (scopes.isEmpty()) {
                throw new IOException("Governance member " + name + " tidak memiliki scope.");
            }
            loaded.put(uuid, new Assignment(uuid, name, role, Set.copyOf(scopes)));
        }
        return loaded;
    }

    private void persist(Map<UUID, Assignment> source) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("meta.schema", SCHEMA);
        yaml.set("meta.updated-at", Instant.now().toString());
        ConfigurationSection members = yaml.createSection("members");
        for (Assignment assignment : source.values()) {
            ConfigurationSection section = members.createSection(assignment.uuid().toString());
            section.set("name", assignment.lastKnownName());
            section.set("role", assignment.role().name());
            List<String> scopes = new ArrayList<>(assignment.scopes());
            scopes.sort(String::compareTo);
            section.set("scopes", scopes);
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

    private static String normalizeScope(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("*")) {
            return value;
        }
        if (!SAFE_SCOPE.matcher(value).matches()) {
            throw new IllegalArgumentException("Scope harus '*' atau shop id [a-z0-9_-] maksimal 48 karakter.");
        }
        return value;
    }

    private void tryRecord(String actor, String action, String detail) {
        try {
            audit.record(actor, action, detail);
        } catch (IOException exception) {
            plugin.getLogger().severe("Gagal menulis governance audit failure: " + exception.getMessage());
        }
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    public record Assignment(UUID uuid, String lastKnownName, GovernanceRole role, Set<String> scopes) {
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
