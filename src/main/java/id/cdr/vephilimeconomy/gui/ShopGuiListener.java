package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.pricing.DynamicPricingService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.shop.ShopRegistry;
import id.cdr.vephilimeconomy.transaction.TransactionFailure;
import id.cdr.vephilimeconomy.transaction.TransactionResult;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import id.cdr.vephilimeconomy.util.InventoryUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class ShopGuiListener implements Listener {
    private final JavaPlugin plugin;
    private final ShopRegistry registry;
    private final ShopGuiService gui;
    private final TransactionService transactions;
    private final EconomyBridge economy;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final double maxNpcDistanceSquared;

    public ShopGuiListener(JavaPlugin plugin, ShopRegistry registry, ShopGuiService gui,
                           TransactionService transactions, EconomyBridge economy,
                           int ignoredLegacyBulkAmount, double maxNpcDistance) {
        this.plugin = plugin;
        this.registry = registry;
        this.gui = gui;
        this.transactions = transactions;
        this.economy = economy;
        double normalizedDistance = Math.max(1.0D, Math.min(32.0D, maxNpcDistance));
        this.maxNpcDistanceSquared = normalizedDistance * normalizedDistance;

        int maxAmount = Math.max(1, Math.min(2304,
                plugin.getConfig().getInt("transaction.max-amount", 64)));
        gui.configureMaxAmount(maxAmount);
        if (plugin instanceof CdrVephilimEconomy cve) {
            gui.installBedrockForms(new BedrockShopFormService(
                    cve, gui, transactions, economy, maxNpcDistance));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (top.getHolder() instanceof ShopInventoryHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || event.getClickedInventory() != top) {
                return;
            }
            handleCatalogClick(player, holder, event.getRawSlot());
            return;
        }

        if (top.getHolder() instanceof ShopTransactionHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || event.getClickedInventory() != top) {
                return;
            }
            handleTransactionClick(player, holder, event.getRawSlot(), event.getCurrentItem());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ShopInventoryHolder || top.getHolder() instanceof ShopTransactionHolder) {
            event.setCancelled(true);
        }
    }

    private void handleCatalogClick(Player player, ShopInventoryHolder holder, int rawSlot) {
        Shop shop = validShop(player, holder.shopId());
        if (shop == null) {
            return;
        }
        ShopListing listing = shop.listingBySlot(rawSlot);
        if (listing == null) {
            return;
        }
        gui.openTransactionMenu(player, shop, listing);
    }

    private void handleTransactionClick(Player player, ShopTransactionHolder holder,
                                        int rawSlot, ItemStack clicked) {
        Shop shop = validShop(player, holder.shopId());
        if (shop == null) {
            return;
        }
        ShopListing listing = shop.listings().get(holder.listingId());
        if (listing == null) {
            player.closeInventory();
            return;
        }

        if (rawSlot == 26) {
            gui.open(player, shop);
            return;
        }

        if (rawSlot >= 10 && rawSlot <= 13 && listing.mode().canBuy()) {
            int amount = switch (rawSlot) {
                case 10 -> 1;
                case 11 -> 16;
                case 12 -> 32;
                case 13 -> 64;
                default -> 0;
            };
            if (amount <= 0 || amount > gui.maxAmount()) {
                return;
            }
            execute(player, shop, listing, TransactionType.BUY, amount, clicked);
            return;
        }

        if (rawSlot == 19 && listing.mode().canSell()) {
            int stock = gui.stock(shop, listing);
            int capacity = Math.max(0, listing.maxStock() - stock);
            int held = ShopGuiService.sellableHeldAmount(player, listing);
            int amount = Math.min(Math.min(held, capacity), gui.maxAmount());
            if (amount <= 0) {
                sendConfigured(player, capacity <= 0 ? "messages.max-stock" : "messages.insufficient-items",
                        capacity <= 0
                                ? "<yellow>Stok pedagang sudah penuh.</yellow>"
                                : "<red>Tidak ada item valid di tangan untuk dijual.</red>");
                return;
            }
            execute(player, shop, listing, TransactionType.SELL, amount, clicked);
            return;
        }

        if (rawSlot == 21 && listing.mode().canSell()) {
            int stock = gui.stock(shop, listing);
            int capacity = Math.max(0, listing.maxStock() - stock);
            int owned = InventoryUtil.countPlain(player.getInventory(), listing.material());
            int amount = Math.min(Math.min(owned, capacity), gui.maxAmount());
            if (amount <= 0) {
                sendConfigured(player, capacity <= 0 ? "messages.max-stock" : "messages.insufficient-items",
                        capacity <= 0
                                ? "<yellow>Stok pedagang sudah penuh.</yellow>"
                                : "<red>Kamu tidak punya item yang dapat dijual.</red>");
                return;
            }
            execute(player, shop, listing, TransactionType.SELL, amount, clicked);
        }
    }

    private void execute(Player player, Shop shop, ShopListing listing,
                         TransactionType type, int amount, ItemStack clicked) {
        double displayedPrice = gui.displayedPrice(clicked, type);
        if (!Double.isFinite(displayedPrice)) {
            sendConfigured(player, "messages.price-changed",
                    "<yellow>Harga pasar perlu diperbarui. Silakan pilih lagi.</yellow>");
            refreshTransactionOrClose(player, shop, listing);
            return;
        }

        DynamicPricingService.ChurnDecision churn = gui.checkMarketChurn(player, shop, listing, type);
        if (!churn.allowed()) {
            long seconds = Math.max(1L, (churn.remainingMillis() + 999L) / 1000L);
            String message = plugin.getConfig().getString("messages.market-churn",
                            "<yellow>Tunggu {seconds} detik sebelum membalik arah transaksi pada komoditas ini.</yellow>")
                    .replace("{seconds}", Long.toString(seconds));
            sendRaw(player, message);
            return;
        }

        TransactionResult result = transactions.execute(player, shop, listing, type, amount, displayedPrice);
        sendResult(player, listing, type, result);

        if (result.failure() == TransactionFailure.SAFETY_STOP) {
            player.closeInventory();
            return;
        }

        if (result.success()) {
            gui.recordSuccessfulMarketTransaction(player, shop, listing, type);
        }

        if (result.success() || result.failure() == TransactionFailure.PRICE_CHANGED) {
            refreshTransactionOrClose(player, shop, listing);
        }
    }

    private Shop validShop(Player player, String shopId) {
        Shop shop = registry.findById(shopId).orElse(null);
        if (shop == null || !shop.enabled()) {
            player.closeInventory();
            return null;
        }
        if (!isNearBoundNpc(player, shop)) {
            player.closeInventory();
            sendConfigured(player, "messages.too-far", "<red>Kamu terlalu jauh dari pedagang.</red>");
            return null;
        }
        return shop;
    }

    private void refreshTransactionOrClose(Player player, Shop shop, ShopListing listing) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isNearBoundNpc(player, shop)) {
                gui.openTransactionMenu(player, shop, listing);
            } else if (player.isOnline()) {
                player.closeInventory();
            }
        });
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
                    .replace("{item}", prettyName(listing))
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
            case PRICE_CHANGED -> "messages.price-changed";
            case NOT_ALLOWED -> "messages.not-allowed";
            case SAFETY_STOP -> "messages.safety-stop";
            case INTERNAL_ERROR, NONE -> "messages.internal-error";
        };
    }

    private static String prettyName(ShopListing listing) {
        String[] words = listing.material().name().toLowerCase(Locale.ROOT).split("_");
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
