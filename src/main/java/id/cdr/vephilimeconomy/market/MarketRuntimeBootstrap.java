package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Owns the non-reloadable beta.5 market runtime hooks for one plugin instance.
 *
 * <p>Dynamic pricing and transaction listeners are rebuilt by /cve reload, but
 * the supply command interceptor and natural-expiry lifecycle driver must only
 * be registered once for the lifetime of the plugin instance. RC3 removed
 * hidden constructor registration; this bootstrap makes that ownership
 * explicit without reintroducing duplicate listeners.</p>
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
            plugin.getLogger().warning("Beta.5 market runtime bootstrap already active; duplicate registration skipped.");
            return true;
        }

        MarketSupplyCommandListener supplyListener = null;
        try {
            supplyListener = new MarketSupplyCommandListener(plugin, audit);
            plugin.getServer().getPluginManager().registerEvents(supplyListener, plugin);

            MarketEventLifecycleService lifecycle = new MarketEventLifecycleService(plugin, audit);
            lifecycle.start();

            plugin.getLogger().info("Beta.5 market runtime bootstrap active: supplyListener=1, expiryLifecycle=1.");
            return true;
        } catch (RuntimeException exception) {
            if (supplyListener != null) {
                HandlerList.unregisterAll(supplyListener);
            }
            STARTED.remove(plugin);
            plugin.getLogger().severe("Beta.5 market runtime bootstrap gagal: " + exception.getMessage());
            return false;
        }
    }
}
