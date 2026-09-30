package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Periodic BUY-side stock restock for NPC shops.
 *
 * <p>Only BUY-capable listings are eligible. SELL-only farmer/fisherman/ore style
 * shops are therefore player-supplied by default. Per-shop and per-listing
 * overrides can disable or tune interval/amount without touching Java code.</p>
 *
 * <p>Durability rule: due timestamps are persisted before stock is mutated.
 * A crash can therefore delay one restock cycle, but cannot replay the same
 * scheduled cycle and inflate stock twice.</p>
 */
public final class AutoRestockService {
    private static final int STATE_SCHEMA = 1;
    private static final long CHECK_PERIOD_TICKS = 20L * 60L;

    private final CdrVephilimEconomy plugin;
    private final AdminAuditService audit;
    private final File stateFile;
    private final File backupFile;
    private final File tempFile;

    private BukkitTask task;
    private boolean healthy = true;

    public AutoRestockService(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.audit = audit;
        this.stateFile = new File(plugin.getDataFolder(), "restock-state.yml");
        this.backupFile = new File(plugin.getDataFolder(), "restock-state.yml.bak");
        this.tempFile = new File(plugin.getDataFolder(), "restock-state.yml.tmp");
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickSafe,
                CHECK_PERIOD_TICKS,
                CHECK_PERIOD_TICKS
        );
        plugin.getLogger().info("Auto restock scheduler active: check=60s, configPath=restock.*");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public String statusSummary() {
        return "enabled=" + plugin.getConfig().getBoolean("restock.enabled", true)
                + ", healthy=" + healthy
                + ", interval=" + globalIntervalMinutes() + "m"
                + ", amount=" + globalAmount();
    }

    private void tickSafe() {
        try {
            runCycle();
        } catch (Exception exception) {
            healthy = false;
            plugin.getLogger().severe("Auto restock cycle dihentikan fail-closed: " + exception.getMessage());
        }
    }

    private void runCycle() throws IOException {
        if (!healthy || !plugin.isEnabled()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("restock.enabled", true)) {
            return;
        }
        if (plugin.isEconomySafetyStopped()) {
            return;
        }

        ShopAdminService shopAdmin = plugin.shopAdminService();
        if (shopAdmin == null) {
            return;
        }

        YamlConfiguration state = loadState();
        long now = System.currentTimeMillis();
        List<DueRestock> due = new ArrayList<>();
        boolean seeded = false;

        for (String shopId : plugin.runtimeShopIds()) {
            Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
            if (shop == null || !shop.enabled() || !shopEnabled(shop.id())) {
                continue;
            }

            for (ShopListing listing : shop.listings().values()) {
                if (!listing.mode().canBuy() || !listingEnabled(shop.id(), listing.id())) {
                    continue;
                }

                int intervalMinutes = intervalMinutes(shop.id(), listing.id());
                int amount = amount(shop.id(), listing.id());
                if (intervalMinutes < 1 || amount < 1) {
                    continue;
                }

                String path = statePath(shop.id(), listing.id());
                long lastRun = state.getLong(path, 0L);
                if (lastRun <= 0L) {
                    state.set(path, now);
                    seeded = true;
                    continue;
                }

                long intervalMillis = intervalMinutes * 60_000L;
                if (now - lastRun >= intervalMillis) {
                    due.add(new DueRestock(shop, listing, amount));
                    // Persist schedule progress before stock mutation to avoid replay after crash.
                    state.set(path, now);
                }
            }
        }

        if (seeded || !due.isEmpty()) {
            state.set("meta.schema", STATE_SCHEMA);
            state.set("meta.updated-at", Instant.now().toString());
            saveState(state);
        }

        if (due.isEmpty()) {
            return;
        }

        YamlConfiguration stockSnapshot = loadStockSnapshot();
        int listingsChanged = 0;
        long itemsAdded = 0L;

        for (DueRestock entry : due) {
            Shop shop = entry.shop();
            ShopListing listing = entry.listing();
            int current = Math.max(0, stockSnapshot.getInt(
                    "shops." + shop.id() + "." + listing.id(),
                    listing.initialStock()
            ));
            current = Math.min(current, listing.maxStock());
            int capacity = listing.maxStock() - current;
            int delta = Math.min(entry.amount(), capacity);
            if (delta <= 0) {
                continue;
            }

            ShopAdminService.Result result = shopAdmin.changeRuntimeStock(
                    "SYSTEM_RESTOCK",
                    shop.id(),
                    listing.id(),
                    ShopAdminService.StockOperation.ADD,
                    delta
            );
            if (!result.success()) {
                plugin.getLogger().warning("Auto restock gagal untuk " + shop.id() + "/" + listing.id()
                        + ": " + result.message());
                continue;
            }

            listingsChanged++;
            itemsAdded += delta;
            stockSnapshot.set("shops." + shop.id() + "." + listing.id(), current + delta);
        }

        if (listingsChanged > 0) {
            String detail = "listings=" + listingsChanged + "; itemsAdded=" + itemsAdded;
            try {
                audit.record("SYSTEM", "AUTO_RESTOCK_CYCLE", detail);
            } catch (IOException exception) {
                plugin.getLogger().warning("Auto restock berhasil tetapi cycle audit gagal: " + exception.getMessage());
            }
            plugin.getLogger().info("Auto restock selesai: " + detail + ".");
        }
    }

