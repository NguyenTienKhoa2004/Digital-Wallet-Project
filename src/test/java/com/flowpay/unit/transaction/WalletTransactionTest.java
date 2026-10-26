package com.flowpay.unit.transaction;

import java.time.Instant;
import java.util.UUID;

import com.flowpay.transaction.TransactionStatus;
import com.flowpay.transaction.WalletTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTransactionTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void successIsFinalAndSetsCompletionTime() {
        WalletTransaction transaction = pending();
        transaction.markSuccess(now.plusSeconds(1));
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(transaction.getCompletedAt()).isEqualTo(now.plusSeconds(1));
        assertThatThrownBy(() -> transaction.markFailed("ERROR", now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.markSuccess(now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureIsFinalAndRecordsItsReasonWithoutBecomingSuccess() {
        WalletTransaction transaction = pending();
        transaction.markFailed("INSUFFICIENT_BALANCE", now.plusSeconds(1));
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(transaction.getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(transaction.getUpdatedAt()).isEqualTo(transaction.getCompletedAt());
        assertThatThrownBy(() -> transaction.markSuccess(now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidTransitionArgumentsDoNotPartiallyChangeTheEntity() {
        WalletTransaction transaction = pending();
        assertThatThrownBy(() -> transaction.markSuccess(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.markFailed("ERROR", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transaction.markFailed(" ", now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transaction.getFailureCode()).isNull();
        assertThat(transaction.getCompletedAt()).isNull();
        assertThat(transaction.getUpdatedAt()).isEqualTo(now);
    }

    private WalletTransaction pending() {
        return WalletTransaction.pendingTransfer("TRF-test", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 3000, null, now);
    }
}
