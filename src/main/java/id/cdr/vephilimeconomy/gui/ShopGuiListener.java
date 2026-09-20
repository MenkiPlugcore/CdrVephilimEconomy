package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.transaction.TransactionFailure;
import id.cdr.vephilimeconomy.transaction.TransactionResult;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class ShopGuiListener implements Listener {
    private final JavaPlugin plugin;
    private final ShopRegistry registry;
    private final ShopGuiService gui;
    private final TransactionService transactions;
    private final EconomyBridge economy;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final int bulkAmount;

    public ShopGuiListener(JavaPlugin plugin, ShopRegistry registry, ShopGuiService gui,
                           TransactionService transactions, EconomyBridge economy, int bulkAmount) {
        this.plugin = plugin;
        this.registry = registry;
        this.gui = gui;
        this.transactions = transactions;
        this.economy = economy;
        this.bulkAmount = Math.max(1, bulkAmount);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopInventoryHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != top) {
            return;
        }

        Shop shop = registry.findById(holder.shopId()).orElse(null);
        if (shop == null || !shop.enabled()) {
            player.closeInventory();
            return;
        }

        ShopListing listing = shop.listingBySlot(event.getRawSlot());
        if (listing == null) {
            return;
        }

        TransactionType type;
        int amount;
        ClickType click = event.getClick();
        if (click == ClickType.LEFT) {
            type = TransactionType.BUY;
            amount = 1;
        } else if (click == ClickType.SHIFT_LEFT) {
            type = TransactionType.BUY;
            amount = bulkAmount;
        } else if (click == ClickType.RIGHT) {
            type = TransactionType.SELL;
            amount = 1;
        } else if (click == ClickType.SHIFT_RIGHT) {
            type = TransactionType.SELL;
            amount = bulkAmount;
        } else {
            return;
        }

        TransactionResult result = transactions.execute(player, shop, listing, type, amount);
        sendResult(player, listing, type, result);

        if (result.success()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    gui.open(player, shop);
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopInventoryHolder)) {
            return;
        }
        int topSize = top.getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private void sendResult(Player player, ShopListing listing, TransactionType type, TransactionResult result) {
        String message;
        if (result.success()) {
            String key = type == TransactionType.BUY ? "messages.buy-success" : "messages.sell-success";
            message = plugin.getConfig().getString(key, "<green>Transaksi berhasil.</green>")
                    .replace("{amount}", Integer.toString(result.amount()))
                    .replace("{item}", prettyName(listing.material()))
                    .replace("{total}", escapeMini(economy.format(result.total())));
        } else {
            message = plugin.getConfig().getString(messagePath(result.failure()), "<red>Transaksi tidak dapat dilakukan.</red>");
        }

        String prefix = plugin.getConfig().getString("messages.prefix", "");
        player.sendMessage(miniMessage.deserialize(prefix + message));
    }

    private static String messagePath(TransactionFailure failure) {
        return switch (failure) {
            case INSUFFICIENT_MONEY -> "messages.insufficient-money";
            case INSUFFICIENT_STOCK -> "messages.insufficient-stock";
            case INSUFFICIENT_ITEMS -> "messages.insufficient-items";
            case INVENTORY_FULL -> "messages.inventory-full";
            case MAX_STOCK -> "messages.max-stock";
            case BUSY -> "messages.busy";
            case INTERNAL_ERROR -> "messages.internal-error";
            case NOT_ALLOWED, NONE -> "messages.internal-error";
        };
    }

    private static String prettyName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private static String escapeMini(String value) {
        return value.replace("<", "\\<").replace(">", "\\>");
    }
}
