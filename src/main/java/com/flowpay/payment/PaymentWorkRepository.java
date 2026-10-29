package com.flowpay.payment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentWorkRepository {

    private final JdbcTemplate jdbc;

    public PaymentWorkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // One SQL statement commits a lease. No database lock is held during HTTP calls.
    // A crashed worker's lease expires; subsequent attempts use the same payment ID.
    public Optional<Work> claimNext() {
        return jdbc.query("""
                WITH due AS (
                    SELECT p.payment_id FROM flowpay.provider_payments p
                    JOIN flowpay.transactions t ON t.transaction_id = p.transaction_id
                    WHERE p.provider = 'MOCK' AND t.status = 'PENDING'
                      AND p.next_check_at <= CURRENT_TIMESTAMP
                    ORDER BY p.next_check_at, p.payment_id
                    LIMIT 1 FOR UPDATE OF p SKIP LOCKED
                ), claimed AS (
                    UPDATE flowpay.provider_payments p
                    SET next_check_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                        attempt_count = LEAST(p.attempt_count::bigint + 1, 2147483647)::integer,
                        updated_at = CURRENT_TIMESTAMP
                    FROM due WHERE p.payment_id = due.payment_id
                    RETURNING p.payment_id, p.transaction_id
                )
                SELECT c.payment_id, t.amount, t.currency FROM claimed c
                JOIN flowpay.transactions t ON t.transaction_id = c.transaction_id
                """, (rs, row) -> new Work(rs.getObject("payment_id", UUID.class),
                        rs.getLong("amount"), rs.getString("currency"))).stream().findFirst();
    }

    public record Work(UUID paymentId, long amount, String currency) {
    }
}
