package id.cdr.vephilimeconomy;

import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.economy.VaultEconomyBridge;
import id.cdr.vephilimeconomy.gui.ShopGuiListener;
import id.cdr.vephilimeconomy.gui.ShopGuiService;
import id.cdr.vephilimeconomy.gui.ShopInventoryHolder;
import id.cdr.vephilimeconomy.npc.CitizensNpcListener;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.PlayerTransactionStateListener;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class CdrVephilimEconomy extends JavaPlugin {
    private StockRepository stockRepository;
    private AuditService auditService;
    private TransactionService transactionService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureResource("shops.yml");

        EconomyBridge economy = setupEconomy();
        if (economy == null) {
            getLogger().severe("Vault ditemukan, tetapi tidak ada economy provider yang terdaftar. Plugin dinonaktifkan.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ShopRegistry shopRegistry = new ShopRegistry();
        shopRegistry.load(new File(getDataFolder(), "shops.yml"), getLogger());

        stockRepository = new StockRepository(new File(getDataFolder(), "stock.yml"), getLogger());
        try {
            stockRepository.load(shopRegistry);
        } catch (IOException exception) {
            getLogger().severe("Gagal memuat persistent stock secara aman: " + exception.getMessage());
            getLogger().severe("Plugin dinonaktifkan untuk mencegah reset/dupe stock yang tidak terdeteksi.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        boolean auditRejected = getConfig().getBoolean("audit.log-rejected-transactions", true);
        boolean auditBusyRejected = getConfig().getBoolean("audit.log-busy-rejections", false);
        boolean discordIncludeRejected = getConfig().getBoolean("audit.discord.include-rejected", false);

        try {
            auditService = new AuditService(
                    this,
                    getConfig().getBoolean("audit.local-enabled", true),
                    getConfig().getBoolean("audit.discord.enabled", false),
                    discordIncludeRejected,
                    getConfig().getString("audit.discord.webhook-url", "")
            );
        } catch (IOException exception) {
            getLogger().severe("Gagal membuka audit log: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        int maxAmount = clamp(getConfig().getInt("transaction.max-amount", 64), 1, 2304);
        int bulkAmount = clamp(getConfig().getInt("transaction.bulk-amount", 16), 1, maxAmount);
        long cooldownMillis = Math.max(0L, getConfig().getLong("transaction.cooldown-ms", 250L));
        double maxNpcDistance = clamp(getConfig().getDouble("npc.max-transaction-distance", 6.0D), 1.0D, 32.0D);

        transactionService = new TransactionService(
                economy,
                stockRepository,
                auditService,
                getLogger(),
                cooldownMillis,
                maxAmount,
                auditRejected,
                auditBusyRejected
        );

        ShopGuiService gui = new ShopGuiService(stockRepository, economy, bulkAmount);
        getServer().getPluginManager().registerEvents(new CitizensNpcListener(shopRegistry, gui), this);
        getServer().getPluginManager().registerEvents(
                new ShopGuiListener(
                        this,
                        shopRegistry,
                        gui,
                        transactionService,
                        economy,
                        bulkAmount,
                        maxNpcDistance
                ),
                this
        );
        getServer().getPluginManager().registerEvents(new PlayerTransactionStateListener(transactionService), this);

        getLogger().info("CdrVephilimEconomy " + getDescription().getVersion()
                + " enabled: NPC-only shop, static pricing, persistent stock, guarded transactions, audit.");
        getLogger().info("Startup diagnostics: shops=" + shopRegistry.shopCount()
                + ", enabled=" + shopRegistry.enabledShopCount()
                + ", npcBindings=" + shopRegistry.activeBindingCount()
                + ", listings=" + shopRegistry.listingCount()
                + ", stockEntries=" + stockRepository.entryCount()
                + ", rejectedDefinitions=" + shopRegistry.rejectedDefinitionCount() + ".");
        getLogger().info("Transaction guard: cooldown=" + cooldownMillis + "ms, bulk=" + bulkAmount
                + ", max=" + maxAmount + ", npcDistance=" + maxNpcDistance + ".");
        getLogger().info("Audit policy: rejected=" + auditRejected + ", busyRejected=" + auditBusyRejected
                + ", discordIncludeRejected=" + discordIncludeRejected + ".");

        if (shopRegistry.activeBindingCount() == 0) {
            getLogger().warning("Tidak ada active NPC binding. Plugin aktif, tetapi player belum dapat melakukan transaksi sampai npc-id di shops.yml diisi dan shop di-enable.");
        }
    }

    @Override
    public void onDisable() {
        closeOpenShopInventories();

        if (transactionService != null) {
            transactionService.clearState();
            transactionService = null;
        }

        if (stockRepository != null) {
            try {
                stockRepository.flush();
                getLogger().info("Stock snapshot berhasil di-flush saat shutdown.");
            } catch (IOException exception) {
                getLogger().severe("Gagal flush stock saat shutdown: " + exception.getMessage());
            } finally {
                stockRepository = null;
            }
        }

        if (auditService != null) {
            auditService.close();
            auditService = null;
        }
    }

    private void closeOpenShopInventories() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopInventoryHolder) {
                player.closeInventory();
            }
        }
    }

    private EconomyBridge setupEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            return null;
        }
        getLogger().info("Vault economy provider: " + provider.getProvider().getName());
        return new VaultEconomyBridge(provider.getProvider());
    }

    private void ensureResource(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
