package id.cdr.vephilimeconomy.transaction;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerTransactionStateListener implements Listener {
    private final TransactionService transactions;

    public PlayerTransactionStateListener(TransactionService transactions) {
        this.transactions = transactions;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        transactions.forgetPlayer(event.getPlayer().getUniqueId());
    }
}
