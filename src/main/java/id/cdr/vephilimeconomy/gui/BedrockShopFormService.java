package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.CdrVephilimEconomy;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.pricing.DynamicPricingService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.transaction.TransactionFailure;
import id.cdr.vephilimeconomy.transaction.TransactionResult;
import id.cdr.vephilimeconomy.transaction.TransactionService;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import id.cdr.vephilimeconomy.util.InventoryUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Locale;

/**
 * Bedrock-native shop UI powered by Floodgate/Cumulus forms.
 *
 * <p>The dependency is optional at runtime. Java players always keep the Bukkit
 * inventory UI. Bedrock players get touch-friendly forms with explicit buttons,
 * so no left/right/shift click semantics are required.</p>
 */
public final class BedrockShopFormService {
    private static final int[] BUY_AMOUNTS = {1, 16, 32, 64};

    private final CdrVephilimEconomy plugin;
    private final ShopGuiService gui;
    private final TransactionService transactions;
    private final EconomyBridge economy;
    private final double maxNpcDistanceSquared;
    private volatile boolean active = true;

    public BedrockShopFormService(CdrVephilimEconomy plugin,
                                  ShopGuiService gui,
                                  TransactionService transactions,
                                  EconomyBridge economy,
                                  double maxNpcDistance) {
        this.plugin = plugin;
        this.gui = gui;
        this.transactions = transactions;
        this.economy = economy;
        double normalized = Math.max(1.0D, Math.min(32.0D, maxNpcDistance));
        this.maxNpcDistanceSquared = normalized * normalized;
    }

    public boolean openShopIfBedrock(Player player, Shop shop) {
        if (!isBedrock(player)) {
            return false;
        }
        openCatalog(player, shop.id());
        return true;
    }

