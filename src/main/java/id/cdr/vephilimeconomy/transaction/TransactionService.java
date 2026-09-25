package id.cdr.vephilimeconomy.transaction;

import id.cdr.vephilimeconomy.audit.AuditEntry;
import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.discount.PlayerDiscountService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.pricing.DynamicPricingService;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.util.InventoryUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class TransactionService {
    private static final double PRICE_EPSILON = 0.000001D;

    private final EconomyBridge economy;
    private final StockRepository stocks;
    private final AuditService audit;
    private final Logger logger;
    private final RuntimeSafetyState safetyState;
    private final PendingTransactionJournal journal;
    private final DynamicPricingService pricing;
    private final Runnable safetyStopCallback;
    private final long cooldownMillis;
    private final int maxAmount;
    private final boolean auditRejected;
    private final boolean auditBusyRejected;
    private final Map<String, ReentrantLock> listingLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransaction = new ConcurrentHashMap<>();
    private final Set<UUID> inFlightPlayers = ConcurrentHashMap.newKeySet();

    public TransactionService(EconomyBridge economy, StockRepository stocks, AuditService audit, Logger logger,
                              RuntimeSafetyState safetyState, PendingTransactionJournal journal,
                              DynamicPricingService pricing, Runnable safetyStopCallback,
                              long cooldownMillis, int maxAmount,
                              boolean auditRejected, boolean auditBusyRejected) {
        this.economy = economy;
        this.stocks = stocks;
        this.audit = audit;
        this.logger = logger;
        this.safetyState = safetyState;
        this.journal = journal;
        this.pricing = pricing;
        this.safetyStopCallback = safetyStopCallback == null ? () -> { } : safetyStopCallback;
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        this.maxAmount = Math.max(1, Math.min(2304, maxAmount));
        this.auditRejected = auditRejected;
        this.auditBusyRejected = auditBusyRejected;
    }

    public TransactionResult execute(Player player, Shop shop, ShopListing listing, TransactionType type, int amount) {
        return execute(player, shop, listing, type, amount, Double.NaN);
    }

    /**
     * Executes against an optional price the player actually saw in the GUI.
     * When market pricing or a personal BUY discount changes after the GUI was
     * rendered, the transaction is rejected before any money/item mutation.
     */
    public TransactionResult execute(Player player, Shop shop, ShopListing listing, TransactionType type,
                                     int amount, double expectedUnitPrice) {
        UUID transactionId = UUID.randomUUID();

        if (safetyState.isStopped()) {
            return safetyStopped(transactionId, player, shop, listing, type, amount);
        }
        if (!Bukkit.isPrimaryThread()) {
            logger.severe("Blocked asynchronous economy transaction attempt: tx=" + transactionId
                    + ", player=" + player.getUniqueId() + ", shop=" + shop.id() + ", listing=" + listing.id());
            return reject(transactionId, player, shop, listing, type, amount,
                    TransactionFailure.NOT_ALLOWED, "asynchronous transaction attempt blocked");
        }
        if (amount <= 0 || amount > maxAmount) {
            return reject(transactionId, player, shop, listing, type, amount,
                    TransactionFailure.NOT_ALLOWED, "amount outside allowed range 1-" + maxAmount);
        }
        if ((type == TransactionType.BUY && !listing.mode().canBuy())
                || (type == TransactionType.SELL && !listing.mode().canSell())) {
            return reject(transactionId, player, shop, listing, type, amount,
                    TransactionFailure.NOT_ALLOWED, "listing mode does not allow " + type);
        }

        UUID playerId = player.getUniqueId();
        if (!inFlightPlayers.add(playerId)) {
            return reject(transactionId, player, shop, listing, type, amount,
                    TransactionFailure.BUSY, "player already has an in-flight transaction");
        }

        try {
            long now = System.currentTimeMillis();
            Long previous = lastTransaction.get(playerId);
            if (previous != null && now - previous < cooldownMillis) {
                return reject(transactionId, player, shop, listing, type, amount,
                        TransactionFailure.BUSY, "player transaction cooldown");
            }
            lastTransaction.put(playerId, now);

            String lockKey = shop.id() + "|" + listing.id();
            ReentrantLock lock = listingLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
            if (!lock.tryLock()) {
                return reject(transactionId, player, shop, listing, type, amount,
                        TransactionFailure.BUSY, "listing transaction lock busy");
            }

            try {
                if (safetyState.isStopped()) {
                    return safetyStopped(transactionId, player, shop, listing, type, amount);
                }
                return type == TransactionType.BUY
                        ? buy(transactionId, player, shop, listing, amount, expectedUnitPrice)
                        : sell(transactionId, player, shop, listing, amount, expectedUnitPrice);
            } finally {
                lock.unlock();
            }
        } finally {
            inFlightPlayers.remove(playerId);
        }
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            lastTransaction.remove(playerId);
            inFlightPlayers.remove(playerId);
        }
    }

    public void clearState() {
        lastTransaction.clear();
        inFlightPlayers.clear();
        listingLocks.clear();
    }

    public int trackedPlayerCount() {
        return lastTransaction.size();
    }

    public int inFlightCount() {
        return inFlightPlayers.size();
    }

    private TransactionResult buy(UUID tx, Player player, Shop shop, ShopListing listing,
                                  int amount, double expectedUnitPrice) {
        int stockBefore = stocks.getStock(shop.id(), listing.id());
        double unitPrice = quote(player, shop, listing, stockBefore, TransactionType.BUY);
        if (priceChanged(expectedUnitPrice, unitPrice)) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.PRICE_CHANGED,
                    "displayed=" + expectedUnitPrice + "; current=" + unitPrice,
                    stockBefore, unitPrice);
        }
        if (stockBefore < amount) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.INSUFFICIENT_STOCK,
                    "requested=" + amount + "; available=" + stockBefore,
                    stockBefore, unitPrice);
        }
        if (!InventoryUtil.canFit(player.getInventory(), listing.material(), amount)) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.INVENTORY_FULL, "insufficient inventory capacity",
                    stockBefore, unitPrice);
        }

        double total = safeTotal(unitPrice, amount);
        if (!Double.isFinite(total) || total <= 0) {
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, "invalid calculated total");
        }
        if (!economy.has(player, total)) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.INSUFFICIENT_MONEY, "insufficient player balance",
                    stockBefore, unitPrice);
        }

        PendingTransactionJournal.JournalEntry pending;
        try {
            pending = journal.begin(tx, player, shop, listing, TransactionType.BUY,
                    amount, unitPrice, total, stockBefore);
        } catch (IOException exception) {
            String detail = "transaction journal prepare failed before mutation: " + exception.getMessage();
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        EconomyBridge.OperationResult withdrawal = economy.withdraw(player, total);
        if (!withdrawal.success()) {
            String detail = "withdraw failed: " + withdrawal.errorMessage();
            if (!cleanupJournal(pending, tx, "withdraw failure cleanup")) {
                detail += "; journalCleanup=false";
            }
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        try {
            pending = journal.advance(pending, PendingTransactionJournal.Stage.MONEY_WITHDRAWN);
        } catch (IOException exception) {
            EconomyBridge.OperationResult refund = economy.deposit(player, total);
            String detail = "journal stage MONEY_WITHDRAWN failed: " + exception.getMessage()
                    + "; moneyRollback=" + refund.success();
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        if (!InventoryUtil.addPlain(player.getInventory(), listing.material(), amount)) {
            EconomyBridge.OperationResult refund = economy.deposit(player, total);
            if (!refund.success()) {
                String detail = "CRITICAL: inventory mutation failed and money refund failed: " + refund.errorMessage();
                tripSafety(tx, detail);
                return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                        amount, stockBefore, unitPrice, detail);
            }
            String detail = "inventory mutation failed; money refunded";
            if (!cleanupJournal(pending, tx, "inventory failure rollback cleanup")) {
                detail += "; journalCleanup=false";
            }
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        try {
            pending = journal.advance(pending, PendingTransactionJournal.Stage.ITEM_ADDED);
        } catch (IOException exception) {
            boolean itemRollback = InventoryUtil.removePlain(player.getInventory(), listing.material(), amount);
            boolean moneyRollback = false;
            String rollbackDetail;
            if (itemRollback) {
                EconomyBridge.OperationResult refund = economy.deposit(player, total);
                moneyRollback = refund.success();
                rollbackDetail = "itemRollback=true; moneyRollback=" + moneyRollback;
            } else {
                rollbackDetail = "itemRollback=false; moneyRollback=SKIPPED_TO_AVOID_FREE_ITEM";
            }
            String detail = "journal stage ITEM_ADDED failed: " + exception.getMessage() + "; " + rollbackDetail;
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        int stockAfter = stockBefore - amount;
        try {
            stocks.setStock(shop.id(), listing, stockAfter);
        } catch (IOException exception) {
            boolean itemRollback = InventoryUtil.removePlain(player.getInventory(), listing.material(), amount);
            boolean moneyRollback = false;
            String rollbackDetail;

            if (itemRollback) {
                EconomyBridge.OperationResult refund = economy.deposit(player, total);
                moneyRollback = refund.success();
                rollbackDetail = moneyRollback
                        ? "itemRollback=true; moneyRollback=true"
                        : "itemRollback=true; moneyRollback=false; refundError=" + refund.errorMessage();
            } else {
                rollbackDetail = "itemRollback=false; moneyRollback=SKIPPED_TO_AVOID_FREE_ITEM";
            }

            String detail = "stock persistence failed: " + exception.getMessage() + "; " + rollbackDetail;
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        try {
            pending = journal.advance(pending, PendingTransactionJournal.Stage.STOCK_PERSISTED);
            journal.complete(pending);
        } catch (IOException exception) {
            String detail = "transaction committed but journal finalization failed: " + exception.getMessage();
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.BUY,
                    amount, stockBefore, unitPrice, detail);
        }

        TransactionResult result = new TransactionResult(tx, true, TransactionFailure.NONE, amount,
                unitPrice, total, stockBefore, stockAfter);
        String discountDetail = PlayerDiscountService.currentPercent(player.getUniqueId(), shop.id()) > 0.0D
                ? "personalDiscount=" + PlayerDiscountService.currentPercent(player.getUniqueId(), shop.id()) + "%"
                : "";
        String marketDetail = pricingDetail(shop, listing, unitPrice);
        String auditDetail = marketDetail.isBlank() ? discountDetail
                : discountDetail.isBlank() ? marketDetail : marketDetail + "; " + discountDetail;
        record(player, shop, listing, TransactionType.BUY, result, "SUCCESS", auditDetail);
        return result;
    }

    private TransactionResult sell(UUID tx, Player player, Shop shop, ShopListing listing,
                                   int amount, double expectedUnitPrice) {
        int stockBefore = stocks.getStock(shop.id(), listing.id());
        double unitPrice = quote(player, shop, listing, stockBefore, TransactionType.SELL);
        if (priceChanged(expectedUnitPrice, unitPrice)) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.SELL, amount,
                    TransactionFailure.PRICE_CHANGED,
                    "displayed=" + expectedUnitPrice + "; current=" + unitPrice,
                    stockBefore, unitPrice);
        }
        if ((long) stockBefore + amount > listing.maxStock()) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.SELL, amount,
                    TransactionFailure.MAX_STOCK,
                    "current=" + stockBefore + "; requested=" + amount + "; max=" + listing.maxStock(),
                    stockBefore, unitPrice);
        }
        int owned = InventoryUtil.countPlain(player.getInventory(), listing.material());
        if (owned < amount) {
            return rejectAtPrice(tx, player, shop, listing, TransactionType.SELL, amount,
                    TransactionFailure.INSUFFICIENT_ITEMS,
                    "requested=" + amount + "; owned=" + owned,
                    stockBefore, unitPrice);
        }

        double total = safeTotal(unitPrice, amount);
        if (!Double.isFinite(total) || total <= 0) {
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, "invalid calculated total");
        }

        PendingTransactionJournal.JournalEntry pending;
        try {
            pending = journal.begin(tx, player, shop, listing, TransactionType.SELL,
                    amount, unitPrice, total, stockBefore);
        } catch (IOException exception) {
            String detail = "transaction journal prepare failed before mutation: " + exception.getMessage();
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        if (!InventoryUtil.removePlain(player.getInventory(), listing.material(), amount)) {
            String detail = "item removal failed after pre-validation";
            if (!cleanupJournal(pending, tx, "item removal failure cleanup")) {
                detail += "; journalCleanup=false";
            }
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        try {
            pending = journal.advance(pending, PendingTransactionJournal.Stage.ITEM_REMOVED);
        } catch (IOException exception) {
            boolean restored = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
            String detail = "journal stage ITEM_REMOVED failed: " + exception.getMessage()
                    + "; itemRollback=" + restored;
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        EconomyBridge.OperationResult deposit = economy.deposit(player, total);
        if (!deposit.success()) {
            boolean restored = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
            String detail = "deposit failed: " + deposit.errorMessage() + "; itemRollback=" + restored;
            if (!restored) {
                tripSafety(tx, detail);
            } else if (!cleanupJournal(pending, tx, "deposit failure rollback cleanup")) {
                detail += "; journalCleanup=false";
            }
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        try {
            pending = journal.advance(pending, PendingTransactionJournal.Stage.MONEY_DEPOSITED);
        } catch (IOException exception) {
            EconomyBridge.OperationResult moneyRollbackResult = economy.withdraw(player, total);
            boolean moneyRollback = moneyRollbackResult.success();
            boolean itemRollback = false;
            if (moneyRollback) {
                itemRollback = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
            }
            String detail = "journal stage MONEY_DEPOSITED failed: " + exception.getMessage()
                    + "; moneyRollback=" + moneyRollback
                    + "; itemRollback=" + (moneyRollback ? itemRollback : "SKIPPED_TO_AVOID_MONEY_AND_ITEM_DUPLICATION");
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        int stockAfter = stockBefore + amount;
        try {
            stocks.setStock(shop.id(), listing, stockAfter);
        } catch (IOException exception) {
            EconomyBridge.OperationResult moneyRollbackResult = economy.withdraw(player, total);
            boolean moneyRollback = moneyRollbackResult.success();
            boolean itemRollback = false;
            String rollbackDetail;

            if (moneyRollback) {
                itemRollback = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
                rollbackDetail = "moneyRollback=true; itemRollback=" + itemRollback;
            } else {
                rollbackDetail = "moneyRollback=false; itemRollback=SKIPPED_TO_AVOID_MONEY_AND_ITEM_DUPLICATION"
                        + "; withdrawError=" + moneyRollbackResult.errorMessage();
            }

            String detail = "stock persistence failed: " + exception.getMessage() + "; " + rollbackDetail;
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        try {
            pending = journal.advance(pending, PendingTransactionJournal.Stage.STOCK_PERSISTED);
            journal.complete(pending);
        } catch (IOException exception) {
            String detail = "transaction committed but journal finalization failed: " + exception.getMessage();
            tripSafety(tx, detail);
            return internalFailure(tx, player, shop, listing, TransactionType.SELL,
                    amount, stockBefore, unitPrice, detail);
        }

        TransactionResult result = new TransactionResult(tx, true, TransactionFailure.NONE, amount,
                unitPrice, total, stockBefore, stockAfter);
        record(player, shop, listing, TransactionType.SELL, result, "SUCCESS", pricingDetail(shop, listing, unitPrice));
        return result;
    }

    private boolean cleanupJournal(PendingTransactionJournal.JournalEntry entry, UUID tx, String context) {
        try {
            journal.complete(entry);
            return true;
        } catch (IOException exception) {
            tripSafety(tx, context + " failed: " + exception.getMessage());
            return false;
        }
    }

    private TransactionResult safetyStopped(UUID tx, Player player, Shop shop, ShopListing listing,
                                            TransactionType type, int amount) {
        int stock = stocks.getStock(shop.id(), listing.id());
        double unitPrice = quote(player, shop, listing, stock, type);
        double total = safeTotal(unitPrice, amount);
        if (!Double.isFinite(total) || amount <= 0 || total < 0) {
            total = 0.0D;
        }
        return new TransactionResult(tx, false, TransactionFailure.SAFETY_STOP, amount,
                unitPrice, total, stock, stock);
    }

    private TransactionResult reject(UUID tx, Player player, Shop shop, ShopListing listing, TransactionType type,
                                     int amount, TransactionFailure failure, String detail) {
        int stock = stocks.getStock(shop.id(), listing.id());
        double unitPrice = quote(player, shop, listing, stock, type);
        return rejectAtPrice(tx, player, shop, listing, type, amount, failure, detail, stock, unitPrice);
    }

    private TransactionResult rejectAtPrice(UUID tx, Player player, Shop shop, ShopListing listing,
                                            TransactionType type, int amount, TransactionFailure failure,
                                            String detail, int stock, double unitPrice) {
        double total = safeTotal(unitPrice, amount);
        if (!Double.isFinite(total) || amount <= 0 || total < 0) {
            total = 0.0D;
        }

        TransactionResult result = new TransactionResult(tx, false, failure, amount, unitPrice, total, stock, stock);
        if (auditRejected && (failure != TransactionFailure.BUSY || auditBusyRejected)) {
            record(player, shop, listing, type, result, "REJECTED", detail);
        }
        return result;
    }

    private TransactionResult internalFailure(UUID tx, Player player, Shop shop, ShopListing listing,
                                              TransactionType type, int amount, int stockBefore,
                                              double unitPrice, String detail) {
        logger.severe("Economy transaction " + tx + " failed: " + detail);
        double total = safeTotal(unitPrice, amount);
        if (!Double.isFinite(total) || total < 0) {
            total = 0.0D;
        }
        TransactionResult result = new TransactionResult(tx, false, TransactionFailure.INTERNAL_ERROR, amount,
                unitPrice, total, stockBefore, stockBefore);
        record(player, shop, listing, type, result, "FAILED", detail);
        return result;
    }

    private double quote(Player player, Shop shop, ShopListing listing, int stock, TransactionType type) {
        double marketPrice;
        if (pricing == null) {
            marketPrice = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
        } else {
            marketPrice = pricing.quote(shop, listing, stock, type).effectivePrice();
        }
        if (type == TransactionType.BUY && player != null) {
            return PlayerDiscountService.applyCurrentBuyDiscount(player.getUniqueId(), shop.id(), marketPrice);
        }
        return marketPrice;
    }

    private String pricingDetail(Shop shop, ShopListing listing, double unitPrice) {
        if (pricing == null || !pricing.isDynamic(shop.id(), listing.id())) {
            return "";
        }
        return "dynamicPrice=true; unitPrice=" + unitPrice;
    }

    private static boolean priceChanged(double expected, double current) {
        return Double.isFinite(expected) && Math.abs(expected - current) > PRICE_EPSILON;
    }

    private void tripSafety(UUID tx, String detail) {
        if (safetyState.trip(tx, detail)) {
            logger.severe("ECONOMY SAFETY STOP ACTIVATED. Semua transaksi baru diblokir sampai admin melakukan recovery eksplisit. "
                    + "tx=" + tx + "; persisted=" + safetyState.persistenceHealthy() + "; " + detail);
            try {
                safetyStopCallback.run();
            } catch (RuntimeException exception) {
                logger.severe("Safety-stop callback gagal: " + exception.getMessage());
            }
        }
    }

    private void record(Player player, Shop shop, ShopListing listing, TransactionType type,
                        TransactionResult result, String status, String detail) {
        audit.record(new AuditEntry(
                Instant.now(),
                result.transactionId(),
                player.getUniqueId(),
                player.getName(),
                shop.id(),
                listing.id(),
                type,
                result.amount(),
                result.unitPrice(),
                result.total(),
                result.stockBefore(),
                result.stockAfter(),
                status,
                detail
        ));
    }

    private static double safeTotal(double unitPrice, int amount) {
        return unitPrice * amount;
    }
}
