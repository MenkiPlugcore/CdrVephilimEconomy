package id.cdr.vephilimeconomy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class ShopInventoryHolder implements InventoryHolder {
    private final String shopId;
    private Inventory inventory;

    public ShopInventoryHolder(String shopId) {
        this.shopId = shopId;
    }

    public String shopId() {
        return shopId;
    }

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Shop inventory has not been attached yet");
        }
        return inventory;
    }
}
