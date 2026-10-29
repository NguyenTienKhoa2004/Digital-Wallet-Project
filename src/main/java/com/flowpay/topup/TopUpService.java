package com.flowpay.topup;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.idempotency.IdempotencyOperation;
import com.flowpay.idempotency.IdempotencyRequest;
import com.flowpay.idempotency.IdempotencyRequestRepository;
import com.flowpay.idempotency.IdempotencyStatus;
import com.flowpay.idempotency.TopUpRequestFingerprint;
import com.flowpay.payment.ProviderPayment;
import com.flowpay.payment.ProviderPaymentRepository;
import com.flowpay.topup.dto.TopUpRequest;
import com.flowpay.topup.dto.TopUpResponse;
import com.flowpay.transaction.WalletTransaction;
import com.flowpay.transaction.WalletTransactionRepository;
import com.flowpay.user.UserRepository;
import com.flowpay.wallet.Wallet;
import com.flowpay.wallet.WalletRepository;
import com.flowpay.wallet.WalletStatus;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@Service
public class TopUpService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final ProviderPaymentRepository paymentRepository;
    private final IdempotencyRequestRepository idempotencyRepository;
    private final TopUpRequestFingerprint fingerprint;
    private final ObjectMapper objectMapper;

    public TopUpService(
            UserRepository userRepository,
            WalletRepository walletRepository,
            WalletTransactionRepository transactionRepository,
            ProviderPaymentRepository paymentRepository,
            IdempotencyRequestRepository idempotencyRepository,
            TopUpRequestFingerprint fingerprint,
            ObjectMapper objectMapper) {

        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TopUpResponse create(UUID userId, String key, TopUpRequest request) {

        Objects.requireNonNull(userId, "userId must not be null");
        validate(key, request);

        if (!userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "USER_NOT_FOUND", "User does not exist.");
        }
        // Generate a hash of the request to detect changes in the request body
        String hash = fingerprint.hash(request);

        boolean isNew = idempotencyRepository
                .insertTopUpRequestIfAbsent(
                        userId, key, hash, Instant.now()) == 1;

        IdempotencyRequest operation = idempotencyRepository
                .findForUpdate(userId, IdempotencyOperation.TOP_UP, key)
                .orElseThrow();

        if (!operation.getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "This key was already used with a different request.");
        }

        if (!isNew) {
            return replay(operation);
        }
        // Lock the wallet row for update to prevent concurrent modifications
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
        .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "WALLET_NOT_FOUND",
                "User does not have a wallet."));

   
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "WALLET_NOT_ACTIVE", "Wallet is not active.");
        }

        Instant now = Instant.now();

        WalletTransaction transaction = transactionRepository.saveAndFlush(
                WalletTransaction.pendingTopUp(
                        "TOP-" + UUID.randomUUID(),
                        userId,
                        wallet.getId(),
                        request.amount(),
                        request.description(),
                        now));

        paymentRepository.save(
                ProviderPayment.forMock(transaction.getId(), now));

        TopUpResponse response = TopUpResponse.from(transaction);

        operation.complete(
                transaction.getId(),
                objectMapper.writeValueAsString(response),
                Instant.now());

        return response;
    }

    private TopUpResponse replay(IdempotencyRequest operation) {
        if (operation.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "IDEMPOTENCY_IN_PROGRESS",
                    "Retry with the same Idempotency-Key.");
        }

        if (operation.getStatus() != IdempotencyStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Unexpected status for a stored top-up request.");
        }

        return objectMapper.readValue(
                operation.getResponseBody(), TopUpResponse.class);
    }

    private void validate(String key, TopUpRequest request) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 1 to 255 characters.");
        }

        if (request == null || request.amount() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_AMOUNT", "Amount must be greater than zero.");
        }

        if (request.description() != null
                && request.description().length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Description must not exceed 255 characters.");
        }
    }
}