    private boolean shopEnabled(String shopId) {
        return plugin.getConfig().getBoolean("restock.shops." + shopId + ".enabled", true);
    }

    private boolean listingEnabled(String shopId, String listingId) {
        return plugin.getConfig().getBoolean(
                "restock.shops." + shopId + ".listings." + listingId + ".enabled",
                true
        );
    }

    private int intervalMinutes(String shopId, String listingId) {
        int global = globalIntervalMinutes();
        int shop = Math.max(1, plugin.getConfig().getInt(
                "restock.shops." + shopId + ".interval-minutes",
                global
        ));
        return Math.max(1, plugin.getConfig().getInt(
                "restock.shops." + shopId + ".listings." + listingId + ".interval-minutes",
                shop
        ));
    }

    private int amount(String shopId, String listingId) {
        int global = globalAmount();
        int shop = Math.max(1, plugin.getConfig().getInt(
                "restock.shops." + shopId + ".amount",
                global
        ));
        return Math.max(1, plugin.getConfig().getInt(
                "restock.shops." + shopId + ".listings." + listingId + ".amount",
                shop
        ));
    }

    private int globalIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("restock.interval-minutes", 360));
    }

    private int globalAmount() {
        return Math.max(1, plugin.getConfig().getInt("restock.amount", 128));
    }

    private YamlConfiguration loadState() throws IOException {
        if (!stateFile.isFile()) {
            YamlConfiguration fresh = new YamlConfiguration();
            fresh.set("meta.schema", STATE_SCHEMA);
            return fresh;
        }
        try {
            return loadStrict(stateFile, true);
        } catch (IOException primary) {
            if (backupFile.isFile()) {
                YamlConfiguration backup = loadStrict(backupFile, true);
                Files.copy(backupFile.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().warning("restock-state.yml dipulihkan dari backup.");
                return backup;
            }
            throw primary;
        }
    }

    private YamlConfiguration loadStockSnapshot() throws IOException {
        File stockFile = new File(plugin.getDataFolder(), "stock.yml");
        return loadStrict(stockFile, false);
    }

    private YamlConfiguration loadStrict(File file, boolean validateStateSchema) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + file.getName() + ": " + exception.getMessage(), exception);
        }
        if (validateStateSchema) {
            int schema = yaml.getInt("meta.schema", STATE_SCHEMA);
            if (schema != STATE_SCHEMA) {
                throw new IOException(file.getName() + " schema tidak didukung: " + schema);
            }
        }
        return yaml;
    }

    private void saveState(YamlConfiguration state) throws IOException {
        File parent = stateFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Tidak dapat membuat plugin data directory untuk restock state.");
        }

        state.save(tempFile);
        loadStrict(tempFile, true);
        moveReplace(tempFile, stateFile);
        Files.copy(stateFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String statePath(String shopId, String listingId) {
        return "shops." + shopId + "." + listingId + ".last-run";
    }

    private record DueRestock(Shop shop, ShopListing listing, int amount) {
    }
}
