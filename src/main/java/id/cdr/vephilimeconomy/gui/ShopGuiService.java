package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.pricing.DynamicPricingService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.transaction.TransactionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
    private final StockRepository stocks;
    private final EconomyBridge economy;
    private final DynamicPricingService pricing;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final int bulkAmount;
    private final NamespacedKey buyQuoteKey;
    private final NamespacedKey sellQuoteKey;

    public ShopGuiService(JavaPlugin plugin, StockRepository stocks, EconomyBridge economy,
                          DynamicPricingService pricing, int bulkAmount) {
        this.stocks = stocks;
        this.economy = economy;
        this.pricing = pricing;
        this.bulkAmount = Math.max(1, bulkAmount);
        this.buyQuoteKey = new NamespacedKey(plugin, "buy_quote");
        this.sellQuoteKey = new NamespacedKey(plugin, "sell_quote");
    }

    public void open(Player player, Shop shop) {
        ShopInventoryHolder holder = new ShopInventoryHolder(shop.id());
        Component title = miniMessage.deserialize(shop.displayName());
        Inventory inventory = Bukkit.createInventory(holder, shop.size(), title);
        holder.attach(inventory);

        for (ShopListing listing : shop.listings().values()) {
            inventory.setItem(listing.slot(), render(player, shop, listing));
        }

        player.openInventory(inventory);
    }

    public double displayedPrice(ItemStack stack, TransactionType type) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return Double.NaN;
        }
        NamespacedKey key = type == TransactionType.BUY ? buyQuoteKey : sellQuoteKey;
        Double value = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return value == null ? Double.NaN : value;
    }

    public DynamicPricingService.ChurnDecision checkMarketChurn(Player player, Shop shop,
                                                                 ShopListing listing, TransactionType type) {
        if (pricing == null) {
            return new DynamicPricingService.ChurnDecision(true, 0L, "OK");
        }
        return pricing.checkChurn(player.getUniqueId(), shop, listing, type);
    }

    public void recordSuccessfulMarketTransaction(Player player, Shop shop,
                                                  ShopListing listing, TransactionType type) {
        if (pricing != null) {
            pricing.recordSuccessfulTransaction(player.getUniqueId(), shop, listing, type);
        }
    }

    private ItemStack render(Player player, Shop shop, ShopListing listing) {
        ItemStack stack = new ItemStack(listing.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(miniMessage.deserialize("<white><bold>" + escapeMini(prettyName(listing.material().name())) + "</bold></white>"));

        List<Component> lore = new ArrayList<>();
        int stock = stocks.getStock(shop.id(), listing.id());
        DynamicPricingService.PriceQuote buyQuote = pricing == null
                ? null
                : pricing.quote(shop, listing, stock, TransactionType.BUY);
        DynamicPricingService.PriceQuote sellQuote = pricing == null
                ? null
                : pricing.quote(shop, listing, stock, TransactionType.SELL);
        double buyPrice = buyQuote == null ? listing.buyPrice() : buyQuote.effectivePrice();
        double sellPrice = sellQuote == null ? listing.sellPrice() : sellQuote.effectivePrice();

        meta.getPersistentDataContainer().set(buyQuoteKey, PersistentDataType.DOUBLE, buyPrice);
        meta.getPersistentDataContainer().set(sellQuoteKey, PersistentDataType.DOUBLE, sellPrice);

        lore.add(miniMessage.deserialize("<gray>Stok pedagang: <white>" + stock + "</white>/<white>"
                + listing.maxStock() + "</white></gray>"));
        lore.add(miniMessage.deserialize("<gray>Saldo kamu: <gold>"
                + escapeMini(economy.format(economy.balance(player))) + "</gold></gray>"));

        DynamicPricingService.PriceQuote marketQuote = listing.mode().canBuy() ? buyQuote : sellQuote;
        if (marketQuote != null && marketQuote.dynamic()) {
            long percent = Math.round((marketQuote.multiplier() - 1.0D) * 100.0D);
            String sign = percent > 0 ? "+" : "";
            lore.add(miniMessage.deserialize("<gray>Pasar dinamis: <yellow>" + sign + percent
                    + "%</yellow> <dark_gray>(sampel stok " + Math.round(marketQuote.stockRatio() * 100.0D) + "%)</dark_gray></gray>"));
        }
        lore.add(Component.empty());

        if (listing.mode().canBuy()) {
            String label = buyQuote != null && buyQuote.dynamic() ? "Harga beli dinamis" : "Harga beli";
            lore.add(miniMessage.deserialize("<green>" + label + ": <gold>"
                    + escapeMini(economy.format(buyPrice)) + "</gold></green>"));
            if (stock <= 0) {
                lore.add(miniMessage.deserialize("<red><bold>STOK HABIS</bold></red>"));
            } else {
                lore.add(miniMessage.deserialize("<dark_gray>Kiri: beli 1 • Shift+kiri: beli "
                        + bulkAmount + "</dark_gray>"));
            }
        }

        if (listing.mode().canSell()) {
            if (listing.mode().canBuy()) {
                lore.add(Component.empty());
            }
            String label = sellQuote != null && sellQuote.dynamic() ? "Harga jual dinamis" : "Harga jual";
            lore.add(miniMessage.deserialize("<aqua>" + label + ": <gold>"
                    + escapeMini(economy.format(sellPrice)) + "</gold></aqua>"));
            if (stock >= listing.maxStock()) {
                lore.add(miniMessage.deserialize("<yellow><bold>STOK PEDAGANG PENUH</bold></yellow>"));
            } else {
                lore.add(miniMessage.deserialize("<dark_gray>Kanan: jual 1 • Shift+kanan: jual "
                        + bulkAmount + "</dark_gray>"));
            }
        }

        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String prettyName(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
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
