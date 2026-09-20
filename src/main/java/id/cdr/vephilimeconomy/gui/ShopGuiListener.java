package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.transaction.TransactionFailure;
import id.cdr.vephilimeconomy.transaction.TransactionResult;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
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
    private final double maxNpcDistanceSquared;

    public ShopGuiListener(JavaPlugin plugin, ShopRegistry registry, ShopGuiService gui,
                           TransactionService transactions, EconomyBridge economy,
                           int bulkAmount, double maxNpcDistance) {
        this.plugin = plugin;
        this.registry = registry;
        this.gui = gui;
        this.transactions = transactions;
        this.economy = economy;
        this.bulkAmount = Math.max(1, bulkAmount);
        double normalizedDistance = Math.max(1.0D, Math.min(32.0D, maxNpcDistance));
        this.maxNpcDistanceSquared = normalizedDistance * normalizedDistance;
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

        if (!isNearBoundNpc(player, shop)) {
            player.closeInventory();
            sendConfigured(player, "messages.too-far", "<red>Kamu terlalu jauh dari pedagang.</red>");
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

        if (result.failure() == TransactionFailure.SAFETY_STOP) {
            player.closeInventory();
            return;
        }

        if (result.success()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && isNearBoundNpc(player, shop)) {
                    gui.open(player, shop);
                } else if (player.isOnline()) {
                    player.closeInventory();
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ShopInventoryHolder) {
            // Hard-cancel every drag while the shop view is open. This also prevents
            // unusual drag distributions confined to the player inventory from
            // racing a transaction/GUI refresh in the same view.
            event.setCancelled(true);
        }
    }

    private boolean isNearBoundNpc(Player player, Shop shop) {
        if (shop.npcId() < 0) {
            return false;
        }

        NPC npc = CitizensAPI.getNPCRegistry().getById(shop.npcId());
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
            return false;
        }

        Location npcLocation = npc.getEntity().getLocation();
        Location playerLocation = player.getLocation();
        if (npcLocation.getWorld() == null || playerLocation.getWorld() == null
                || !npcLocation.getWorld().equals(playerLocation.getWorld())) {
            return false;
        }

        return npcLocation.distanceSquared(playerLocation) <= maxNpcDistanceSquared;
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

        sendRaw(player, message);
    }

    private void sendConfigured(Player player, String path, String fallback) {
        sendRaw(player, plugin.getConfig().getString(path, fallback));
    }

    private void sendRaw(Player player, String message) {
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
            case NOT_ALLOWED -> "messages.not-allowed";
            case SAFETY_STOP -> "messages.safety-stop";
            case INTERNAL_ERROR, NONE -> "messages.internal-error";
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
