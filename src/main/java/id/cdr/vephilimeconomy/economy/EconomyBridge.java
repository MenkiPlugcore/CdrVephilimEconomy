package id.cdr.vephilimeconomy.economy;

import org.bukkit.OfflinePlayer;

public interface EconomyBridge {
    boolean has(OfflinePlayer player, double amount);

    OperationResult withdraw(OfflinePlayer player, double amount);

    OperationResult deposit(OfflinePlayer player, double amount);

    String format(double amount);

    record OperationResult(boolean success, String errorMessage) {
        public static OperationResult ok() {
            return new OperationResult(true, "");
        }

        public static OperationResult failed(String message) {
            return new OperationResult(false, message == null ? "unknown economy error" : message);
        }
    }
}
