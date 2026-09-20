package id.cdr.vephilimeconomy.diagnostic;

import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.RuntimeSafetyState;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DoctorService {
    private static final int STOCK_SCHEMA_VERSION = 1;

    private final JavaPlugin plugin;
    private final ShopRegistry runtimeRegistry;
    private final StockRepository stocks;
    private final AuditService audit;
    private final RuntimeSafetyState safety;
    private final EconomyBridge economy;

    public DoctorService(JavaPlugin plugin, ShopRegistry runtimeRegistry, StockRepository stocks,
                         AuditService audit, RuntimeSafetyState safety, EconomyBridge economy) {
        this.plugin = plugin;
        this.runtimeRegistry = runtimeRegistry;
        this.stocks = stocks;
        this.audit = audit;
        this.safety = safety;
        this.economy = economy;
    }

    public Report run() {
        List<Check> checks = new ArrayList<>();
        checkRuntime(checks);
        checkDependencies(checks);
        checkConfig(checks);
        checkShops(checks);
        checkNpcBindings(checks);
        checkStock(checks);
        checkFilesystem(checks);
        checkAudit(checks);
        checkSafety(checks);
        return new Report(checks);
    }

    private void checkRuntime(List<Check> checks) {
        if (plugin.isEnabled() && runtimeRegistry != null && stocks != null && audit != null && safety != null && economy != null) {
            checks.add(pass("runtime", "Plugin dan service utama aktif."));
        } else {
            checks.add(fail("runtime", "Ada runtime service yang belum siap."));
        }
    }

    private void checkDependencies(List<Check> checks) {
        Plugin citizens = plugin.getServer().getPluginManager().getPlugin("Citizens");
        if (citizens != null && citizens.isEnabled()) {
            checks.add(pass("Citizens", "enabled version=" + citizens.getPluginMeta().getVersion()));
        } else {
            checks.add(fail("Citizens", "dependency tidak ditemukan atau tidak enabled."));
        }

        Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vault != null && vault.isEnabled()) {
            checks.add(pass("Vault", "enabled version=" + vault.getPluginMeta().getVersion()));
        } else {
            checks.add(fail("Vault", "dependency tidak ditemukan atau tidak enabled."));
        }

        if (economy != null) {
            checks.add(pass("economy-provider", "Vault economy bridge tersedia."));
        } else {
            checks.add(fail("economy-provider", "Tidak ada economy provider aktif."));
        }
    }

    private void checkConfig(List<Check> checks) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            loadStrict(configFile);
            checks.add(pass("config.yml", "YAML dapat dibaca secara strict."));
        } catch (IOException exception) {
            checks.add(fail("config.yml", compact(exception.getMessage())));
        }
    }

    private void checkShops(List<Check> checks) {
        File shopsFile = new File(plugin.getDataFolder(), "shops.yml");
        if (!shopsFile.isFile()) {
            checks.add(fail("shops.yml", "File tidak ditemukan."));
            return;
        }

        ShopRegistry diskRegistry = new ShopRegistry();
        try {
            diskRegistry.load(shopsFile, plugin.getLogger());
        } catch (IOException exception) {
            checks.add(fail("shops.yml", compact(exception.getMessage())));
            return;
        }

        if (diskRegistry.rejectedDefinitionCount() > 0) {
            checks.add(fail("shops.yml", "rejectedDefinitions=" + diskRegistry.rejectedDefinitionCount()));
        } else if (diskRegistry.configurationWarningCount() > 0) {
            checks.add(warn("shops.yml", "valid dengan configWarnings=" + diskRegistry.configurationWarningCount()));
        } else {
            checks.add(pass("shops.yml", "valid, shops=" + diskRegistry.shopCount() + ", listings=" + diskRegistry.listingCount()));
        }

        if (runtimeRegistry != null
                && (runtimeRegistry.shopCount() != diskRegistry.shopCount()
                || runtimeRegistry.listingCount() != diskRegistry.listingCount()
                || runtimeRegistry.activeBindingCount() != diskRegistry.activeBindingCount())) {
            checks.add(warn("runtime-vs-disk", "Konfigurasi disk berbeda dari runtime. Jalankan /cve reload bila perubahan memang ingin diterapkan."));
        } else {
            checks.add(pass("runtime-vs-disk", "Jumlah shop/listing/NPC binding runtime sesuai konfigurasi disk."));
        }
    }

    private void checkNpcBindings(List<Check> checks) {
        if (runtimeRegistry == null) {
            checks.add(fail("npc-bindings", "Shop registry runtime belum siap."));
            return;
        }
        if (runtimeRegistry.activeBindingCount() == 0) {
            checks.add(warn("npc-bindings", "Tidak ada NPC shop aktif."));
            return;
        }

        int valid = 0;
        int missing = 0;
        int unspawned = 0;
        try {
            for (Shop shop : runtimeRegistry.all()) {
                if (!shop.enabled() || shop.npcId() < 0) {
                    continue;
                }
                NPC npc = CitizensAPI.getNPCRegistry().getById(shop.npcId());
                if (npc == null) {
                    missing++;
                } else if (!npc.isSpawned() || npc.getEntity() == null) {
                    unspawned++;
                } else {
                    valid++;
                }
            }
        } catch (RuntimeException exception) {
            checks.add(fail("npc-bindings", "Citizens API gagal diperiksa: " + compact(exception.getMessage())));
            return;
        }

        if (missing > 0) {
            checks.add(fail("npc-bindings", "valid=" + valid + ", missing=" + missing + ", unspawned=" + unspawned));
        } else if (unspawned > 0) {
            checks.add(warn("npc-bindings", "valid=" + valid + ", unspawned=" + unspawned + ". NPC mungkin berada di chunk/world yang belum aktif."));
        } else {
            checks.add(pass("npc-bindings", "Semua " + valid + " binding Citizens aktif dan spawned."));
        }
    }

    private void checkStock(List<Check> checks) {
        if (runtimeRegistry == null || stocks == null) {
            checks.add(fail("stock-runtime", "Stock service belum siap."));
            return;
        }

        File main = new File(plugin.getDataFolder(), "stock.yml");
        File backup = new File(plugin.getDataFolder(), "stock.yml.bak");
        File marker = new File(plugin.getDataFolder(), "stock.yml.initialized");
        File temp = new File(plugin.getDataFolder(), "stock.yml.tmp");

        SnapshotStatus mainStatus = inspectSnapshot(main, true);
        checks.add(new Check(mainStatus.level(), "stock.yml", mainStatus.detail()));

        SnapshotStatus backupStatus = inspectSnapshot(backup, false);
        checks.add(new Check(backupStatus.level(), "stock.yml.bak", backupStatus.detail()));

        if (marker.isFile()) {
            checks.add(pass("stock-marker", "stock.yml.initialized tersedia."));
        } else {
            checks.add(fail("stock-marker", "stock.yml.initialized hilang; perlindungan anti-reset storage melemah."));
        }

        if (temp.exists()) {
            checks.add(warn("stock-temp", "stock.yml.tmp masih ada; kemungkinan write sebelumnya terinterupsi."));
        } else {
            checks.add(pass("stock-temp", "Tidak ada stale temporary stock snapshot."));
        }
    }

    private SnapshotStatus inspectSnapshot(File file, boolean requireRuntimeMatch) {
        if (!file.isFile()) {
            return new SnapshotStatus(requireRuntimeMatch ? Level.FAIL : Level.WARN, "File tidak ditemukan.");
        }

        YamlConfiguration yaml;
        try {
            yaml = loadStrict(file);
        } catch (IOException exception) {
            return new SnapshotStatus(requireRuntimeMatch ? Level.FAIL : Level.WARN, compact(exception.getMessage()));
        }

        if (yaml.getInt("meta.schema", -1) != STOCK_SCHEMA_VERSION) {
            return new SnapshotStatus(requireRuntimeMatch ? Level.FAIL : Level.WARN,
                    "schema tidak valid: " + yaml.getInt("meta.schema", -1));
        }

        int expectedEntries = 0;
        int mismatch = 0;
        int invalid = 0;
        for (Shop shop : runtimeRegistry.all()) {
            for (ShopListing listing : shop.listings().values()) {
                expectedEntries++;
                Object raw = yaml.get("shops." + shop.id() + "." + listing.id());
                if (!(raw instanceof Number number) || !isWholeNumber(number)) {
                    invalid++;
                    continue;
                }
                long value = number.longValue();
                if (value < 0 || value > listing.maxStock()) {
                    invalid++;
                    continue;
                }
                if (requireRuntimeMatch && value != stocks.getStock(shop.id(), listing.id())) {
                    mismatch++;
                }
            }
        }

        if (invalid > 0) {
            return new SnapshotStatus(requireRuntimeMatch ? Level.FAIL : Level.WARN,
                    "invalidEntries=" + invalid + "/" + expectedEntries);
        }
        if (mismatch > 0) {
            return new SnapshotStatus(Level.FAIL,
                    "persisted/runtime mismatch=" + mismatch + "/" + expectedEntries);
        }
        return new SnapshotStatus(Level.PASS, "schema OK, entries=" + expectedEntries
                + (requireRuntimeMatch ? ", runtime sinkron." : ", snapshot valid."));
    }

    private void checkFilesystem(List<Check> checks) {
        Path testFile = null;
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            testFile = Files.createTempFile(plugin.getDataFolder().toPath(), ".cve-doctor-", ".tmp");
            Files.writeString(testFile, "doctor-write-test", StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.delete(testFile);
            testFile = null;
            checks.add(pass("filesystem", "Plugin data folder writable dan temporary file dapat dihapus."));
        } catch (IOException exception) {
            checks.add(fail("filesystem", compact(exception.getMessage())));
        } finally {
            if (testFile != null) {
                try {
                    Files.deleteIfExists(testFile);
                } catch (IOException ignored) {
                    // Diagnostic cleanup is best effort only.
                }
            }
        }
    }

    private void checkAudit(List<Check> checks) {
        if (audit == null) {
            checks.add(fail("audit", "Audit service belum siap."));
            return;
        }

        if (!audit.localEnabled()) {
            checks.add(warn("audit-local", "Local audit dinonaktifkan oleh config."));
        } else if (audit.localWriterReady()) {
            checks.add(pass("audit-local", "audit.log writer aktif."));
        } else {
            checks.add(fail("audit-local", "Local audit diaktifkan tetapi writer tidak tersedia."));
        }

        if (!audit.discordRequested()) {
            checks.add(pass("audit-discord", "Discord audit opsional sedang disabled."));
        } else if (!audit.discordConfigurationValid()) {
            checks.add(fail("audit-discord", "Discord audit enabled tetapi webhook URL kosong/tidak valid."));
        } else {
            checks.add(pass("audit-discord", "Webhook configuration valid secara lokal; network delivery tidak diping oleh doctor."));
        }
    }

    private void checkSafety(List<Check> checks) {
        if (safety == null) {
            checks.add(fail("safety", "Safety service belum siap."));
            return;
        }

        File lock = new File(plugin.getDataFolder(), "safety.lock");
        File temp = new File(plugin.getDataFolder(), "safety.lock.tmp");
        if (safety.isStopped()) {
            String detail = safety.shortStatus()
                    + ", persisted=" + safety.persistenceHealthy()
                    + ", tx=" + (safety.transactionId() == null ? "unknown" : safety.transactionId())
                    + ", reason=" + compact(safety.reason());
            checks.add(fail("safety", detail));
            if (safety.persistenceHealthy() && !lock.isFile()) {
                checks.add(fail("safety-lock", "Safety state mengaku persisted tetapi safety.lock tidak ditemukan."));
            } else if (lock.isFile()) {
                checks.add(pass("safety-lock", "Persistent safety.lock tersedia."));
            }
        } else if (lock.exists() || temp.exists()) {
            checks.add(fail("safety", "Runtime safety=OK tetapi lock/temp file masih ada; restart/investigasi disarankan."));
        } else {
            checks.add(pass("safety", "Safety state OK dan tidak ada persistent lock aktif."));
        }
    }

    private YamlConfiguration loadStrict(File source) throws IOException {
        if (!source.isFile()) {
            throw new IOException(source.getName() + " tidak ditemukan");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + source.getName() + ": " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private static boolean isWholeNumber(Number number) {
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.rint(value);
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
    }

    private static Check pass(String name, String detail) {
        return new Check(Level.PASS, name, detail);
    }

    private static Check warn(String name, String detail) {
        return new Check(Level.WARN, name, detail);
    }

    private static Check fail(String name, String detail) {
        return new Check(Level.FAIL, name, detail);
    }

    public enum Level {
        PASS,
        WARN,
        FAIL
    }

    public record Check(Level level, String name, String detail) {
    }

    public record Report(List<Check> checks, int passCount, int warnCount, int failCount) {
        public Report(List<Check> checks) {
            this(Collections.unmodifiableList(new ArrayList<>(checks)),
                    (int) checks.stream().filter(check -> check.level() == Level.PASS).count(),
                    (int) checks.stream().filter(check -> check.level() == Level.WARN).count(),
                    (int) checks.stream().filter(check -> check.level() == Level.FAIL).count());
        }

        public boolean healthy() {
            return failCount == 0;
        }
    }

    private record SnapshotStatus(Level level, String detail) {
    }
}
