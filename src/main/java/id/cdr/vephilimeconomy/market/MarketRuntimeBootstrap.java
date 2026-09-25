package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import id.cdr.vephilimeconomy.admin.AdminShopGuiEditor;
import id.cdr.vephilimeconomy.discount.PlayerDiscountCommandListener;
import id.cdr.vephilimeconomy.discount.PlayerDiscountService;
import id.cdr.vephilimeconomy.license.LicenseIntegrityGuard;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Owns non-reloadable production hooks for one plugin instance.
 *
 * <p>Dynamic pricing and transaction listeners are rebuilt by /cve reload, but
 * the license monitor, supply command interceptor, personal-discount commands,
 * in-game shop editor, and natural-expiry lifecycle driver must only be registered
 * once for the lifetime of the plugin instance.</p>
 */
public final class MarketRuntimeBootstrap {
    private static final Set<CdrVephilimEconomy> STARTED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private MarketRuntimeBootstrap() {
    }

    public static synchronized boolean start(CdrVephilimEconomy plugin, AdminAuditService audit) {
        if (plugin == null || audit == null) {
            return false;
        }
        if (!STARTED.add(plugin)) {
            plugin.getLogger().warning("Production runtime bootstrap already active; duplicate registration skipped.");
            return true;
        }

        LicenseIntegrityGuard licenseGuard = new LicenseIntegrityGuard(plugin);
        try {
            licenseGuard.initializeOrThrow();
            licenseGuard.startMonitoring();
        } catch (IllegalStateException licenseFailure) {
            STARTED.remove(plugin);
            plugin.getLogger().severe("MENKIESTES runtime license bootstrap failed: " + licenseFailure.getMessage());
            throw licenseFailure;
        }

        MarketSupplyCommandListener supplyListener = null;
        PlayerDiscountCommandListener discountListener = null;
        AdminShopGuiEditor shopEditor = null;
        try {
            supplyListener = new MarketSupplyCommandListener(plugin, audit);
            plugin.getServer().getPluginManager().registerEvents(supplyListener, plugin);

            PlayerDiscountService discountService = PlayerDiscountService.install(plugin, audit);
            discountListener = new PlayerDiscountCommandListener(plugin, discountService);
            plugin.getServer().getPluginManager().registerEvents(discountListener, plugin);

            shopEditor = new AdminShopGuiEditor(plugin);
            plugin.getServer().getPluginManager().registerEvents(shopEditor, plugin);

            MarketEventLifecycleService lifecycle = new MarketEventLifecycleService(plugin, audit);
            lifecycle.start();

            plugin.getLogger().info("Production runtime bootstrap active: licenseGuard=1, supplyListener=1, discountListener=1, shopEditor=1, expiryLifecycle=1.");
            return true;
        } catch (RuntimeException exception) {
            if (supplyListener != null) {
                HandlerList.unregisterAll(supplyListener);
            }
            if (discountListener != null) {
                HandlerList.unregisterAll(discountListener);
            }
            if (shopEditor != null) {
                HandlerList.unregisterAll(shopEditor);
            }
            licenseGuard.stopMonitoring();
            STARTED.remove(plugin);
            plugin.getLogger().severe("Production runtime bootstrap gagal: " + exception.getMessage());
            return false;
        }
    }
}
