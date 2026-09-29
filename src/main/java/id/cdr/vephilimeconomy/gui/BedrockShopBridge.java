package id.cdr.vephilimeconomy.gui;

import id.cdr.vephilimeconomy.shop.Shop;
import org.bukkit.entity.Player;

/** Runtime-safe bridge so the core plugin can load even when Floodgate is absent. */
public interface BedrockShopBridge {
    boolean openShopIfBedrock(Player player, Shop shop);

    void shutdown();
}
