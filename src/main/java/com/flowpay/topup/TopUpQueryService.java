package com.flowpay.topup;

import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.idempotency.IdempotencyOperation;
import com.flowpay.idempotency.IdempotencyRequestRepository;
import com.flowpay.idempotency.IdempotencyStatus;
import com.flowpay.topup.dto.TopUpHistoryResponse;
import com.flowpay.topup.dto.TopUpResponse;
import com.flowpay.transaction.TransactionStatus;
import com.flowpay.transaction.TransactionType;
import com.flowpay.transaction.WalletTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TopUpQueryService {

    private final WalletTransactionRepository transactions;
    private final IdempotencyRequestRepository operations;

    public TopUpQueryService(WalletTransactionRepository transactions, IdempotencyRequestRepository operations) {
        this.transactions = transactions;
        this.operations = operations;
    }

    public TopUpResponse findByReference(UUID userId, String reference) {
        return transactions.findVisibleByReference(userId, reference, TransactionType.TOP_UP, TransactionStatus.SUCCESS)
                .map(TopUpResponse::from).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "TOP_UP_NOT_FOUND", "Top-up does not exist or is not accessible."));
    }

    public TopUpResponse findByKey(UUID userId, String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency-Key.");
        }
        var operation = operations.findByUserIdAndOperationTypeAndIdempotencyKey(userId, IdempotencyOperation.TOP_UP, key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND",
                        "No committed result was found. Retry with the same key; this does not prove failure."));
        if (operation.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "The result is not yet available.");
        }
        return transactions.findById(operation.getTransactionId()).map(TopUpResponse::from).orElseThrow();
    }

    public TopUpHistoryResponse history(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Page must be non-negative and size between 1 and 100.");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = transactions.findVisibleHistory(userId, TransactionType.TOP_UP, TransactionStatus.SUCCESS, pageable);
        return new TopUpHistoryResponse(result.getContent().stream().map(TopUpResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }
}
