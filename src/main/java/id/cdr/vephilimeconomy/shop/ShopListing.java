package id.cdr.vephilimeconomy.shop;

import org.bukkit.Material;

public record ShopListing(
        String id,
        Material material,
        int slot,
        ListingMode mode,
        double buyPrice,
        double sellPrice,
        int initialStock,
        int maxStock
) {
    public ShopListing {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Listing id cannot be blank");
        }
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("Listing material must be a real item");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("Listing slot cannot be negative");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Listing mode cannot be null");
        }
        if (buyPrice < 0 || sellPrice < 0) {
            throw new IllegalArgumentException("Prices cannot be negative");
        }
        if (initialStock < 0 || maxStock < 0 || initialStock > maxStock) {
            throw new IllegalArgumentException("Invalid stock bounds");
        }
    }
}
