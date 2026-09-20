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
    private final long cooldownMillis;
    private final Map<String, ReentrantLock> listingLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTransaction = new ConcurrentHashMap<>();

    public TransactionService(EconomyBridge economy, StockRepository stocks, AuditService audit, Logger logger, long cooldownMillis) {
        this.economy = economy;
        this.stocks = stocks;
        this.audit = audit;
        this.logger = logger;
        this.cooldownMillis = Math.max(0L, cooldownMillis);
    }

    public TransactionResult execute(Player player, Shop shop, ShopListing listing, TransactionType type, int amount) {
        UUID transactionId = UUID.randomUUID();
        if (amount <= 0 || amount > 2304) {
            return TransactionResult.failed(transactionId, TransactionFailure.NOT_ALLOWED);
        }
        if ((type == TransactionType.BUY && !listing.mode().canBuy())
                || (type == TransactionType.SELL && !listing.mode().canSell())) {
            return TransactionResult.failed(transactionId, TransactionFailure.NOT_ALLOWED);
        }

        long now = System.currentTimeMillis();
        Long previous = lastTransaction.get(player.getUniqueId());
        if (previous != null && now - previous < cooldownMillis) {
            return TransactionResult.failed(transactionId, TransactionFailure.BUSY);
        }
        lastTransaction.put(player.getUniqueId(), now);

        String lockKey = shop.id() + "|" + listing.id();
        ReentrantLock lock = listingLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return TransactionResult.failed(transactionId, TransactionFailure.BUSY);
        }

        try {
            return type == TransactionType.BUY
                    ? buy(transactionId, player, shop, listing, amount)
                    : sell(transactionId, player, shop, listing, amount);
        } finally {
            lock.unlock();
        }
    }

    private TransactionResult buy(UUID tx, Player player, Shop shop, ShopListing listing, int amount) {
        int stockBefore = stocks.getStock(shop.id(), listing.id());
        if (stockBefore < amount) {
            return TransactionResult.failed(tx, TransactionFailure.INSUFFICIENT_STOCK);
        }
        if (!InventoryUtil.canFit(player.getInventory(), listing.material(), amount)) {
            return TransactionResult.failed(tx, TransactionFailure.INVENTORY_FULL);
        }

        double total = safeTotal(listing.buyPrice(), amount);
        if (!Double.isFinite(total) || total <= 0) {
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore, "invalid calculated total");
        }
        if (!economy.has(player, total)) {
            return TransactionResult.failed(tx, TransactionFailure.INSUFFICIENT_MONEY);
        }

        EconomyBridge.OperationResult withdrawal = economy.withdraw(player, total);
        if (!withdrawal.success()) {
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore, "withdraw failed: " + withdrawal.errorMessage());
        }

        if (!InventoryUtil.addPlain(player.getInventory(), listing.material(), amount)) {
            EconomyBridge.OperationResult refund = economy.deposit(player, total);
            String detail = refund.success() ? "inventory mutation failed; money refunded" : "CRITICAL: inventory mutation failed and money refund failed: " + refund.errorMessage();
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore, detail);
        }

        int stockAfter = stockBefore - amount;
        try {
            stocks.setStock(shop.id(), listing, stockAfter);
        } catch (IOException exception) {
            boolean itemRollback = InventoryUtil.removePlain(player.getInventory(), listing.material(), amount);
            EconomyBridge.OperationResult refund = economy.deposit(player, total);
            String detail = "stock persistence failed: " + exception.getMessage()
                    + "; itemRollback=" + itemRollback
                    + "; moneyRollback=" + refund.success();
            return internalFailure(tx, player, shop, listing, TransactionType.BUY, amount, stockBefore, detail);
        }

        TransactionResult result = new TransactionResult(tx, true, TransactionFailure.NONE, amount, listing.buyPrice(), total, stockBefore, stockAfter);
        record(player, shop, listing, TransactionType.BUY, result, "SUCCESS", "");
        return result;
    }

    private TransactionResult sell(UUID tx, Player player, Shop shop, ShopListing listing, int amount) {
        int stockBefore = stocks.getStock(shop.id(), listing.id());
        if ((long) stockBefore + amount > listing.maxStock()) {
            return TransactionResult.failed(tx, TransactionFailure.MAX_STOCK);
        }
        if (InventoryUtil.countPlain(player.getInventory(), listing.material()) < amount) {
            return TransactionResult.failed(tx, TransactionFailure.INSUFFICIENT_ITEMS);
        }

        double total = safeTotal(listing.sellPrice(), amount);
        if (!Double.isFinite(total) || total <= 0) {
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore, "invalid calculated total");
        }

        if (!InventoryUtil.removePlain(player.getInventory(), listing.material(), amount)) {
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore, "item removal failed after pre-validation");
        }

        EconomyBridge.OperationResult deposit = economy.deposit(player, total);
        if (!deposit.success()) {
            boolean restored = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore,
                    "deposit failed: " + deposit.errorMessage() + "; itemRollback=" + restored);
        }

        int stockAfter = stockBefore + amount;
        try {
            stocks.setStock(shop.id(), listing, stockAfter);
        } catch (IOException exception) {
            EconomyBridge.OperationResult moneyRollback = economy.withdraw(player, total);
            boolean itemRollback = InventoryUtil.addPlain(player.getInventory(), listing.material(), amount);
            String detail = "stock persistence failed: " + exception.getMessage()
                    + "; moneyRollback=" + moneyRollback.success()
                    + "; itemRollback=" + itemRollback;
            return internalFailure(tx, player, shop, listing, TransactionType.SELL, amount, stockBefore, detail);
        }

        TransactionResult result = new TransactionResult(tx, true, TransactionFailure.NONE, amount, listing.sellPrice(), total, stockBefore, stockAfter);
        record(player, shop, listing, TransactionType.SELL, result, "SUCCESS", "");
        return result;
    }

    private TransactionResult internalFailure(UUID tx, Player player, Shop shop, ShopListing listing, TransactionType type,
                                              int amount, int stockBefore, String detail) {
        logger.severe("Economy transaction " + tx + " failed: " + detail);
        TransactionResult result = new TransactionResult(tx, false, TransactionFailure.INTERNAL_ERROR, amount,
                type == TransactionType.BUY ? listing.buyPrice() : listing.sellPrice(),
                0.0D, stockBefore, stockBefore);
        record(player, shop, listing, type, result, "FAILED", detail);
        return result;
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
