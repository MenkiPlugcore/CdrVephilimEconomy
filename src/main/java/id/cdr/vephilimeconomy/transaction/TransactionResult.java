package id.cdr.vephilimeconomy.transaction;

import java.util.UUID;

public record TransactionResult(
        UUID transactionId,
        boolean success,
        TransactionFailure failure,
        int amount,
        double unitPrice,
        double total,
        int stockBefore,
        int stockAfter
) {
    public static TransactionResult failed(UUID id, TransactionFailure failure) {
        return new TransactionResult(id, false, failure, 0, 0.0D, 0.0D, -1, -1);
    }
}
