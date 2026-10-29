package com.flowpay.transfer;

import java.time.Instant;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.idempotency.IdempotencyOperation;
import com.flowpay.idempotency.IdempotencyRequest;
import com.flowpay.idempotency.IdempotencyRequestRepository;
import com.flowpay.idempotency.IdempotencyStatus;
import com.flowpay.transfer.dto.TransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransferIdempotencyService {

    private final IdempotencyRequestRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public TransferIdempotencyService(IdempotencyRequestRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    // Claim and finalize requests in the same transaction as the wallet and ledger changes.
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim begin(UUID userId, String key, String hash) {
        boolean isNew = idempotencyRepository.insertTransferRequestIfAbsent(userId, key, hash, Instant.now()) == 1;
        IdempotencyRequest idempotencyRequest = idempotencyRepository
                .findForUpdate(userId, IdempotencyOperation.TRANSFER, key).orElseThrow();
        if (!idempotencyRequest.getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                    "This Idempotency-Key was already used with a different request.");
        }
        if (!isNew) {
            finalResult(idempotencyRequest);
        }
        return new Claim(idempotencyRequest, isNew);
    }

    @Transactional(readOnly = true)
    public StoredTransferResult findResult(UUID userId, String key) {
        IdempotencyRequest idempotencyRequest = idempotencyRepository
                .findByUserIdAndOperationTypeAndIdempotencyKey(userId, IdempotencyOperation.TRANSFER, key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND",
                        "No committed result was found. This does not prove failure; retry with the same Idempotency-Key."));
        return finalResult(idempotencyRequest);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredTransferResult complete(IdempotencyRequest idempotencyRequest, UUID transactionId, TransferResponse response) {
        idempotencyRequest.complete(transactionId, objectMapper.writeValueAsString(response), Instant.now());
        idempotencyRepository.flush();
        return StoredTransferResult.from(idempotencyRequest);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredTransferResult reject(IdempotencyRequest idempotencyRequest, UUID transactionId, ApiException error) {
        idempotencyRequest.reject(transactionId, (short) error.getStatus().value(),
                objectMapper.writeValueAsString(error.toProblemDetail()), Instant.now());
        idempotencyRepository.flush();
        return StoredTransferResult.from(idempotencyRequest);
    }

    private StoredTransferResult finalResult(IdempotencyRequest idempotencyRequest) {
        if (idempotencyRequest.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "The outcome is not final. Look up the result again or retry with the same Idempotency-Key.");
        }
        return StoredTransferResult.from(idempotencyRequest);
    }

    public record Claim(IdempotencyRequest request, boolean isNew) {
    }
}
