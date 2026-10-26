package com.flowpay.transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.idempotency.IdempotencyOperation;
import com.flowpay.idempotency.IdempotencyRequest;
import com.flowpay.idempotency.IdempotencyRequestRepository;
import com.flowpay.idempotency.IdempotencyStatus;
import com.flowpay.ledger.EntryType;
import com.flowpay.ledger.LedgerAccount;
import com.flowpay.ledger.LedgerAccountRepository;
import com.flowpay.ledger.LedgerEntry;
import com.flowpay.ledger.LedgerEntryRepository;
import com.flowpay.transaction.WalletTransaction;
import com.flowpay.transaction.WalletTransactionRepository;
import com.flowpay.transfer.dto.TransferRequest;
import com.flowpay.transfer.dto.TransferResponse;
import com.flowpay.user.UserRepository;
import com.flowpay.wallet.Wallet;
import com.flowpay.wallet.WalletRepository;
import com.flowpay.wallet.WalletStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransferExecutionService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyRequestRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public TransferExecutionService(UserRepository userRepository, WalletRepository walletRepository,
            WalletTransactionRepository transactionRepository, LedgerAccountRepository ledgerAccountRepository,
            LedgerEntryRepository ledgerEntryRepository, IdempotencyRequestRepository idempotencyRepository,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    // Own the transaction so cached business rejections commit before the facade raises an API error.
    // READ_COMMITTED lets a duplicate INSERT wait, then read the first request's committed result.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public StoredTransferResult execute(UUID userId, String key, String hash, TransferRequest transferRequest) {
        if (!userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist.");
        }
        boolean isNewRequest = idempotencyRepository
                .insertTransferRequestIfAbsent(userId, key, hash, Instant.now()) == 1;
        IdempotencyRequest operation = idempotencyRepository
                .findForUpdate(userId, IdempotencyOperation.TRANSFER, key).orElseThrow();
        if (!operation.getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                    "This Idempotency-Key was already used with a different request.");
        }
        if (!isNewRequest) {
            return finalResult(operation);
        }

        PreparedTransfer prepared;
        try {
            prepared = prepare(userId, transferRequest);
        } catch (ApiException rejection) {
            return reject(operation, null, rejection);
        }

        Instant now = Instant.now();
        WalletTransaction transaction = transactionRepository.saveAndFlush(WalletTransaction.pendingTransfer(
                "TRF-" + UUID.randomUUID(), userId, prepared.source().getId(), prepared.destination().getId(),
                transferRequest.amount(), transferRequest.description(), now));
        if (prepared.source().getBalance() < transferRequest.amount()) {
            return fail(operation, transaction, "INSUFFICIENT_BALANCE", "Source wallet has insufficient balance.");
        }
        if (prepared.destination().getBalance() > Long.MAX_VALUE - transferRequest.amount()) {
            return fail(operation, transaction, "BALANCE_LIMIT_EXCEEDED", "Destination wallet balance limit exceeded.");
        }

        prepared.source().debit(transferRequest.amount(), now);
        prepared.destination().credit(transferRequest.amount(), now);
        ledgerEntryRepository.saveAllAndFlush(List.of(
                LedgerEntry.create(transaction.getId(), prepared.sourceAccount().getId(),
                        EntryType.DEBIT, transferRequest.amount(), now),
                LedgerEntry.create(transaction.getId(), prepared.destinationAccount().getId(),
                        EntryType.CREDIT, transferRequest.amount(), now)));
        transaction.markSuccess(Instant.now());
        operation.complete(transaction.getId(), objectMapper.writeValueAsString(TransferResponse.from(transaction)), Instant.now());
        idempotencyRepository.flush();
        return StoredTransferResult.from(operation);
    }

    @Transactional(readOnly = true)
    public StoredTransferResult findResult(UUID userId, String key) {
        IdempotencyRequest operation = idempotencyRepository
                .findByUserIdAndOperationTypeAndIdempotencyKey(userId, IdempotencyOperation.TRANSFER, key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND",
                        "No committed result was found. This does not prove failure; retry with the same Idempotency-Key."));
        return finalResult(operation);
    }

    private StoredTransferResult finalResult(IdempotencyRequest operation) {
        if (operation.getStatus() == IdempotencyStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "The outcome is not final. Look up the result again or retry with the same Idempotency-Key.");
        }
        return StoredTransferResult.from(operation);
    }

    private PreparedTransfer prepare(UUID userId, TransferRequest input) {
        UUID sourceId = walletRepository.findWalletIdByUserId(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "SOURCE_WALLET_NOT_FOUND", "User does not have a wallet."));
        UUID destinationId = input.destinationWalletId();
        if (sourceId.equals(destinationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED",
                    "Source and destination must be different.");
        }
        UUID firstId = sourceId.compareTo(destinationId) < 0 ? sourceId : destinationId;
        UUID secondId = firstId.equals(sourceId) ? destinationId : sourceId;
        Wallet first = lockWallet(firstId);
        Wallet second = lockWallet(secondId);
        Wallet source = firstId.equals(sourceId) ? first : second;
        Wallet destination = firstId.equals(destinationId) ? first : second;
        if (!source.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WALLET_ACCESS_DENIED", "User does not own the source wallet.");
        }
        if (source.getStatus() != WalletStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "SOURCE_WALLET_NOT_ACTIVE", "Source wallet is not active.");
        }
        if (destination.getStatus() != WalletStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "DESTINATION_WALLET_NOT_ACTIVE", "Destination wallet is not active.");
        }
        if (!"USD".equals(source.getCurrency()) || !source.getCurrency().equals(destination.getCurrency())) {
            throw new ApiException(HttpStatus.CONFLICT, "CURRENCY_MISMATCH", "Wallets must use the supported USD currency.");
        }
        return new PreparedTransfer(source, destination, ledgerAccount(sourceId), ledgerAccount(destinationId));
    }

    private Wallet lockWallet(UUID walletId) {
        return walletRepository.findByIdForUpdate(walletId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet does not exist."));
    }

    private LedgerAccount ledgerAccount(UUID walletId) {
        return ledgerAccountRepository.findByWalletId(walletId).orElseThrow(() ->
                new IllegalStateException("Ledger account is missing for wallet: " + walletId));
    }

    private StoredTransferResult fail(IdempotencyRequest operation, WalletTransaction transaction, String code, String detail) {
        transaction.markFailed(code, Instant.now());
        return reject(operation, transaction.getId(),
                new ApiException(HttpStatus.CONFLICT, code, detail, transaction.getReference()));
    }

    private StoredTransferResult reject(IdempotencyRequest operation, UUID transactionId, ApiException error) {
        operation.reject(transactionId, (short) error.getStatus().value(),
                objectMapper.writeValueAsString(error.toProblemDetail()), Instant.now());
        idempotencyRepository.flush();
        return StoredTransferResult.from(operation);
    }

    private record PreparedTransfer(Wallet source, Wallet destination,
            LedgerAccount sourceAccount, LedgerAccount destinationAccount) {
    }
}