    public boolean isBedrock(Player player) {
        if (player == null || !plugin.getServer().getPluginManager().isPluginEnabled("floodgate")) {
            return false;
        }
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("Floodgate API unavailable while checking Bedrock player: " + exception.getMessage());
            return false;
        }
    }

    public void shutdown() {
        active = false;
    }

    private void openCatalog(Player player, String shopId) {
        if (!active || !player.isOnline()) {
            return;
        }
        Shop shop = currentShop(player, shopId);
        if (shop == null) {
            return;
        }

        SimpleForm.Builder form = SimpleForm.builder()
                .title(stripFormatting(shop.displayName()))
                .content("Saldo: " + economy.format(economy.balance(player))
                        + "\nPilih produk untuk membuka menu transaksi.");

        for (ShopListing listing : shop.listings().values()) {
            int stock = gui.stock(shop, listing);
            StringBuilder text = new StringBuilder(ShopGuiService.prettyName(listing.material().name()));
            text.append("\nStok: ").append(stock).append('/').append(listing.maxStock());
            if (listing.mode().canBuy()) {
                text.append(" | Beli ").append(gui.formatMoney(gui.quoteUnitPrice(player, shop, listing, TransactionType.BUY)));
            }
            if (listing.mode().canSell()) {
                text.append(" | Jual ").append(gui.formatMoney(gui.quoteUnitPrice(player, shop, listing, TransactionType.SELL)));
            }

            form.button(text.toString(), FormImage.Type.PATH, materialIcon(listing),
                    response -> runMain(() -> openTransaction(player, shop.id(), listing.id())));
        }

        form.button("Tutup", FormImage.Type.PATH, "textures/ui/cancel");
        sendFormOrFallback(player, shop, form);
    }

    private void openTransaction(Player player, String shopId, String listingId) {
        if (!active || !player.isOnline()) {
            return;
        }
        Shop shop = currentShop(player, shopId);
        if (shop == null) {
            return;
        }
        ShopListing listing = shop.listings().get(listingId);
        if (listing == null) {
            player.sendMessage("§c[CVE Shop] Produk sudah tidak tersedia. Buka NPC lagi.");
            return;
        }

        int stock = gui.stock(shop, listing);
        double buyPrice = gui.quoteUnitPrice(player, shop, listing, TransactionType.BUY);
        double sellPrice = gui.quoteUnitPrice(player, shop, listing, TransactionType.SELL);
        int owned = InventoryUtil.countPlain(player.getInventory(), listing.material());
        int capacity = Math.max(0, listing.maxStock() - stock);

        StringBuilder content = new StringBuilder();
        content.append("Stok pedagang: ").append(stock).append('/').append(listing.maxStock());
        content.append("\nSaldo kamu: ").append(economy.format(economy.balance(player)));
        if (listing.mode().canBuy()) {
            content.append("\nHarga beli / item: ").append(gui.formatMoney(buyPrice));
        }
        if (listing.mode().canSell()) {
            content.append("\nHarga jual / item: ").append(gui.formatMoney(sellPrice));
            content.append("\nItem kamu: ").append(owned);
            content.append(" | Kapasitas NPC: ").append(capacity);
        }

        SimpleForm.Builder form = SimpleForm.builder()
                .title(ShopGuiService.prettyName(listing.material().name()))
                .content(content.toString());

        if (listing.mode().canBuy()) {
            for (int amount : BUY_AMOUNTS) {
                if (amount > gui.maxAmount()) {
                    continue;
                }
                double expected = buyPrice;
                form.button("Beli " + amount + "\n" + gui.formatMoney(expected * amount),
                        FormImage.Type.PATH, materialIcon(listing),
                        response -> runMain(() -> execute(player, shopId, listingId,
                                TransactionType.BUY, amount, expected)));
            }
        }

        if (listing.mode().canSell()) {
            int held = ShopGuiService.sellableHeldAmount(player, listing);
            int heldAccepted = Math.min(Math.min(held, capacity), gui.maxAmount());
            int allAccepted = Math.min(Math.min(owned, capacity), gui.maxAmount());
            double expected = sellPrice;

            form.button("Jual yang dipegang\n" + heldAccepted + " item • "
                            + gui.formatMoney(expected * heldAccepted),
                    FormImage.Type.PATH, "textures/ui/up_arrow",
                    response -> runMain(() -> executeSellHeld(player, shopId, listingId, expected)));

            form.button("Jual semua\n" + allAccepted + " item • "
                            + gui.formatMoney(expected * allAccepted),
                    FormImage.Type.PATH, "textures/ui/confirm",
                    response -> runMain(() -> executeSellAll(player, shopId, listingId, expected)));
        }

        form.button("Kembali ke produk", FormImage.Type.PATH, "textures/ui/cancel",
                response -> runMain(() -> openCatalog(player, shopId)));
        sendFormOrFallback(player, shop, form);
    }

    private void executeSellHeld(Player player, String shopId, String listingId, double expectedPrice) {
        Shop shop = currentShop(player, shopId);
        if (shop == null) return;
        ShopListing listing = shop.listings().get(listingId);
        if (listing == null || !listing.mode().canSell()) return;

        int capacity = Math.max(0, listing.maxStock() - gui.stock(shop, listing));
        int held = ShopGuiService.sellableHeldAmount(player, listing);
        int amount = Math.min(Math.min(held, capacity), gui.maxAmount());
        if (amount <= 0) {
            player.sendMessage(capacity <= 0
                    ? "§e[CVE Shop] Stok pedagang sudah penuh."
                    : "§c[CVE Shop] Tidak ada item valid di tangan untuk dijual.");
            openTransaction(player, shopId, listingId);
            return;
        }
        execute(player, shopId, listingId, TransactionType.SELL, amount, expectedPrice);
    }

    private void executeSellAll(Player player, String shopId, String listingId, double expectedPrice) {
        Shop shop = currentShop(player, shopId);
        if (shop == null) return;
        ShopListing listing = shop.listings().get(listingId);
        if (listing == null || !listing.mode().canSell()) return;

        int capacity = Math.max(0, listing.maxStock() - gui.stock(shop, listing));
        int owned = InventoryUtil.countPlain(player.getInventory(), listing.material());
        int amount = Math.min(Math.min(owned, capacity), gui.maxAmount());
        if (amount <= 0) {
            player.sendMessage(capacity <= 0
                    ? "§e[CVE Shop] Stok pedagang sudah penuh."
                    : "§c[CVE Shop] Kamu tidak punya item yang dapat dijual.");
            openTransaction(player, shopId, listingId);
            return;
        }
        execute(player, shopId, listingId, TransactionType.SELL, amount, expectedPrice);
    }

    private void execute(Player player, String shopId, String listingId,
                         TransactionType type, int amount, double expectedPrice) {
        if (!active || !player.isOnline()) {
            return;
        }
        Shop shop = currentShop(player, shopId);
        if (shop == null) {
            return;
        }
        ShopListing listing = shop.listings().get(listingId);
        if (listing == null) {
            return;
        }

        DynamicPricingService.ChurnDecision churn = gui.checkMarketChurn(player, shop, listing, type);
        if (!churn.allowed()) {
            long seconds = Math.max(1L, (churn.remainingMillis() + 999L) / 1000L);
            player.sendMessage("§e[CVE Shop] Tunggu " + seconds
                    + " detik sebelum membalik arah transaksi pada komoditas ini.");
            openTransaction(player, shopId, listingId);
            return;
        }

        TransactionResult result = transactions.execute(player, shop, listing, type, amount, expectedPrice);
        sendResult(player, listing, type, result);
        if (result.failure() == TransactionFailure.SAFETY_STOP) {
            return;
        }
        if (result.success()) {
            gui.recordSuccessfulMarketTransaction(player, shop, listing, type);
        }
        if (result.success() || result.failure() == TransactionFailure.PRICE_CHANGED) {
            Bukkit.getScheduler().runTask(plugin, () -> openTransaction(player, shopId, listingId));
        }
    }

    private Shop currentShop(Player player, String shopId) {
        Shop shop = plugin.findRuntimeShop(shopId).orElse(null);
        if (shop == null || !shop.enabled()) {
            player.sendMessage("§c[CVE Shop] Shop sudah tidak aktif.");
            return null;
        }
        if (!isNearBoundNpc(player, shop)) {
            player.sendMessage("§c[CVE Shop] Kamu terlalu jauh dari pedagang.");
            return null;
        }
        return shop;
    }

    private boolean isNearBoundNpc(Player player, Shop shop) {
        if (shop.npcId() < 0) return false;
        NPC npc = CitizensAPI.getNPCRegistry().getById(shop.npcId());
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) return false;
        Location npcLocation = npc.getEntity().getLocation();
        Location playerLocation = player.getLocation();
        if (npcLocation.getWorld() == null || playerLocation.getWorld() == null
                || !npcLocation.getWorld().equals(playerLocation.getWorld())) return false;
        return npcLocation.distanceSquared(playerLocation) <= maxNpcDistanceSquared;
    }

    private void sendFormOrFallback(Player player, Shop shop, SimpleForm.Builder form) {
        try {
            if (!FloodgateApi.getInstance().sendForm(player.getUniqueId(), form)) {
                plugin.getLogger().warning("Bedrock form gagal dikirim ke " + player.getName()
                        + "; fallback ke inventory GUI.");
                gui.open(player, shop);
            }
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("Bedrock form error untuk " + player.getName() + ": " + exception.getMessage());
            gui.open(player, shop);
        }
    }

    private void runMain(Runnable action) {
        if (!active) return;
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void sendResult(Player player, ShopListing listing, TransactionType type, TransactionResult result) {
        if (result.success()) {
            String verb = type == TransactionType.BUY ? "membeli" : "menjual";
            player.sendMessage("§a[CVE Shop] Berhasil " + verb + " " + result.amount() + "x "
                    + ShopGuiService.prettyName(listing.material().name()) + " senilai "
                    + economy.format(result.total()) + ".");
            return;
        }

        String message = switch (result.failure()) {
            case INSUFFICIENT_MONEY -> "Saldo tidak cukup.";
            case INSUFFICIENT_STOCK -> "Stok pedagang tidak cukup.";
            case INSUFFICIENT_ITEMS -> "Item kamu tidak cukup.";
            case INVENTORY_FULL -> "Inventory kamu penuh.";
            case MAX_STOCK -> "Stok pedagang sudah penuh.";
            case BUSY -> "Transaksi terlalu cepat. Coba lagi sebentar.";
            case PRICE_CHANGED -> "Harga berubah. Menu diperbarui.";
            case NOT_ALLOWED -> "Transaksi ini tidak diizinkan.";
            case SAFETY_STOP -> "Sistem ekonomi sedang dikunci sementara.";
            case INTERNAL_ERROR, NONE -> "Transaksi gagal karena error internal.";
        };
        player.sendMessage("§c[CVE Shop] " + message);
    }

    private static String materialIcon(ShopListing listing) {
        String name = listing.material().name().toLowerCase(Locale.ROOT);
        return listing.material().isBlock() ? "textures/blocks/" + name : "textures/items/" + name;
    }

    private static String stripFormatting(String value) {
        if (value == null) return "Shop";
        return value.replaceAll("<[^>]+>", "").replace('§', '&');
    }
}
