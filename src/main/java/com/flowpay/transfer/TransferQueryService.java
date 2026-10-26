package com.flowpay.transfer;

import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.transaction.TransactionStatus;
import com.flowpay.transaction.TransactionType;
import com.flowpay.transaction.WalletTransactionRepository;
import com.flowpay.transfer.dto.TransferHistoryResponse;
import com.flowpay.transfer.dto.TransferResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransferQueryService {

    private final WalletTransactionRepository transactionRepository;

    public TransferQueryService(WalletTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public TransferResponse findByReference(UUID userId, String reference) {
        return transactionRepository.findVisibleByReference(
                userId, reference, TransactionType.TRANSFER, TransactionStatus.SUCCESS)
                .map(TransferResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "TRANSFER_NOT_FOUND", "Transfer does not exist or is not accessible."));
    }

    public TransferHistoryResponse history(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        var pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = transactionRepository.findVisibleHistory(
                userId, TransactionType.TRANSFER, TransactionStatus.SUCCESS, pageable);
        return new TransferHistoryResponse(result.getContent().stream().map(TransferResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }
}
