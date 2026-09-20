package id.cdr.vephilimeconomy.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.Objects;

public final class VaultEconomyBridge implements EconomyBridge {
    private final Economy economy;

    public VaultEconomyBridge(Economy economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    @Override
    public OperationResult withdraw(OfflinePlayer player, double amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess()
                ? OperationResult.ok()
                : OperationResult.failed(response.errorMessage);
    }

    @Override
    public OperationResult deposit(OfflinePlayer player, double amount) {
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess()
                ? OperationResult.ok()
                : OperationResult.failed(response.errorMessage);
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }
}
