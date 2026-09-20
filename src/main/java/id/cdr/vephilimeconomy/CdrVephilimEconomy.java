package id.cdr.vephilimeconomy;

import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.economy.VaultEconomyBridge;
import id.cdr.vephilimeconomy.gui.ShopGuiListener;
import id.cdr.vephilimeconomy.gui.ShopGuiService;
import id.cdr.vephilimeconomy.npc.CitizensNpcListener;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class CdrVephilimEconomy extends JavaPlugin {
    private StockRepository stockRepository;
    private AuditService auditService;

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

        stockRepository = new StockRepository(new File(getDataFolder(), "stock.yml"));
        try {
            stockRepository.load(shopRegistry);
        } catch (IOException exception) {
            getLogger().severe("Gagal memuat persistent stock: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            auditService = new AuditService(
                    this,
                    getConfig().getBoolean("audit.local-enabled", true),
                    getConfig().getBoolean("audit.discord.enabled", false),
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

        TransactionService transactions = new TransactionService(
                economy,
                stockRepository,
                auditService,
                getLogger(),
                cooldownMillis,
                maxAmount
        );

        ShopGuiService gui = new ShopGuiService(stockRepository, economy);
        getServer().getPluginManager().registerEvents(new CitizensNpcListener(shopRegistry, gui), this);
        getServer().getPluginManager().registerEvents(
                new ShopGuiListener(
                        this,
                        shopRegistry,
                        gui,
                        transactions,
                        economy,
                        bulkAmount,
                        maxNpcDistance
                ),
                this
        );

        getLogger().info("CdrVephilimEconomy beta.1 core enabled: NPC-only shop, static pricing, stock, guarded transactions, audit.");
        getLogger().info("Transaction guard: cooldown=" + cooldownMillis + "ms, bulk=" + bulkAmount
                + ", max=" + maxAmount + ", npcDistance=" + maxNpcDistance + ".");
    }

    @Override
    public void onDisable() {
        if (stockRepository != null) {
            try {
                stockRepository.flush();
            } catch (IOException exception) {
                getLogger().severe("Gagal flush stock saat shutdown: " + exception.getMessage());
            }
        }
        if (auditService != null) {
            auditService.close();
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
