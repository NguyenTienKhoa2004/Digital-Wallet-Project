package com.flowpay.integration.topup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.flowpay.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("mock-provider")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "mock-provider.api-key=provider-test-key",
        "mock-provider.callback-key=callback-test-key",
        "mock-provider.base-url=",
        "mock-provider.callback-base-url=",
        "flowpay.background-jobs-enabled=true",
        "flowpay.payment-poll-interval-ms=50",
        "mock-provider.processing-delay-ms=50",
        "mock-provider.processing-interval-ms=50",
        "mock-provider.callback-interval-ms=50"
})
@Timeout(45)
class TopUpScheduledIT {

    @Autowired
    private Environment environment;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void realScheduledJobsCompletePaymentAndDeliverCallbackWithoutManualTriggers() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String email = UUID.randomUUID() + "@example.com";
            var register = post(client, "/auth/register", Map.of(),
                    Map.of("username", "Scheduled test", "email", email, "password", "scheduled-password"));
            assertThat(register.statusCode()).as(register.body()).isEqualTo(201);
            var login = post(client, "/auth/login", Map.of(), Map.of("email", email, "password", "scheduled-password"));
            assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
            String token = mapper.readTree(login.body()).get("accessToken").asString();
            var wallet = post(client, "/wallets", Map.of("Authorization", "Bearer " + token), null);
            assertThat(wallet.statusCode()).as(wallet.body()).isEqualTo(201);
            UUID walletId = UUID.fromString(mapper.readTree(wallet.body()).get("walletId").asString());
            var created = post(client, "/top-ups", Map.of("Authorization", "Bearer " + token,
                    "Idempotency-Key", UUID.randomUUID().toString()), Map.of("amount", 1000));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            JsonNode body = mapper.readTree(created.body());
            UUID transactionId = UUID.fromString(body.get("transactionId").asString());

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            boolean complete = false;
            while (System.nanoTime() < deadline) {
                complete = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(
                            SELECT 1 FROM flowpay.transactions t
                            JOIN flowpay.provider_payments p ON p.transaction_id = t.transaction_id
                            JOIN mock_provider.payments m ON m.payment_id = p.payment_id
                            WHERE t.transaction_id = ? AND t.status = 'SUCCESS' AND m.callback_delivered_at IS NOT NULL
                        )
                        """, Boolean.class, transactionId));
                if (complete) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(complete).as("Scheduled provider processing, settlement and callback acknowledgement").isTrue();
            assertThat(jdbc.queryForObject("SELECT balance FROM flowpay.wallets WHERE wallet_id = ?", Long.class, walletId))
                    .isEqualTo(1000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flowpay.ledger_entries WHERE transaction_id = ?",
                    Long.class, transactionId)).isEqualTo(2);
        }
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> headers, Object body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
