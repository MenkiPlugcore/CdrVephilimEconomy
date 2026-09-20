package id.cdr.vephilimeconomy.audit;

import id.cdr.vephilimeconomy.transaction.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record AuditEntry(
        Instant timestamp,
        UUID transactionId,
        UUID playerId,
        String playerName,
        String shopId,
        String listingId,
        TransactionType type,
        int amount,
        double unitPrice,
        double total,
        int stockBefore,
        int stockAfter,
        String status,
        String detail
) {
}
