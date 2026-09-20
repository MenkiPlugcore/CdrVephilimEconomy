package id.cdr.vephilimeconomy.shop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Shop {
    private final String id;
    private final String displayName;
    private final int npcId;
    private final int size;
    private final boolean enabled;
    private final String manager;
    private final Map<String, ShopListing> listings;
    private final Map<Integer, ShopListing> listingsBySlot;

    public Shop(String id, String displayName, int npcId, int size, boolean enabled, String manager,
                Map<String, ShopListing> listings) {
        this.id = id;
        this.displayName = displayName;
        this.npcId = npcId;
        this.size = size;
        this.enabled = enabled;
        this.manager = manager == null ? "" : manager;
        this.listings = Collections.unmodifiableMap(new LinkedHashMap<>(listings));

        Map<Integer, ShopListing> bySlot = new LinkedHashMap<>();
        for (ShopListing listing : listings.values()) {
            if (listing.slot() >= size) {
                throw new IllegalArgumentException("Listing '" + listing.id() + "' slot exceeds shop inventory size");
            }
            if (bySlot.put(listing.slot(), listing) != null) {
                throw new IllegalArgumentException("Duplicate listing slot " + listing.slot() + " in shop " + id);
            }
        }
        this.listingsBySlot = Collections.unmodifiableMap(bySlot);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int npcId() {
        return npcId;
    }

    public int size() {
        return size;
    }

    public boolean enabled() {
        return enabled;
    }

    public String manager() {
        return manager;
    }

    public Map<String, ShopListing> listings() {
        return listings;
    }

    public ShopListing listingBySlot(int slot) {
        return listingsBySlot.get(slot);
    }
}
