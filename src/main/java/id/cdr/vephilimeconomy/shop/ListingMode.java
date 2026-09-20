package id.cdr.vephilimeconomy.shop;

public enum ListingMode {
    BUY,
    SELL,
    BUY_SELL;

    public boolean canBuy() {
        return this == BUY || this == BUY_SELL;
    }

    public boolean canSell() {
        return this == SELL || this == BUY_SELL;
    }
}
