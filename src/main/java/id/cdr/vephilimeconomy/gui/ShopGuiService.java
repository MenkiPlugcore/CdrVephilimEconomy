package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.discount.PlayerDiscountService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.integration.WantedPriceBridge;
import id.cdr.vephilimeconomy.pricing.DynamicPricingService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import id.cdr.vephilimeconomy.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShopGuiService {
    private static final int[] BUY_AMOUNTS = {1, 16, 32, 64};

    private final StockRepository stocks;
    private final EconomyBridge economy;
    private final DynamicPricingService pricing;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile int maxAmount;
    private volatile BedrockShopBridge bedrockForms;
    private final NamespacedKey buyQuoteKey;
    private final NamespacedKey sellQuoteKey;

    public ShopGuiService(JavaPlugin plugin, StockRepository stocks, EconomyBridge economy,
                          DynamicPricingService pricing, int initialAmountLimit) {
        this.stocks = stocks;
        this.economy = economy;
        this.pricing = pricing;
        this.maxAmount = Math.max(1, Math.min(2304, initialAmountLimit));
        this.buyQuoteKey = new NamespacedKey(plugin, "buy_quote");
        this.sellQuoteKey = new NamespacedKey(plugin, "sell_quote");
    }

    public void configureMaxAmount(int amount) {
        this.maxAmount = Math.max(1, Math.min(2304, amount));
    }

    public void installBedrockForms(BedrockShopBridge service) {
        this.bedrockForms = service;
    }

    public void openCrossplay(Player player, Shop shop) {
        BedrockShopBridge forms = bedrockForms;
        if (forms != null && forms.openShopIfBedrock(player, shop)) return;
        open(player, shop);
    }

    public void open(Player player, Shop shop) {
        ShopInventoryHolder holder = new ShopInventoryHolder(shop.id());
        Component title = miniMessage.deserialize(shop.displayName());
        Inventory inventory = Bukkit.createInventory(holder, shop.size(), title);
        holder.attach(inventory);
        for (ShopListing listing : shop.listings().values()) inventory.setItem(listing.slot(), renderListing(player, shop, listing));
        player.openInventory(inventory);
    }

    public void openTransactionMenu(Player player, Shop shop, ShopListing listing) {
        ShopTransactionHolder holder = new ShopTransactionHolder(shop.id(), listing.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Transaksi: " + prettyName(listing.material().name())));
        holder.attach(inventory);

        int stock = stock(shop, listing);
        double buyPrice = quoteUnitPrice(player, shop, listing, TransactionType.BUY);
        double sellPrice = quoteUnitPrice(player, shop, listing, TransactionType.SELL);
        double wantedMultiplier = WantedPriceBridge.multiplier(player.getUniqueId());

        ItemStack preview = new ItemStack(listing.material());
        ItemMeta previewMeta = preview.getItemMeta();
        previewMeta.displayName(Component.text(prettyName(listing.material().name())));
        List<Component> previewLore = new ArrayList<>();
        previewLore.add(Component.text("Stok pedagang: " + stock + "/" + listing.maxStock()));
        if (listing.mode().canBuy()) previewLore.add(Component.text("Harga beli / item: " + economy.format(buyPrice)));
        if (listing.mode().canBuy() && wantedMultiplier > 1.0D) {
            previewLore.add(Component.text("Status buronan: +" + Math.round((wantedMultiplier - 1.0D) * 100.0D) + "% harga beli"));
        }
        if (listing.mode().canSell()) {
            previewLore.add(Component.text("Harga jual / item: " + economy.format(sellPrice)));
            previewLore.add(Component.text("Kamu punya: " + InventoryUtil.countPlain(player.getInventory(), listing.material())));
        }
        previewMeta.lore(previewLore);
        preview.setItemMeta(previewMeta);
        inventory.setItem(4, preview);

        if (listing.mode().canBuy()) {
            int[] slots = {10, 11, 12, 13};
            for (int i = 0; i < BUY_AMOUNTS.length; i++) {
                int amount = BUY_AMOUNTS[i];
                if (amount > maxAmount) continue;
                inventory.setItem(slots[i], actionButton(Material.LIME_STAINED_GLASS_PANE, "Beli " + amount,
                        TransactionType.BUY, buyPrice, "Total: " + economy.format(buyPrice * amount),
                        "Klik untuk membeli " + amount));
            }
        }

        if (listing.mode().canSell()) {
            int held = sellableHeldAmount(player, listing);
            int owned = InventoryUtil.countPlain(player.getInventory(), listing.material());
            int capacity = Math.max(0, listing.maxStock() - stock);
            int heldAccepted = Math.min(Math.min(held, capacity), maxAmount);
            int allAccepted = Math.min(Math.min(owned, capacity), maxAmount);
            inventory.setItem(19, actionButton(Material.CHEST, "Jual yang dipegang", TransactionType.SELL, sellPrice,
                    "Di tangan: " + held, "Dapat dijual sekarang: " + heldAccepted,
                    heldAccepted > 0 ? "Total: " + economy.format(sellPrice * heldAccepted) : "Tidak ada item valid di tangan"));
            inventory.setItem(21, actionButton(Material.BARREL, "Jual semua", TransactionType.SELL, sellPrice,
                    "Di inventory: " + owned, "Kapasitas NPC: " + capacity, "Dapat dijual sekarang: " + allAccepted,
                    allAccepted > 0 ? "Total: " + economy.format(sellPrice * allAccepted) : "Tidak ada item yang dapat dijual"));
        }

        inventory.setItem(26, simpleButton(Material.ARROW, "Kembali", "Kembali ke daftar produk"));
        player.openInventory(inventory);
    }

    public double displayedPrice(ItemStack stack, TransactionType type) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return Double.NaN;
        NamespacedKey key = type == TransactionType.BUY ? buyQuoteKey : sellQuoteKey;
        Double value = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return value == null ? Double.NaN : value;
    }

    public double quoteUnitPrice(Player player, Shop shop, ShopListing listing, TransactionType type) {
        int stock = stock(shop, listing);
        DynamicPricingService.PriceQuote quote = pricing == null ? null : pricing.quote(shop, listing, stock, type);
        double base = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
        double market = quote == null ? base : quote.effectivePrice();
        return type == TransactionType.BUY
                ? PlayerDiscountService.applyCurrentBuyDiscount(player.getUniqueId(), shop.id(), market)
                : market;
    }

    public int stock(Shop shop, ShopListing listing) { return stocks.getStock(shop.id(), listing.id()); }
    public int maxAmount() { return maxAmount; }
    public String formatMoney(double amount) { return economy.format(amount); }

    public DynamicPricingService.ChurnDecision checkMarketChurn(Player player, Shop shop, ShopListing listing, TransactionType type) {
        if (pricing == null) return new DynamicPricingService.ChurnDecision(true, 0L, "OK");
        return pricing.checkChurn(player.getUniqueId(), shop, listing, type);
    }

    public void recordSuccessfulMarketTransaction(Player player, Shop shop, ShopListing listing, TransactionType type) {
        if (pricing != null) pricing.recordSuccessfulTransaction(player.getUniqueId(), shop, listing, type);
    }

    public static int sellableHeldAmount(Player player, ShopListing listing) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return 0;
        return hand.isSimilar(new ItemStack(listing.material())) ? hand.getAmount() : 0;
    }

    private ItemStack renderListing(Player player, Shop shop, ShopListing listing) {
        ItemStack stack = new ItemStack(listing.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(miniMessage.deserialize("<white><bold>" + escapeMini(prettyName(listing.material().name())) + "</bold></white>"));
        List<Component> lore = new ArrayList<>();
        int stock = stock(shop, listing);
        DynamicPricingService.PriceQuote buyQuote = pricing == null ? null : pricing.quote(shop, listing, stock, TransactionType.BUY);
        DynamicPricingService.PriceQuote sellQuote = pricing == null ? null : pricing.quote(shop, listing, stock, TransactionType.SELL);
        double marketBuyPrice = buyQuote == null ? listing.buyPrice() : buyQuote.effectivePrice();
        double personalDiscount = PlayerDiscountService.currentPercent(player.getUniqueId(), shop.id());
        double buyPrice = PlayerDiscountService.applyCurrentBuyDiscount(player.getUniqueId(), shop.id(), marketBuyPrice);
        double sellPrice = sellQuote == null ? listing.sellPrice() : sellQuote.effectivePrice();
        double wantedMultiplier = WantedPriceBridge.multiplier(player.getUniqueId());

        meta.getPersistentDataContainer().set(buyQuoteKey, PersistentDataType.DOUBLE, buyPrice);
        meta.getPersistentDataContainer().set(sellQuoteKey, PersistentDataType.DOUBLE, sellPrice);
        lore.add(miniMessage.deserialize("<gray>Stok pedagang: <white>" + stock + "</white>/<white>" + listing.maxStock() + "</white></gray>"));
        lore.add(miniMessage.deserialize("<gray>Saldo kamu: <gold>" + escapeMini(economy.format(economy.balance(player))) + "</gold></gray>"));

        DynamicPricingService.PriceQuote marketQuote = listing.mode().canBuy() ? buyQuote : sellQuote;
        if (marketQuote != null && marketQuote.dynamic()) {
            long percent = Math.round((marketQuote.multiplier() - 1.0D) * 100.0D);
            lore.add(miniMessage.deserialize("<gray>Pasar dinamis: <yellow>" + (percent > 0 ? "+" : "") + percent
                    + "%</yellow> <dark_gray>(sampel stok " + Math.round(marketQuote.stockRatio() * 100.0D) + "%)</dark_gray></gray>"));
        }
        if (listing.mode().canBuy() && personalDiscount > 0.0D) {
            lore.add(miniMessage.deserialize("<gray>Diskon pribadi: <green>-" + formatPercent(personalDiscount) + "%</green></gray>"));
        }
        if (listing.mode().canBuy() && wantedMultiplier > 1.0D) {
            long surcharge = Math.round((wantedMultiplier - 1.0D) * 100.0D);
            lore.add(miniMessage.deserialize("<gray>Status buronan: <red>+" + surcharge + "%</red> harga beli</gray>"));
            lore.add(miniMessage.deserialize("<dark_gray>Total bounty aktif: "
                    + escapeMini(economy.format(WantedPriceBridge.activeBountyTotal(player.getUniqueId()).doubleValue())) + "</dark_gray>"));
        }
        lore.add(Component.empty());

        if (listing.mode().canBuy()) {
            lore.add(miniMessage.deserialize("<green>Harga beli kamu: <gold>" + escapeMini(economy.format(buyPrice)) + "</gold></green>"));
            if (stock <= 0) lore.add(miniMessage.deserialize("<red><bold>STOK HABIS</bold></red>"));
        }
        if (listing.mode().canSell()) {
            if (listing.mode().canBuy()) lore.add(Component.empty());
            String label = sellQuote != null && sellQuote.dynamic() ? "Harga jual dinamis" : "Harga jual";
            lore.add(miniMessage.deserialize("<aqua>" + label + ": <gold>" + escapeMini(economy.format(sellPrice)) + "</gold></aqua>"));
            if (stock >= listing.maxStock()) lore.add(miniMessage.deserialize("<yellow><bold>STOK PEDAGANG PENUH</bold></yellow>"));
        }
        lore.add(Component.empty());
        lore.add(miniMessage.deserialize("<yellow>Klik / tap untuk membuka menu transaksi</yellow>"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack actionButton(Material material, String name, TransactionType type, double unitPrice, String... loreLines) {
        ItemStack stack = simpleButton(material, name, loreLines);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(type == TransactionType.BUY ? buyQuoteKey : sellQuoteKey, PersistentDataType.DOUBLE, unitPrice);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack simpleButton(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) lore.add(Component.text(line));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public static String prettyName(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private static String formatPercent(double value) {
        if (Math.rint(value) == value) return Long.toString(Math.round(value));
        return Double.toString(value);
    }

    private static String escapeMini(String value) { return value.replace("<", "\\<").replace(">", "\\>"); }
}
