package id.cdr.vephilimeconomy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ShopTransactionHolder implements InventoryHolder {
    private final String shopId;
    private final String listingId;
    private Inventory inventory;

    public ShopTransactionHolder(String shopId, String listingId) {
        this.shopId = shopId;
        this.listingId = listingId;
    }

    public String shopId() {
        return shopId;
    }

    public String listingId() {
        return listingId;
    }

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
