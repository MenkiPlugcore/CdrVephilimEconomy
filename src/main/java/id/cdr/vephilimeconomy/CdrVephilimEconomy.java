package id.cdr.vephilimeconomy;

import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.admin.ShopAdminService;
import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.command.CveCommand;
import id.cdr.vephilimeconomy.diagnostic.DoctorService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.economy.VaultEconomyBridge;
import id.cdr.vephilimeconomy.gui.ShopGuiListener;
import id.cdr.vephilimeconomy.gui.ShopGuiService;
import id.cdr.vephilimeconomy.gui.ShopInventoryHolder;
import id.cdr.vephilimeconomy.npc.CitizensNpcListener;
import id.cdr.vephilimeconomy.pricing.DynamicPricingService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.PendingTransactionJournal;
import id.cdr.vephilimeconomy.transaction.PlayerTransactionStateListener;
import id.cdr.vephilimeconomy.transaction.RuntimeSafetyState;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import id.cdr.vephilimeconomy.transaction.TransactionType;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CdrVephilimEconomy extends JavaPlugin {
    private RuntimeSafetyState safetyState;
    private PendingTransactionJournal pendingJournal;
    private EconomyBridge economy;
    private ShopRegistry shopRegistry;
    private StockRepository stockRepository;
    private DynamicPricingService dynamicPricingService;
    private AuditService auditService;
    private AdminAuditService adminAuditService;
    private ShopAdminService shopAdminService;
    private TransactionService transactionService;
    private ShopGuiService guiService;
    private CitizensNpcListener citizensNpcListener;
    private ShopGuiListener shopGuiListener;
    private PlayerTransactionStateListener transactionStateListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureResource("shops.yml");
        ensureResource("pricing.yml");

        safetyState = new RuntimeSafetyState(getDataFolder(), getLogger());
        safetyState.load();

        pendingJournal = new PendingTransactionJournal(getDataFolder(), getLogger());
        PendingTransactionJournal.ScanResult startupPending = pendingJournal.scanPending();
        if (startupPending.total() > 0) {
            String reason = "PENDING_TRANSACTION_RECOVERY_REQUIRED: " + startupPending.summary();
            if (!safetyState.isStopped()) {
                safetyState.trip(startupPending.firstTransactionId(), reason);
            }
            getLogger().severe("Ditemukan pending transaction journal setelah startup/crash. "
                    + "Ekonomi dikunci sampai rekonsiliasi manual dan /cve safety unlock CONFIRM. "
                    + startupPending.summary());
        }

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

        StockRepository candidateStockRepository = new StockRepository(new File(getDataFolder(), "stock.yml"), getLogger());
        try {
            candidateStockRepository.load(initialRegistry);
        } catch (IOException exception) {
            getLogger().severe("Gagal memuat persistent stock secara aman: " + exception.getMessage());
            getLogger().severe("Plugin dinonaktifkan untuk mencegah reset/dupe stock yang tidak terdeteksi.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        stockRepository = candidateStockRepository;

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

        adminAuditService = new AdminAuditService(this);
        shopAdminService = new ShopAdminService(this, stockRepository, adminAuditService);

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

        shopAdminService = null;
        adminAuditService = null;
        dynamicPricingService = null;
        shopRegistry = null;
        guiService = null;
        economy = null;
    }

    public synchronized ReloadResult reloadRuntime() {
        if (!isEnabled() || stockRepository == null || economy == null || safetyState == null || pendingJournal == null) {
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

        DynamicPricingService newPricing;
        try {
            newPricing = loadPricing(candidate);
        } catch (IOException exception) {
            return new ReloadResult(false, "pricing.yml tidak valid: " + exception.getMessage()
                    + ". Runtime lama tetap aktif.");
        }

        AuditService newAudit;
        try {
            newAudit = createAuditService(candidateSettings);
        } catch (IOException exception) {
            return new ReloadResult(false, "Audit service baru gagal dibuka: " + exception.getMessage());
        }

        TransactionService newTransactions = createTransactionService(newAudit, candidateSettings, newPricing);
        ShopGuiService newGui = new ShopGuiService(this, stockRepository, economy, newPricing, candidateSettings.bulkAmount());
        CitizensNpcListener newCitizensListener = new CitizensNpcListener(this, candidate, newGui, safetyState);
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
        dynamicPricingService = newPricing;
        auditService = newAudit;
        transactionService = newTransactions;
        guiService = newGui;
        citizensNpcListener = newCitizensListener;
        shopGuiListener = newGuiListener;
        transactionStateListener = newStateListener;

        reloadConfig();
        logDiagnostics("Reload");

        int totalWarnings = candidate.configurationWarningCount() + newPricing.configurationWarningCount();
        String warningSuffix = totalWarnings > 0
                ? " Ada " + totalWarnings + " warning konfigurasi; cek console."
                : "";
        String safetySuffix = safetyState.isStopped()
                ? " ECONOMY SAFETY STOP masih aktif; reload tidak mereset safety lock."
                : "";
        return new ReloadResult(true, "Reload aman selesai. Restart server tidak diperlukan." + warningSuffix + safetySuffix);
    }

    public DoctorService.Report runDoctor() {
        return new DoctorService(this, shopRegistry, stockRepository, auditService,
                safetyState, pendingJournal, economy).run();
    }

    public ShopAdminService shopAdminService() {
        return shopAdminService;
    }

    public AdminAuditService adminAuditService() {
        return adminAuditService;
    }

    public DynamicPricingService dynamicPricingService() {
        return dynamicPricingService;
    }

    public boolean isEconomySafetyStopped() {
        return safetyState != null && safetyState.isStopped();
    }

    public Optional<Shop> findRuntimeShop(String rawShopId) {
        if (shopRegistry == null || rawShopId == null) {
            return Optional.empty();
        }
        return shopRegistry.findById(rawShopId.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> runtimeShopIds() {
        if (shopRegistry == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Shop shop : shopRegistry.all()) {
            ids.add(shop.id());
        }
        return Collections.unmodifiableList(ids);
    }

    public List<String> runtimeListingIds(String rawShopId) {
        Optional<Shop> shop = findRuntimeShop(rawShopId);
        if (shop.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(shop.get().listings().keySet());
    }

    public List<String> shopListLines() {
        if (shopRegistry == null || shopRegistry.shopCount() == 0) {
            return List.of("§7Belum ada shop runtime.");
        }
        List<String> lines = new ArrayList<>();
        for (Shop shop : shopRegistry.all()) {
            String manager = shop.manager().isBlank() ? "-" : shop.manager();
            lines.add("§e" + shop.id() + " §7| " + (shop.enabled() ? "§aENABLED" : "§cDISABLED")
                    + " §7| npc=§f" + shop.npcId()
                    + " §7| listings=§f" + shop.listings().size()
                    + " §7| manager=§f" + manager);
        }
        return lines;
    }

    public List<String> shopInfoLines(String rawShopId) {
        Optional<Shop> optional = findRuntimeShop(rawShopId);
        if (optional.isEmpty()) {
            return List.of("§cShop tidak ditemukan: " + rawShopId);
        }
        Shop shop = optional.get();
        String manager = shop.manager().isBlank() ? "-" : shop.manager();
        List<String> lines = new ArrayList<>();
        lines.add("§6[CVE Shop] §f" + shop.id() + " §7- " + shop.displayName());
        lines.add("§7enabled=§f" + shop.enabled() + " §7npc=§f" + shop.npcId()
                + " §7size=§f" + shop.size() + " §7manager=§f" + manager);
        if (shop.listings().isEmpty()) {
            lines.add("§7Listings: kosong");
            return lines;
        }
        lines.add("§7Listings:");
        for (ShopListing listing : shop.listings().values()) {
            int stock = stockRepository == null ? -1 : stockRepository.getStock(shop.id(), listing.id());
            String buyDisplay = Double.toString(listing.buyPrice());
            String sellDisplay = Double.toString(listing.sellPrice());
            String dynamic = "";
            if (dynamicPricingService != null && dynamicPricingService.isDynamic(shop.id(), listing.id())) {
                DynamicPricingService.PriceQuote buy = dynamicPricingService.quote(
                        shop, listing, stock, TransactionType.BUY);
                DynamicPricingService.PriceQuote sell = dynamicPricingService.quote(
                        shop, listing, stock, TransactionType.SELL);
                buyDisplay += "→" + buy.effectivePrice();
                sellDisplay += "→" + sell.effectivePrice();
                dynamic = " dynamic=x" + buy.multiplier();
            }
            lines.add("§f- " + listing.id() + " §7" + listing.material().name()
                    + " slot=" + listing.slot() + " mode=" + listing.mode()
                    + " buy=" + buyDisplay + " sell=" + sellDisplay
                    + " stock=" + stock + "/" + listing.maxStock() + dynamic);
        }
        return lines;
    }

    public String statusSummary() {
        if (shopRegistry == null || stockRepository == null || safetyState == null || pendingJournal == null) {
            return "runtime belum siap";
        }
        PendingTransactionJournal.ScanResult pending = pendingJournal.scanPending();
        String pricingStatus = dynamicPricingService == null ? "UNAVAILABLE" : dynamicPricingService.shortStatus();
        String summary = "version=" + getDescription().getVersion()
                + ", shops=" + shopRegistry.shopCount()
                + ", enabled=" + shopRegistry.enabledShopCount()
                + ", npcBindings=" + shopRegistry.activeBindingCount()
                + ", listings=" + shopRegistry.listingCount()
                + ", stockEntries=" + stockRepository.entryCount()
                + ", pricing=" + pricingStatus
                + ", pendingTx=" + pending.total()
                + ", configWarnings=" + shopRegistry.configurationWarningCount()
                + ", safety=" + safetyState.shortStatus();
        if (safetyState.isStopped()) {
            summary += ", tx=" + (safetyState.transactionId() == null ? "unknown" : safetyState.transactionId())
                    + ", persisted=" + safetyState.persistenceHealthy()
                    + ", reason=" + compactReason(safetyState.reason());
        }
        return summary;
    }

    public String pricingStatusSummary() {
        if (dynamicPricingService == null) {
            return "pricing runtime belum siap";
        }
        return "pricing=" + dynamicPricingService.shortStatus()
                + ", warnings=" + dynamicPricingService.configurationWarningCount();
    }

    public String safetyStatusSummary() {
        if (safetyState == null || pendingJournal == null) {
            return "safety runtime belum siap";
        }
        PendingTransactionJournal.ScanResult pending = pendingJournal.scanPending();
        if (!safetyState.isStopped()) {
            return "safety=OK, persistentLock=false, pendingTx=" + pending.total();
        }
        return "safety=" + safetyState.shortStatus()
                + ", persisted=" + safetyState.persistenceHealthy()
                + ", pendingTx=" + pending.total()
                + ", tx=" + (safetyState.transactionId() == null ? "unknown" : safetyState.transactionId())
                + ", stoppedAt=" + safetyState.stoppedAt()
                + ", reason=" + compactReason(safetyState.reason());
    }

    public synchronized ReloadResult unlockSafety(String actor) {
        if (safetyState == null || stockRepository == null || pendingJournal == null) {
            return new ReloadResult(false, "Safety/storage runtime belum siap.");
        }
        if (!safetyState.isStopped()) {
            return new ReloadResult(false, "Safety stop tidak sedang aktif.");
        }

        try {
            stockRepository.flush();
        } catch (IOException exception) {
            return new ReloadResult(false, "Recovery ditolak karena stock snapshot tidak dapat di-flush: "
                    + exception.getMessage());
        }

        PendingTransactionJournal.ArchiveResult archive = pendingJournal.archiveAll(actor);
        if (!archive.success()) {
            return new ReloadResult(false, archive.message());
        }

        RuntimeSafetyState.UnlockResult unlock = safetyState.unlock(actor);
        if (!unlock.success()) {
            return new ReloadResult(false, unlock.message());
        }

        getLogger().warning("Safety recovery selesai oleh " + actor
                + ". Pending evidence archived=" + archive.archived()
                + "; runtime transaksi dibuka kembali tanpa menghapus recovery history.");
        return new ReloadResult(true, unlock.message() + " " + archive.message());
    }

    private void installRuntime(ShopRegistry registry, RuntimeSettings settings) throws IOException {
        DynamicPricingService newPricing = loadPricing(registry);
        AuditService newAudit = createAuditService(settings);
        TransactionService newTransactions = createTransactionService(newAudit, settings, newPricing);
        ShopGuiService newGui = new ShopGuiService(this, stockRepository, economy, newPricing, settings.bulkAmount());
        CitizensNpcListener newCitizensListener = new CitizensNpcListener(this, registry, newGui, safetyState);
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
        dynamicPricingService = newPricing;
        auditService = newAudit;
        transactionService = newTransactions;
        guiService = newGui;
        citizensNpcListener = newCitizensListener;
        shopGuiListener = newGuiListener;
        transactionStateListener = newStateListener;
    }

    private DynamicPricingService loadPricing(ShopRegistry registry) throws IOException {
        DynamicPricingService service = new DynamicPricingService(new File(getDataFolder(), "pricing.yml"), getLogger());
        try {
            service.load(registry);
        } catch (IllegalArgumentException exception) {
            throw new IOException("pricing policy validation failed: " + exception.getMessage(), exception);
        }
        return service;
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

    private TransactionService createTransactionService(AuditService audit, RuntimeSettings settings,
                                                        DynamicPricingService pricing) {
        return new TransactionService(
                economy,
                stockRepository,
                audit,
                getLogger(),
                safetyState,
                pendingJournal,
                pricing,
                this::closeOpenShopInventories,
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
        if (shopRegistry == null || stockRepository == null || safetyState == null || pendingJournal == null) {
            return;
        }
        RuntimeSettings settings = readSettings();
        PendingTransactionJournal.ScanResult pending = pendingJournal.scanPending();
        getLogger().info("CdrVephilimEconomy " + getDescription().getVersion()
                + " " + phase.toLowerCase() + ": NPC-only shop, controlled pricing, persistent stock, guarded transactions, audit.");
        getLogger().info(phase + " diagnostics: shops=" + shopRegistry.shopCount()
                + ", enabled=" + shopRegistry.enabledShopCount()
                + ", npcBindings=" + shopRegistry.activeBindingCount()
                + ", listings=" + shopRegistry.listingCount()
                + ", stockEntries=" + stockRepository.entryCount()
                + ", pricing=" + (dynamicPricingService == null ? "UNAVAILABLE" : dynamicPricingService.shortStatus())
                + ", pendingTx=" + pending.total()
                + ", rejectedDefinitions=" + shopRegistry.rejectedDefinitionCount()
                + ", configWarnings=" + shopRegistry.configurationWarningCount()
                + ", safety=" + safetyState.shortStatus() + ".");
        getLogger().info("Transaction guard: cooldown=" + settings.cooldownMillis() + "ms, bulk=" + settings.bulkAmount()
                + ", max=" + settings.maxAmount() + ", npcDistance=" + settings.maxNpcDistance() + ".");
        getLogger().info("Audit policy: rejected=" + settings.auditRejected()
                + ", busyRejected=" + settings.auditBusyRejected()
                + ", discordIncludeRejected=" + settings.discordIncludeRejected() + ".");
        if (adminAuditService != null && !adminAuditService.isWritable()) {
            getLogger().warning("Admin audit file tidak writable; perubahan shop beta.2 akan ditolak fail-closed.");
        }

        if (pending.total() > 0) {
            getLogger().severe("Pending transaction recovery required: " + pending.summary());
        }
        if (safetyState.isStopped()) {
            getLogger().severe("Economy safety stop aktif dan persisten: tx="
                    + (safetyState.transactionId() == null ? "unknown" : safetyState.transactionId())
                    + ", persisted=" + safetyState.persistenceHealthy()
                    + ", reason=" + safetyState.reason());
        }
        if (shopRegistry.activeBindingCount() == 0) {
            getLogger().warning("Tidak ada active NPC binding. Gunakan /cve shop bind dan /cve shop enable, atau edit shops.yml lalu /cve reload.");
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
