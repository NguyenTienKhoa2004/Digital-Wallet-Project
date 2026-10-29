package com.flowpay.transfer;

import java.util.Objects;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.idempotency.IdempotencyStatus;
import com.flowpay.idempotency.TransferRequestFingerprint;
import com.flowpay.transfer.dto.TransferRequest;
import com.flowpay.transfer.dto.TransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransferService {

    private final TransferExecutionService executionService;
    private final TransferIdempotencyService idempotencyService;
    private final TransferRequestFingerprint fingerprint;
    private final ObjectMapper objectMapper;

    public TransferService(TransferExecutionService executionService, TransferIdempotencyService idempotencyService,
            TransferRequestFingerprint fingerprint, ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.idempotencyService = idempotencyService;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
    }

    public TransferResponse transfer(UUID authenticatedUserId, String key, TransferRequest request) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId must not be null");
        validateKey(key);
        if (request == null || request.destinationWalletId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Destination wallet is required.");
        }
        if (request.amount() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be greater than zero.");
        }
        if (request.description() != null && request.description().length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Description must not exceed 255 characters.");
        }
        StoredTransferResult result = executionService.execute(
                authenticatedUserId, key, fingerprint.hash(request), request);
        // The execution transaction has committed before decoding or raising a cached business rejection.
        return decode(result);
    }

    public TransferResponse findByIdempotencyKey(UUID userId, String key) {
        validateKey(key);
        return decode(idempotencyService.findResult(userId, key));
    }

    private TransferResponse decode(StoredTransferResult result) {
        if (result.status() == IdempotencyStatus.COMPLETED) {
            return objectMapper.readValue(result.responseBody(), TransferResponse.class);
        }
        JsonNode error = objectMapper.readTree(result.responseBody());
        JsonNode reference = error.get("reference");
        throw new ApiException(HttpStatus.valueOf(result.responseStatus()),
                error.get("code").asString(), error.get("detail").asString(),
                reference == null || reference.isNull() ? null : reference.asString());
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 1 to 255 characters.");
        }
    }
}
