package id.cdr.vephilimeconomy.gui;

public final class ShopTransactionHolder extends ShopInventoryHolder {
    private final String listingId;

    public ShopTransactionHolder(String shopId, String listingId) {
        super(shopId);
        this.listingId = listingId;
    }

    public String listingId() {
        return listingId;
    }
}
