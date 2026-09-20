package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.storage.StockRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShopGuiService {
    private final StockRepository stocks;
    private final EconomyBridge economy;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final int bulkAmount;

    public ShopGuiService(StockRepository stocks, EconomyBridge economy, int bulkAmount) {
        this.stocks = stocks;
        this.economy = economy;
        this.bulkAmount = Math.max(1, bulkAmount);
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

    private ItemStack render(Player player, Shop shop, ShopListing listing) {
        ItemStack stack = new ItemStack(listing.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(miniMessage.deserialize("<white><bold>" + escapeMini(prettyName(listing.material().name())) + "</bold></white>"));

        List<Component> lore = new ArrayList<>();
        int stock = stocks.getStock(shop.id(), listing.id());

        lore.add(miniMessage.deserialize("<gray>Stok pedagang: <white>" + stock + "</white>/<white>"
                + listing.maxStock() + "</white></gray>"));
        lore.add(miniMessage.deserialize("<gray>Saldo kamu: <gold>"
                + escapeMini(economy.format(economy.balance(player))) + "</gold></gray>"));
        lore.add(Component.empty());

        if (listing.mode().canBuy()) {
            lore.add(miniMessage.deserialize("<green>Harga beli: <gold>"
                    + escapeMini(economy.format(listing.buyPrice())) + "</gold></green>"));
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
            lore.add(miniMessage.deserialize("<aqua>Harga jual: <gold>"
                    + escapeMini(economy.format(listing.sellPrice())) + "</gold></aqua>"));
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
