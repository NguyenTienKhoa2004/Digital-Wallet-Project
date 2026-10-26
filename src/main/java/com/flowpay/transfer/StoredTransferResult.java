package com.flowpay.transfer;

import com.flowpay.idempotency.IdempotencyRequest;
import com.flowpay.idempotency.IdempotencyStatus;

public record StoredTransferResult(IdempotencyStatus status, short responseStatus, String responseBody) {

    public static StoredTransferResult from(IdempotencyRequest request) {
        return new StoredTransferResult(request.getStatus(), request.getResponseStatus(), request.getResponseBody());
    }
}
