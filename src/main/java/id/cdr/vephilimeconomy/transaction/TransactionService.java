package id.cdr.vephilimeconomy.transaction;

import id.cdr.vephilimeconomy.audit.AuditEntry;
import id.cdr.vephilimeconomy.audit.AuditService;
import id.cdr.vephilimeconomy.economy.EconomyBridge;
import id.cdr.vephilimeconomy.shop.Shop;
import id.cdr.vephilimeconomy.shop.ShopListing;
import id.cdr.vephilimeconomy.storage.StockRepository;
import id.cdr.vephilimeconomy.util.InventoryUtil;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class TransactionService {
    private final EconomyBridge economy;
    private final StockRepository stocks;
    private final AuditService audit;
    private final Logger logger;
    private final RuntimeSafetyState safetyState;
    private final Runnable safetyStopCallback;
    private final long cooldownMillis;
    private final int maxAmount;
    private final boolean auditRejected;
    private final boolean auditBusyRejected;
    private final Map<String, ReentrantLock> listingLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransaction = new ConcurrentHashMap<>();

    public TransactionService(EconomyBridge economy, StockRepository stocks, AuditService audit, Logger logger,
                              RuntimeSafetyState safetyState, Runnable safetyStopCallback,
                              long cooldownMillis, int maxAmount,
                              boolean auditRejected, boolean auditBusyRejected) {
        this.economy = economy;
        this.stocks = stocks;
        this.audit = audit;
        this.logger = logger;
        this.safetyState = safetyState;
        this.safetyStopCallback = safetyStopCallback == null ? () -> { } : safetyStopCallback;
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        this.maxAmount = Math.max(1, Math.min(2304, maxAmount));
        this.auditRejected = auditRejected;
        this.auditBusyRejected = auditBusyRejected;
    }

    public TransactionResult execute(Player player, Shop shop, ShopListing listing, TransactionType type, int amount) {
        UUID transactionId = UUID.randomUUID();

        if (safetyState.isStopped()) {
            return safetyStopped(transactionId, shop, listing, type, amount);
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

        long now = System.currentTimeMillis();
        Long previous = lastTransaction.get(player.getUniqueId());
        if (previous != null && now - previous < cooldownMillis) {
            return reject(transactionId, player, shop, listing, type, amount,
                    TransactionFailure.BUSY, "player transaction cooldown");
        }
        lastTransaction.put(player.getUniqueId(), now);

        String lockKey = shop.id() + "|" + listing.id();
        ReentrantLock lock = listingLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return reject(transactionId, player, shop, listing, type, amount,
                    TransactionFailure.BUSY, "listing transaction lock busy");
        }

        try {
            if (safetyState.isStopped()) {
                return safetyStopped(transactionId, shop, listing, type, amount);
            }
            return type == TransactionType.BUY
                    ? buy(transactionId, player, shop, listing, amount)
                    : sell(transactionId, player, shop, listing, amount);
        } finally {
            lock.unlock();
        }
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId != null) {
            lastTransaction.remove(playerId);
        }
    }

    public void clearState() {
        lastTransaction.clear();
        listingLocks.clear();
    }

    public int trackedPlayerCount() {
        return lastTransaction.size();
    }

    private TransactionResult buy(UUID tx, Player player, Shop shop, ShopListing listing, int amount) {
        int stockBefore = stocks.getStock(shop.id(), listing.id());
        if (stockBefore < amount) {
            return reject(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.INSUFFICIENT_STOCK,
                    "requested=" + amount + "; available=" + stockBefore);
        }
        if (!InventoryUtil.canFit(player.getInventory(), listing.material(), amount)) {
            return reject(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.INVENTORY_FULL, "insufficient inventory capacity");
        }

        double total = safeTotal(listing.buyPrice(), amount);
        if (!Double.isFinite(total) || total <= 0) {
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore,
                    "invalid calculated total");
        }
        if (!economy.has(player, total)) {
            return reject(tx, player, shop, listing, TransactionType.BUY, amount,
                    TransactionFailure.INSUFFICIENT_MONEY, "insufficient player balance");
        }

        EconomyBridge.OperationResult withdrawal = economy.withdraw(player, total);
        if (!withdrawal.success()) {
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore,
                    "withdraw failed: " + withdrawal.errorMessage());
        }

        if (!InventoryUtil.addPlain(player.getInventory(), listing.material(), amount)) {
            EconomyBridge.OperationResult refund = economy.deposit(player, total);
            if (!refund.success()) {
                String detail = "CRITICAL: inventory mutation failed and money refund failed: " + refund.errorMessage();
                tripSafety(tx, detail);
                return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore, detail);
            }
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore,
                    "inventory mutation failed; money refunded");
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
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore, detail);
        }

        TransactionResult result = new TransactionResult(tx, true, TransactionFailure.NONE, amount,
                listing.buyPrice(), total, stockBefore, stockAfter);
        record(player, shop, listing, TransactionType.BUY, result, "SUCCESS", "");
        return result;
    }

    private TransactionResult sell(UUID tx, Player player, Shop shop, ShopListing listing, int amount) {
        int stockBefore = stocks.getStock(shop.id(), listing.id());
        if ((long) stockBefore + amount > listing.maxStock()) {
            return reject(tx, player, shop, listing, TransactionType.SELL, amount,
                    TransactionFailure.MAX_STOCK,
                    "current=" + stockBefore + "; requested=" + amount + "; max=" + listing.maxStock());
        }
        int owned = InventoryUtil.countPlain(player.getInventory(), listing.material());
        if (owned < amount) {
            return reject(tx, player, shop, listing, TransactionType.SELL, amount,
                    TransactionFailure.INSUFFICIENT_ITEMS,
                    "requested=" + amount + "; owned=" + owned);
        }

        double total = safeTotal(listing.sellPrice(), amount);
        if (!Double.isFinite(total) || total <= 0) {
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore,
                    "invalid calculated total");
        }

        if (!InventoryUtil.removePlain(player.getInventory(), listing.material(), amount)) {
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore,
                    "item removal failed after pre-validation");
        }

        EconomyBridge.OperationResult deposit = economy.deposit(player, total);
        if (!deposit.success()) {
            boolean restored = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
            String detail = "deposit failed: " + deposit.errorMessage() + "; itemRollback=" + restored;
            if (!restored) {
                tripSafety(tx, detail);
            }
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore, detail);
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
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore, detail);
        }

        TransactionResult result = new TransactionResult(tx, true, TransactionFailure.NONE, amount,
                listing.sellPrice(), total, stockBefore, stockAfter);
        record(player, shop, listing, TransactionType.SELL, result, "SUCCESS", "");
        return result;
    }

    private TransactionResult safetyStopped(UUID tx, Shop shop, ShopListing listing, TransactionType type, int amount) {
        int stock = stocks.getStock(shop.id(), listing.id());
        double unitPrice = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
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
        double unitPrice = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
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

    private TransactionResult internalFailure(UUID tx, Player player, Shop shop, ShopListing listing, TransactionType type,
                                              int amount, int stockBefore, String detail) {
        logger.severe("Economy transaction " + tx + " failed: " + detail);
        double unitPrice = type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice();
        double total = safeTotal(unitPrice, amount);
        if (!Double.isFinite(total) || total < 0) {
            total = 0.0D;
        }
        TransactionResult result = new TransactionResult(tx, false, TransactionFailure.INTERNAL_ERROR, amount,
                unitPrice, total, stockBefore, stockBefore);
        record(player, shop, listing, type, result, "FAILED", detail);
        return result;
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
