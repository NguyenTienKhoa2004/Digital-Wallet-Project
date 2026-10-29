package com.flowpay.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {

    private final JdbcTemplate jdbc;

    public ReconciliationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/payments/{paymentId}/checks")
    public List<Check> checks(@PathVariable UUID paymentId) {
        if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM flowpay.provider_payments WHERE payment_id = ?)",
                Boolean.class, paymentId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment does not exist.");
        }
        return jdbc.query("""
                SELECT check_id, previous_transaction_status, observed_provider_status, result, detail, checked_at
                FROM flowpay.reconciliation_checks WHERE payment_id = ?
                ORDER BY checked_at DESC, check_id DESC LIMIT 100
                """, (rs, row) -> new Check(rs.getObject("check_id", UUID.class), rs.getString("previous_transaction_status"),
                        rs.getString("observed_provider_status"), rs.getString("result"), rs.getString("detail"),
                        rs.getTimestamp("checked_at").toInstant()), paymentId);
    }

    public record Check(UUID checkId, String previousStatus, String observedStatus, String result, String detail, Instant checkedAt) {
    }
}
