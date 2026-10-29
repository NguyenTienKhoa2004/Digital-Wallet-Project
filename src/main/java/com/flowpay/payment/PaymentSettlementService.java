package com.flowpay.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.ledger.EntryType;
import com.flowpay.ledger.LedgerAccountRepository;
import com.flowpay.ledger.LedgerEntry;
import com.flowpay.ledger.LedgerEntryRepository;
import com.flowpay.payment.provider.ProviderPaymentResult;
import com.flowpay.transaction.TransactionStatus;
import com.flowpay.transaction.TransactionType;
import com.flowpay.transaction.WalletTransaction;
import com.flowpay.transaction.WalletTransactionRepository;
import com.flowpay.wallet.Wallet;
import com.flowpay.wallet.WalletRepository;
import com.flowpay.wallet.WalletStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentSettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSettlementService.class);
    private final ProviderPaymentRepository payments;
    private final WalletTransactionRepository transactions;
    private final WalletRepository wallets;
    private final LedgerAccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final JdbcTemplate jdbc;

    public PaymentSettlementService(ProviderPaymentRepository payments, WalletTransactionRepository transactions,
            WalletRepository wallets, LedgerAccountRepository accounts, LedgerEntryRepository entries, JdbcTemplate jdbc) {
        this.payments = payments;
        this.transactions = transactions;
        this.wallets = wallets;
        this.accounts = accounts;
        this.entries = entries;
        this.jdbc = jdbc;
    }

    // All observation paths lock payment -> transaction -> wallet in the same order.
    @Transactional
    public void accept(ProviderPaymentResult result, String source) {
        validate(result);
        ProviderPayment payment = payments.findByIdForUpdate(result.paymentId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "FlowPay payment does not exist."));
        WalletTransaction transaction = transactions.findByIdForUpdate(payment.getTransactionId()).orElseThrow();
        if (transaction.getType() != TransactionType.TOP_UP || transaction.getAmount() != result.amount()
                || !transaction.getCurrency().equals(result.currency())
                || (payment.getProviderPaymentId() != null
                    && !payment.getProviderPaymentId().equals(result.providerPaymentId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_PAYMENT_MISMATCH",
                    "Provider result does not match the stored payment.");
        }
        TransactionStatus previous = transaction.getStatus();
        Instant now = Instant.now();
        ProviderPaymentStatus observed = ProviderPaymentStatus.valueOf(result.status().name());
        ProviderPaymentStatus known = payment.getProviderStatus();

        if (known == ProviderPaymentStatus.SUCCESS || known == ProviderPaymentStatus.FAILED) {
            if (observed == ProviderPaymentStatus.PENDING) {
                audit(payment, previous, observed, "MATCHED", source + ": ignored stale PENDING");
                return;
            }
            if (known != observed) {
                payment.scheduleRetry("CONFLICTING_PROVIDER_RESULT",
                        previous == TransactionStatus.PENDING ? now.plusSeconds(60) : null, now);
                audit(payment, previous, observed, "MANUAL_REVIEW", source + ": conflicting final result");
                LOG.error("Conflicting provider result for payment {}", payment.getId());
                return;
            }
        }
        if (previous != TransactionStatus.PENDING) {
            audit(payment, previous, observed, "MATCHED", source + ": duplicate final result");
            return;
        }

        payment.observe(result.providerPaymentId(), observed, now);
        if (observed == ProviderPaymentStatus.PENDING) {
            payment.scheduleRetry(null, now.plusSeconds(5), now);
            audit(payment, previous, observed, "RETRY", source + ": awaiting provider");
            return;
        }
        if (observed == ProviderPaymentStatus.FAILED) {
            transaction.markFailed("PROVIDER_PAYMENT_FAILED", now);
        } else {
            Wallet wallet = wallets.findByIdForUpdate(transaction.getDestinationWalletId()).orElseThrow();
            String blocked = wallet.getStatus() != WalletStatus.ACTIVE ? "WALLET_NOT_ACTIVE"
                    : wallet.getBalance() > Long.MAX_VALUE - transaction.getAmount() ? "BALANCE_LIMIT_EXCEEDED" : null;
            if (blocked != null) {
                payment.scheduleRetry(blocked, now.plusSeconds(5), now);
                audit(payment, previous, observed, "MANUAL_REVIEW", source + ": " + blocked);
                LOG.warn("Payment {} confirmed but settlement is deferred: {}", payment.getId(), blocked);
                return;
            }
            UUID clearingId = accounts.findByAccountCode("MOCK_PROVIDER_CLEARING").orElseThrow().getId();
            UUID walletAccountId = accounts.findByWalletId(wallet.getId()).orElseThrow().getId();
            wallet.credit(transaction.getAmount(), now);
            entries.saveAllAndFlush(List.of(
                    LedgerEntry.create(transaction.getId(), clearingId, EntryType.DEBIT, transaction.getAmount(), now),
                    LedgerEntry.create(transaction.getId(), walletAccountId, EntryType.CREDIT, transaction.getAmount(), now)));
            transaction.markSuccess(now);
        }
        payment.finish(now);
        audit(payment, previous, observed, "RESOLVED", source + ": finalized");
        LOG.info("Top-up {} finalized as {}", transaction.getReference(), transaction.getStatus());
    }

    @Transactional
    public void retry(UUID paymentId, String errorCode) {
        payments.findByIdForUpdate(paymentId).ifPresent(payment -> {
            WalletTransaction transaction = transactions.findByIdForUpdate(payment.getTransactionId()).orElseThrow();
            if (transaction.getStatus() == TransactionStatus.PENDING) {
                Instant now = Instant.now();
                payment.scheduleRetry(errorCode, now.plusSeconds(5), now);
                audit(payment, transaction.getStatus(), payment.getProviderStatus(), "RETRY", "POLL: " + errorCode);
            }
        });
    }

    private void audit(ProviderPayment payment, TransactionStatus previous, ProviderPaymentStatus observed,
            String result, String detail) {
        jdbc.update("""
                INSERT INTO flowpay.reconciliation_checks
                    (payment_id, previous_transaction_status, observed_provider_status, result, detail)
                VALUES (?, ?, ?, ?, ?)
                """, payment.getId(), previous.name(), observed.name(), result, detail);
    }

    private void validate(ProviderPaymentResult result) {
        if (result == null || result.paymentId() == null || result.providerPaymentId() == null
                || result.providerPaymentId().isBlank() || result.providerPaymentId().length() > 255
                || result.amount() <= 0 || !"USD".equals(result.currency()) || result.status() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_RESULT", "Invalid provider result.");
        }
    }
}
