package id.cdr.vephilimeconomy;

import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.command.CveCommand;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.economy.VaultEconomyBridge;
import id.cdr.vephilimeconomy.gui.ShopGuiListener;
import id.cdr.vephilimeconomy.gui.ShopGuiService;
import id.cdr.vephilimeconomy.gui.ShopInventoryHolder;
import id.cdr.vephilimeconomy.npc.CitizensNpcListener;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.PlayerTransactionStateListener;
import id.cdr.vephilimeconomy.transaction.RuntimeSafetyState;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class CdrVephilimEconomy extends JavaPlugin {
    private final RuntimeSafetyState safetyState = new RuntimeSafetyState();

    private EconomyBridge economy;
    private ShopRegistry shopRegistry;
    private StockRepository stockRepository;
    private AuditService auditService;
    private TransactionService transactionService;
    private ShopGuiService guiService;
    private CitizensNpcListener citizensNpcListener;
    private ShopGuiListener shopGuiListener;
    private PlayerTransactionStateListener transactionStateListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureResource("shops.yml");

        economy = setupEconomy();
        if (economy == null) {
            getLogger().severe("Vault ditemukan, tetapi tidak ada economy provider yang terdaftar. Plugin dinonaktifkan.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ShopRegistry initialRegistry = new ShopRegistry();
        try {
            initialRegistry.load(new File(getDataFolder(), "shops.yml"), getLogger());
        } catch (IOException exception) {
            getLogger().severe("Gagal memuat shops.yml secara aman: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        stockRepository = new StockRepository(new File(getDataFolder(), "stock.yml"), getLogger());
        try {
            stockRepository.load(initialRegistry);
        } catch (IOException exception) {
            getLogger().severe("Gagal memuat persistent stock secara aman: " + exception.getMessage());
            getLogger().severe("Plugin dinonaktifkan untuk mencegah reset/dupe stock yang tidak terdeteksi.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RuntimeSettings initialSettings;
        try {
            initialSettings = readSettingsStrictFromDisk();
            reloadConfig();
        } catch (IOException exception) {
            getLogger().severe("Gagal memuat config.yml secara aman: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            installRuntime(initialRegistry, initialSettings);
        } catch (IOException exception) {
            getLogger().severe("Gagal menyiapkan runtime economy: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("cve");
        if (command == null) {
            getLogger().severe("Command /cve tidak terdaftar di plugin.yml. Plugin dinonaktifkan.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CveCommand commandHandler = new CveCommand(this);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        logDiagnostics("Startup");
    }

    @Override
    public void onDisable() {
        closeOpenShopInventories();
        unregisterRuntimeListeners();

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

        shopRegistry = null;
        guiService = null;
        economy = null;
    }

    public synchronized ReloadResult reloadRuntime() {
        if (!isEnabled() || stockRepository == null || economy == null) {
            return new ReloadResult(false, "Plugin belum berada pada runtime state yang dapat direload.");
        }

        ShopRegistry candidate = new ShopRegistry();
        try {
            candidate.load(new File(getDataFolder(), "shops.yml"), getLogger());
        } catch (IOException exception) {
            return new ReloadResult(false, "shops.yml tidak valid: " + exception.getMessage());
        }

        if (candidate.rejectedDefinitionCount() > 0) {
            return new ReloadResult(false, "Reload dibatalkan karena ada "
                    + candidate.rejectedDefinitionCount() + " definisi shop yang ditolak. Runtime lama tetap aktif.");
        }

        RuntimeSettings candidateSettings;
        try {
            candidateSettings = readSettingsStrictFromDisk();
        } catch (IOException exception) {
            return new ReloadResult(false, "config.yml tidak valid: " + exception.getMessage()
                    + ". Runtime lama tetap aktif.");
        }

        AuditService newAudit;
        try {
            newAudit = createAuditService(candidateSettings);
        } catch (IOException exception) {
            return new ReloadResult(false, "Audit service baru gagal dibuka: " + exception.getMessage());
        }

        TransactionService newTransactions = createTransactionService(newAudit, candidateSettings);
        ShopGuiService newGui = new ShopGuiService(stockRepository, economy, candidateSettings.bulkAmount());
        CitizensNpcListener newCitizensListener = new CitizensNpcListener(candidate, newGui);
        ShopGuiListener newGuiListener = new ShopGuiListener(
                this,
                candidate,
                newGui,
                newTransactions,
                economy,
                candidateSettings.bulkAmount(),
                candidateSettings.maxNpcDistance()
        );
        PlayerTransactionStateListener newStateListener = new PlayerTransactionStateListener(newTransactions);

        try {
            getServer().getPluginManager().registerEvents(newCitizensListener, this);
            getServer().getPluginManager().registerEvents(newGuiListener, this);
            getServer().getPluginManager().registerEvents(newStateListener, this);
        } catch (RuntimeException exception) {
            unregisterListeners(newCitizensListener, newGuiListener, newStateListener);
            newAudit.close();
            return new ReloadResult(false, "Listener runtime baru gagal diregistrasikan. Runtime lama tetap aktif: "
                    + exception.getMessage());
        }

        try {
            stockRepository.reconcile(candidate);
        } catch (IOException exception) {
            unregisterListeners(newCitizensListener, newGuiListener, newStateListener);
            newAudit.close();
            return new ReloadResult(false, "Stock reconcile gagal. Runtime lama tetap aktif: " + exception.getMessage());
        }

        closeOpenShopInventories();
        unregisterRuntimeListeners();

        if (transactionService != null) {
            transactionService.clearState();
        }
        if (auditService != null) {
            auditService.close();
        }

        shopRegistry = candidate;
        auditService = newAudit;
        transactionService = newTransactions;
        guiService = newGui;
        citizensNpcListener = newCitizensListener;
        shopGuiListener = newGuiListener;
        transactionStateListener = newStateListener;

        reloadConfig();
        logDiagnostics("Reload");

        String warningSuffix = candidate.configurationWarningCount() > 0
                ? " Ada " + candidate.configurationWarningCount() + " warning konfigurasi; cek console."
                : "";
        String safetySuffix = safetyState.isStopped()
                ? " ECONOMY SAFETY STOP masih aktif; reload tidak mereset safety latch."
                : "";
        return new ReloadResult(true, "Reload aman selesai. Restart server tidak diperlukan." + warningSuffix + safetySuffix);
    }

    public String statusSummary() {
        if (shopRegistry == null || stockRepository == null) {
            return "runtime belum siap";
        }
        String summary = "version=" + getDescription().getVersion()
                + ", shops=" + shopRegistry.shopCount()
                + ", enabled=" + shopRegistry.enabledShopCount()
                + ", npcBindings=" + shopRegistry.activeBindingCount()
                + ", listings=" + shopRegistry.listingCount()
                + ", stockEntries=" + stockRepository.entryCount()
                + ", configWarnings=" + shopRegistry.configurationWarningCount()
                + ", safety=" + safetyState.shortStatus();
        if (safetyState.isStopped()) {
            summary += ", reason=" + compactReason(safetyState.reason());
        }
        return summary;
    }

    private void installRuntime(ShopRegistry registry, RuntimeSettings settings) throws IOException {
        AuditService newAudit = createAuditService(settings);
        TransactionService newTransactions = createTransactionService(newAudit, settings);
        ShopGuiService newGui = new ShopGuiService(stockRepository, economy, settings.bulkAmount());
        CitizensNpcListener newCitizensListener = new CitizensNpcListener(registry, newGui);
        ShopGuiListener newGuiListener = new ShopGuiListener(
                this,
                registry,
                newGui,
                newTransactions,
                economy,
                settings.bulkAmount(),
                settings.maxNpcDistance()
        );
        PlayerTransactionStateListener newStateListener = new PlayerTransactionStateListener(newTransactions);

        getServer().getPluginManager().registerEvents(newCitizensListener, this);
        getServer().getPluginManager().registerEvents(newGuiListener, this);
        getServer().getPluginManager().registerEvents(newStateListener, this);

        shopRegistry = registry;
        auditService = newAudit;
        transactionService = newTransactions;
        guiService = newGui;
        citizensNpcListener = newCitizensListener;
        shopGuiListener = newGuiListener;
        transactionStateListener = newStateListener;
    }

    private AuditService createAuditService(RuntimeSettings settings) throws IOException {
        return new AuditService(
                this,
                settings.localAuditEnabled(),
                settings.discordAuditEnabled(),
                settings.discordIncludeRejected(),
                settings.webhookUrl()
        );
    }

    private TransactionService createTransactionService(AuditService audit, RuntimeSettings settings) {
        return new TransactionService(
                economy,
                stockRepository,
                audit,
                getLogger(),
                safetyState,
                settings.cooldownMillis(),
                settings.maxAmount(),
                settings.auditRejected(),
                settings.auditBusyRejected()
        );
    }

    private RuntimeSettings readSettingsStrictFromDisk() throws IOException {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(configFile);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in config.yml: " + exception.getMessage(), exception);
        }
        return readSettings(yaml);
    }

    private RuntimeSettings readSettings() {
        return readSettings(getConfig());
    }

    private RuntimeSettings readSettings(ConfigurationSection config) {
        int maxAmount = clamp(config.getInt("transaction.max-amount", 64), 1, 2304);
        int bulkAmount = clamp(config.getInt("transaction.bulk-amount", 16), 1, maxAmount);
        long cooldownMillis = Math.max(0L, config.getLong("transaction.cooldown-ms", 250L));
        double maxNpcDistance = clamp(config.getDouble("npc.max-transaction-distance", 6.0D), 1.0D, 32.0D);

        return new RuntimeSettings(
                maxAmount,
                bulkAmount,
                cooldownMillis,
                maxNpcDistance,
                config.getBoolean("audit.log-rejected-transactions", true),
                config.getBoolean("audit.log-busy-rejections", false),
                config.getBoolean("audit.local-enabled", true),
                config.getBoolean("audit.discord.enabled", false),
                config.getBoolean("audit.discord.include-rejected", false),
                config.getString("audit.discord.webhook-url", "")
        );
    }

    private void logDiagnostics(String phase) {
        if (shopRegistry == null || stockRepository == null) {
            return;
        }
        RuntimeSettings settings = readSettings();
        getLogger().info("CdrVephilimEconomy " + getDescription().getVersion()
                + " " + phase.toLowerCase() + ": NPC-only shop, static pricing, persistent stock, guarded transactions, audit.");
        getLogger().info(phase + " diagnostics: shops=" + shopRegistry.shopCount()
                + ", enabled=" + shopRegistry.enabledShopCount()
                + ", npcBindings=" + shopRegistry.activeBindingCount()
                + ", listings=" + shopRegistry.listingCount()
                + ", stockEntries=" + stockRepository.entryCount()
                + ", rejectedDefinitions=" + shopRegistry.rejectedDefinitionCount()
                + ", configWarnings=" + shopRegistry.configurationWarningCount()
                + ", safety=" + safetyState.shortStatus() + ".");
        getLogger().info("Transaction guard: cooldown=" + settings.cooldownMillis() + "ms, bulk=" + settings.bulkAmount()
                + ", max=" + settings.maxAmount() + ", npcDistance=" + settings.maxNpcDistance() + ".");
        getLogger().info("Audit policy: rejected=" + settings.auditRejected()
                + ", busyRejected=" + settings.auditBusyRejected()
                + ", discordIncludeRejected=" + settings.discordIncludeRejected() + ".");

        if (safetyState.isStopped()) {
            getLogger().severe("Economy safety stop aktif: " + safetyState.reason());
        }
        if (shopRegistry.activeBindingCount() == 0) {
            getLogger().warning("Tidak ada active NPC binding. Isi npc-id dan enabled di shops.yml lalu gunakan /cve reload.");
        }
    }

    private void unregisterRuntimeListeners() {
        if (citizensNpcListener != null) {
            HandlerList.unregisterAll(citizensNpcListener);
            citizensNpcListener = null;
        }
        if (shopGuiListener != null) {
            HandlerList.unregisterAll(shopGuiListener);
            shopGuiListener = null;
        }
        if (transactionStateListener != null) {
            HandlerList.unregisterAll(transactionStateListener);
            transactionStateListener = null;
        }
    }

    private static void unregisterListeners(CitizensNpcListener citizens, ShopGuiListener gui,
                                            PlayerTransactionStateListener state) {
        HandlerList.unregisterAll(citizens);
        HandlerList.unregisterAll(gui);
        HandlerList.unregisterAll(state);
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

    private static String compactReason(String reason) {
        if (reason == null) {
            return "unknown";
        }
        String compact = reason.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 160 ? compact : compact.substring(0, 157) + "...";
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

    public record ReloadResult(boolean success, String message) {
    }

    private record RuntimeSettings(
            int maxAmount,
            int bulkAmount,
            long cooldownMillis,
            double maxNpcDistance,
            boolean auditRejected,
            boolean auditBusyRejected,
            boolean localAuditEnabled,
            boolean discordAuditEnabled,
            boolean discordIncludeRejected,
            String webhookUrl
    ) {
    }
}
