package id.cdr.vephilimeconomy.market;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.admin.AdminAuditService;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;

/**
 * beta.5 RC3 natural-expiry lifecycle driver.
 *
 * The price effect itself already stops at ends-at inside MarketEvent.activeAt().
 * This service only records that natural expiry durably and emits the RP/history
 * side effects once. It runs on the main thread at a low frequency because the
 * event file is small and lifecycle transitions are rare.
 */
public final class MarketEventLifecycleService {
    private static final long INITIAL_DELAY_TICKS = 40L;
    private static final long PERIOD_TICKS = 1200L; // 60 seconds

    private final CdrVephilimEconomy plugin;
    private final MarketEventService events;
    private BukkitTask task;
    private String lastError = "";

    public MarketEventLifecycleService(CdrVephilimEconomy plugin, AdminAuditService audit) {
        this.plugin = plugin;
        this.events = new MarketEventService(plugin, audit);
    }

    public void start() {
        if (task != null) return;
        tick();
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tick, INITIAL_DELAY_TICKS, PERIOD_TICKS);
    }

    private void tick() {
        try {
            events.load();
            MarketEventService.LifecycleResult result = events.processExpiredLifecycle();
            if (result.recorded() > 0) {
                plugin.getLogger().info("Market expiry lifecycle recorded=" + result.recorded()
                        + ", broadcasts=" + result.broadcasts() + ".");
            }
            lastError = "";
        } catch (IOException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (!message.equals(lastError)) {
                plugin.getLogger().severe("Market expiry lifecycle gagal: " + message);
                lastError = message;
            }
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (!message.equals(lastError)) {
                plugin.getLogger().severe("Market expiry lifecycle runtime failure: " + message);
                lastError = message;
            }
        }
    }
}
