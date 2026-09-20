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

    public ShopGuiService(StockRepository stocks, EconomyBridge economy) {
        this.stocks = stocks;
        this.economy = economy;
    }

    public void open(Player player, Shop shop) {
        ShopInventoryHolder holder = new ShopInventoryHolder(shop.id());
        Component title = miniMessage.deserialize(shop.displayName());
        Inventory inventory = Bukkit.createInventory(holder, shop.size(), title);
        holder.attach(inventory);

        for (ShopListing listing : shop.listings().values()) {
            inventory.setItem(listing.slot(), render(shop, listing));
        }

        player.openInventory(inventory);
    }

    private ItemStack render(Shop shop, ShopListing listing) {
        ItemStack stack = new ItemStack(listing.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(prettyName(listing.material().name())));

        List<Component> lore = new ArrayList<>();
        int stock = stocks.getStock(shop.id(), listing.id());
        lore.add(miniMessage.deserialize("<gray>Stok: <white>" + stock + "</white>/<white>" + listing.maxStock() + "</white></gray>"));
        lore.add(Component.empty());

        if (listing.mode().canBuy()) {
            lore.add(miniMessage.deserialize("<green>Beli: <gold>" + escapeMini(economy.format(listing.buyPrice())) + "</gold></green>"));
            lore.add(miniMessage.deserialize("<dark_gray>Klik kiri: beli 1 • Shift+kiri: beli banyak</dark_gray>"));
        }
        if (listing.mode().canSell()) {
            lore.add(miniMessage.deserialize("<aqua>Jual: <gold>" + escapeMini(economy.format(listing.sellPrice())) + "</gold></aqua>"));
            lore.add(miniMessage.deserialize("<dark_gray>Klik kanan: jual 1 • Shift+kanan: jual banyak</dark_gray>"));
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
