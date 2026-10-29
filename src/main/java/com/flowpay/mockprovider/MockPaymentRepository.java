package com.flowpay.mockprovider;

import java.util.Optional;
import java.util.UUID;

import com.flowpay.payment.provider.CreateProviderPaymentRequest;
import com.flowpay.payment.provider.PaymentProviderStatus;
import com.flowpay.payment.provider.ProviderPaymentResult;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mock-provider")
public class MockPaymentRepository {

    private final JdbcTemplate jdbcTemplate;

    public MockPaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertIfAbsent(
            CreateProviderPaymentRequest request,
            String providerPaymentId) {

        return jdbcTemplate.update("""
                INSERT INTO mock_provider.payments
                    (payment_id, provider_payment_id, amount, currency)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (payment_id) DO NOTHING
                """,
                request.paymentId(),
                providerPaymentId,
                request.amount(),
                request.currency());
    }

    public Optional<ProviderPaymentResult> findByPaymentId(UUID paymentId) {
        return jdbcTemplate.query("""
                SELECT payment_id, provider_payment_id,
                       amount, currency, status
                FROM mock_provider.payments
                WHERE payment_id = ?
                """,
                (rs, rowNum) -> new ProviderPaymentResult(
                        rs.getObject("payment_id", UUID.class),
                        rs.getString("provider_payment_id"),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        PaymentProviderStatus.valueOf(rs.getString("status"))),
                paymentId)
                .stream()
                .findFirst();
    }

    public int completeDuePayments(
        PaymentProviderStatus finalStatus,
        long delayMs) {

        return jdbcTemplate.update("""
                UPDATE mock_provider.payments
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'PENDING'
                AND created_at <= CURRENT_TIMESTAMP
                    - (? * INTERVAL '1 millisecond')
                """,
                finalStatus.name(),
                delayMs);
    }

    public Optional<ProviderPaymentResult> claimCallback() {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT payment_id FROM mock_provider.payments
                    WHERE status IN ('SUCCESS', 'FAILED') AND callback_delivered_at IS NULL
                      AND callback_next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY callback_next_attempt_at, payment_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE mock_provider.payments p
                SET callback_next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                    callback_attempt_count = LEAST(p.callback_attempt_count::bigint + 1, 2147483647)::integer
                FROM due WHERE p.payment_id = due.payment_id
                RETURNING p.payment_id, p.provider_payment_id, p.amount, p.currency, p.status
                """, (rs, row) -> new ProviderPaymentResult(rs.getObject("payment_id", UUID.class),
                        rs.getString("provider_payment_id"), rs.getLong("amount"), rs.getString("currency"),
                        PaymentProviderStatus.valueOf(rs.getString("status")))).stream().findFirst();
    }

    public void callbackDelivered(UUID paymentId) {
        jdbcTemplate.update("""
                UPDATE mock_provider.payments SET callback_delivered_at = CURRENT_TIMESTAMP WHERE payment_id = ?
                """, paymentId);
    }

    public void retryCallback(UUID paymentId) {
        jdbcTemplate.update("""
                UPDATE mock_provider.payments
                SET callback_next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '5 seconds'
                WHERE payment_id = ? AND callback_delivered_at IS NULL
                """, paymentId);
    }
}

