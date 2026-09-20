package id.cdr.vephilimeconomy.transaction;

public enum TransactionFailure {
    NONE,
    NOT_ALLOWED,
    INSUFFICIENT_MONEY,
    INSUFFICIENT_STOCK,
    INSUFFICIENT_ITEMS,
    INVENTORY_FULL,
    MAX_STOCK,
    BUSY,
    INTERNAL_ERROR
}
