package com.flowpay.integration.topup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.flowpay.idempotency.TopUpRequestFingerprint;
import com.flowpay.payment.ProviderPayment;
import com.flowpay.payment.ProviderPaymentRepository;
import com.flowpay.support.TestcontainersConfiguration;
import com.flowpay.topup.dto.TopUpRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.application.name=flowpay-topup-api-test"
})
@Timeout(90)
class TopUpApiIT {

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private ProviderPaymentRepository paymentRepository;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TopUpRequestFingerprint fingerprint;

    private HttpClient client;
    private Account owner;

    @BeforeEach
    void createAuthenticatedWalletThroughHttp() throws Exception {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        owner = account(true);
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void creationCommitsPendingTransactionPaymentAndReplayResultWithoutCreditingWallet() throws Exception {
        String key = key();
        HttpResponse<String> response = post(owner.token(), key, payload(1000));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        UUID transactionId = UUID.fromString(body.get("transactionId").asString());

        assertThat(body.get("reference").asString()).startsWith("TOP-");
        assertThat(body.get("destinationWalletId").asString()).isEqualTo(owner.walletId().toString());
        assertThat(body.get("amount").asLong()).isEqualTo(1000);
        assertThat(body.get("currency").asString()).isEqualTo("USD");
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("description").asString()).isEqualTo("Top up 10 USD");
        assertThat(body.get("createdAt").asString()).isNotBlank();
        assertThat(body.get("completedAt").isNull()).isTrue();
        assertThat(body.get("failureCode").isNull()).isTrue();

        Map<String, Object> transaction = jdbcTemplate.queryForMap(
                "SELECT * FROM flowpay.transactions WHERE transaction_id = ?", transactionId);
        assertThat(transaction.get("initiated_by")).isEqualTo(owner.userId());
        assertThat(transaction.get("source_wallet_id")).isNull();
        assertThat(transaction.get("destination_wallet_id")).isEqualTo(owner.walletId());
        assertThat(transaction.get("type")).isEqualTo("TOP_UP");
        assertThat(transaction.get("status")).isEqualTo("PENDING");
        assertThat(transaction.get("amount")).isEqualTo(1000L);
        assertThat(transaction.get("completed_at")).isNull();

        Map<String, Object> payment = jdbcTemplate.queryForMap(
                "SELECT * FROM flowpay.provider_payments WHERE transaction_id = ?", transactionId);
        assertThat(payment.get("payment_id")).isInstanceOf(UUID.class);
        assertThat(payment.get("provider")).isEqualTo("MOCK");
        assertThat(payment.get("provider_payment_id")).isNull();
        assertThat(payment.get("provider_status")).isEqualTo("UNKNOWN");
        assertThat(payment.get("attempt_count")).isEqualTo(0);
        assertThat(payment.get("next_check_at")).isNotNull();
        assertThat(payment.get("last_checked_at")).isNull();

        Map<String, Object> operation = jdbcTemplate.queryForMap("""
                SELECT * FROM flowpay.idempotency_requests
                WHERE user_id = ? AND operation_type = 'TOP_UP' AND idempotency_key = ?
                """, owner.userId(), key);
        assertThat(operation.get("transaction_id")).isEqualTo(transactionId);
        assertThat(operation.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) operation.get("response_status")).intValue()).isEqualTo(201);
        assertThat(objectMapper.readTree(operation.get("response_body").toString())).isEqualTo(body);
        assertPendingRecords(owner, 1);
    }

    @Test
    void retryReplaysTheOriginalResultEvenAfterTheWalletIsFrozen() throws Exception {
        String key = key();
        HttpResponse<String> first = post(owner.token(), key, payload(1000));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        UUID paymentId = jdbcTemplate.queryForObject("""
                SELECT p.payment_id FROM flowpay.provider_payments p
                JOIN flowpay.transactions t ON t.transaction_id = p.transaction_id
                WHERE t.initiated_by = ?
                """, UUID.class, owner.userId());
        freezeWallet(true);

        HttpResponse<String> retry = post(owner.token(), key, payload(1000));

        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
        assertThat(json(retry)).isEqualTo(json(first));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payment_id FROM flowpay.provider_payments WHERE transaction_id = ?
                """, UUID.class, UUID.fromString(json(first).get("transactionId").asString())))
                .isEqualTo(paymentId);
        assertPendingRecords(owner, 1);
    }

    @Test
    void jsonPropertyOrderAndWhitespaceDoNotChangeRequestIdentity() throws Exception {
        String key = key();
        HttpResponse<String> first = post(owner.token(), key, payload(1000));
        HttpResponse<String> retry = post(owner.token(), key, """
                { "description": "Top up 10 USD", "amount": 1000 }
                """);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
        assertThat(json(retry)).isEqualTo(json(first));
        assertPendingRecords(owner, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "description"})
    void sameKeyWithDifferentBusinessFieldsIsRejected(String changed) throws Exception {
        String key = key();
        assertThat(post(owner.token(), key, payload(1000)).statusCode()).isEqualTo(201);
        String different = objectMapper.writeValueAsString(new TopUpRequest(
                "amount".equals(changed) ? 2000 : 1000,
                "description".equals(changed) ? "Other description" : "Top up 10 USD"));

        assertProblem(post(owner.token(), key, different), 409, "IDEMPOTENCY_KEY_CONFLICT");
        assertPendingRecords(owner, 1);
    }

    @Test
    void nullAndEmptyDescriptionsHaveDifferentRequestIdentities() throws Exception {
        String key = key();
        HttpResponse<String> first = post(owner.token(), key, "{\"amount\":1000}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(json(first).get("description").isNull()).isTrue();
        assertProblem(post(owner.token(), key, "{\"amount\":1000,\"description\":\"\"}"),
                409, "IDEMPOTENCY_KEY_CONFLICT");
        assertPendingRecords(owner, 1);
    }

    @Test
    void concurrentRetriesCreateExactlyOneTransactionAndOnePayment() throws Exception {
        String key = key();
        String body = payload(1000);
        List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(() -> post(owner.token(), key, body));
        }

        List<HttpResponse<String>> responses = concurrently(requests);

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(json(response)).isEqualTo(json(responses.getFirst()));
        }
        assertPendingRecords(owner, 1);
    }

    @Test
    void concurrentDifferentPayloadsWithTheSameKeyCannotBothCreatePayments() throws Exception {
        String key = key();
        List<HttpResponse<String>> responses = concurrently(List.of(
                () -> post(owner.token(), key, payload(1000)),
                () -> post(owner.token(), key, payload(2000))));

        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(201, 409);
        HttpResponse<String> rejected = responses.stream()
                .filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        assertProblem(rejected, 409, "IDEMPOTENCY_KEY_CONFLICT");
        HttpResponse<String> accepted = responses.stream()
                .filter(response -> response.statusCode() == 201).findFirst().orElseThrow();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT amount FROM flowpay.transactions WHERE initiated_by = ?
                """, Long.class, owner.userId())).isEqualTo(json(accepted).get("amount").asLong());
        assertPendingRecords(owner, 1);
    }

    @Test
    void differentKeysAllowIndependentTopUpsWithIdenticalPayloads() throws Exception {
        List<HttpResponse<String>> responses = concurrently(List.of(
                () -> post(owner.token(), key(), payload(1000)),
                () -> post(owner.token(), key(), payload(1000))));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsOnly(201);
        assertThat(json(responses.get(0)).get("transactionId"))
                .isNotEqualTo(json(responses.get(1)).get("transactionId"));
        assertPendingRecords(owner, 2);
    }

    @Test
    void keysAreScopedToTheAuthenticatedUserAndOperationType() throws Exception {
        String key = key();
        jdbcTemplate.update("""
                INSERT INTO flowpay.idempotency_requests
                (user_id, operation_type, idempotency_key, request_hash, status, updated_at)
                VALUES (?, 'TRANSFER', ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                """, owner.userId(), key, "0".repeat(64));
        Account other = account(true);

        HttpResponse<String> first = post(owner.token(), key, payload(1000));
        HttpResponse<String> second = post(other.token(), key, payload(1000));

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(json(first).get("destinationWalletId").asString()).isEqualTo(owner.walletId().toString());
        assertThat(json(second).get("destinationWalletId").asString()).isEqualTo(other.walletId().toString());
        assertThat(json(first).get("transactionId")).isNotEqualTo(json(second).get("transactionId"));
        assertPendingRecords(owner, 1);
        assertPendingRecords(other, 1);
    }

    @Test
    void frozenWalletRejectionRollsBackAndTheSameKeyCanBeRetriedAfterUnfreezing() throws Exception {
        String key = key();
        freezeWallet(true);
        assertProblem(post(owner.token(), key, payload(1000)), 409, "WALLET_NOT_ACTIVE");
        assertPendingRecords(owner, 0);

        freezeWallet(false);
        assertThat(post(owner.token(), key, payload(1000)).statusCode()).isEqualTo(201);
        assertPendingRecords(owner, 1);
    }

    @Test
    void aUserWithoutAWalletCannotCreateATopUpOrConsumeAKey() throws Exception {
        Account withoutWallet = account(false);
        assertProblem(post(withoutWallet.token(), key(), payload(1000)), 404, "WALLET_NOT_FOUND");
        assertPendingRecords(withoutWallet, 0);
        assertPendingRecords(owner, 0);
    }

    @Test
    void failureAfterPaymentSqlRollsBackAllRecordsAndAllowsRetryWithTheSameKey() throws Exception {
        String key = key();
        doAnswer(invocation -> {
            ProviderPayment payment = invocation.getArgument(0);
            entityManager.persist(payment);
            entityManager.flush();
            // These SQL writes exist inside the request transaction before the injected failure.
            assertPendingRecords(owner, 1);
            throw new DataAccessResourceFailureException("Simulated failure after payment SQL");
        }).when(paymentRepository).save(any(ProviderPayment.class));

        assertProblem(post(owner.token(), key, payload(1000)), 503, "OPERATION_OUTCOME_UNKNOWN");
        assertPendingRecords(owner, 0);

        reset(paymentRepository);
        assertThat(post(owner.token(), key, payload(1000)).statusCode()).isEqualTo(201);
        assertPendingRecords(owner, 1);
    }

    @Test
    void anExistingProcessingKeyDoesNotCreateAnotherTransaction() throws Exception {
        String key = key();
        jdbcTemplate.update("""
                INSERT INTO flowpay.idempotency_requests
                (user_id, operation_type, idempotency_key, request_hash, status, updated_at)
                VALUES (?, 'TOP_UP', ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                """, owner.userId(), key, fingerprint.hash(new TopUpRequest(1000, "Top up 10 USD")));

        assertProblem(post(owner.token(), key, payload(1000)), 409, "IDEMPOTENCY_IN_PROGRESS");
        assertThat(transactionCount(owner)).isZero();
        assertThat(paymentCount(owner)).isZero();
        assertThat(keyCount(owner)).isEqualTo(1);
        assertNoMoneyMoved(owner);
    }

    @Test
    void missingOrInvalidIdempotencyHeadersDoNotCreateRecords() throws Exception {
        assertProblem(post(owner.token(), null, payload(1000)), 400, "MISSING_REQUIRED_HEADER");
        assertProblem(post(owner.token(), "", payload(1000)), 400, "INVALID_IDEMPOTENCY_KEY");
        assertProblem(post(owner.token(), "x".repeat(256), payload(1000)), 400, "INVALID_IDEMPOTENCY_KEY");
        assertPendingRecords(owner, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"amount\":0}", "{\"amount\":-1}", "{\"amount\":1.5}",
            "{\"amount\":9223372036854775808}", "not-json"
    })
    void malformedOrInvalidRequestsDoNotCreateRecords(String body) throws Exception {
        HttpResponse<String> response = post(owner.token(), key(), body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertPendingRecords(owner, 0);
    }

    @Test
    void oversizedDescriptionIsRejectedWithoutConsumingAKey() throws Exception {
        String body = objectMapper.writeValueAsString(new TopUpRequest(1000, "x".repeat(256)));
        assertProblem(post(owner.token(), key(), body), 400, "VALIDATION_FAILED");
        assertPendingRecords(owner, 0);
    }

    @Test
    void missingOrInvalidAuthenticationDoesNotCreateRecords() throws Exception {
        assertProblem(post(null, key(), payload(1000)), 401, "UNAUTHORIZED");
        assertProblem(post("invalid-token", key(), payload(1000)), 401, "UNAUTHORIZED");
        assertPendingRecords(owner, 0);
    }

    private Account account(boolean createWallet) throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String password = "topup-api-password";
        HttpResponse<String> registered = send("/auth/register", null, null,
                objectMapper.writeValueAsString(Map.of("username", "Top-up test", "email", email, "password", password)));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        UUID userId = UUID.fromString(json(registered).get("userId").asString());
        HttpResponse<String> login = send("/auth/login", null, null,
                objectMapper.writeValueAsString(Map.of("email", email, "password", password)));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String token = json(login).get("accessToken").asString();
        if (!createWallet) {
            return new Account(userId, null, token);
        }
        HttpResponse<String> wallet = send("/wallets", token, null, null);
        assertThat(wallet.statusCode()).as(wallet.body()).isEqualTo(201);
        return new Account(userId, UUID.fromString(json(wallet).get("walletId").asString()), token);
    }

    private HttpResponse<String> post(String token, String key, String body) throws Exception {
        return send("/top-ups", token, key, body);
    }

    private HttpResponse<String> send(String path, String token, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(30))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String payload(long amount) {
        return objectMapper.writeValueAsString(new TopUpRequest(amount, "Top up 10 USD"));
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }

    private void freezeWallet(boolean frozen) {
        jdbcTemplate.update("UPDATE flowpay.wallets SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE wallet_id = ?",
                frozen ? "FROZEN" : "ACTIVE", owner.walletId());
    }

    private long transactionCount(Account account) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.transactions WHERE initiated_by = ? AND type = 'TOP_UP'
                """, Long.class, account.userId());
    }

    private long paymentCount(Account account) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.provider_payments p
                JOIN flowpay.transactions t ON t.transaction_id = p.transaction_id
                WHERE t.initiated_by = ?
                """, Long.class, account.userId());
    }

    private long keyCount(Account account) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.idempotency_requests WHERE user_id = ? AND operation_type = 'TOP_UP'
                """, Long.class, account.userId());
    }

    private void assertPendingRecords(Account account, long expected) {
        assertThat(transactionCount(account)).isEqualTo(expected);
        assertThat(paymentCount(account)).isEqualTo(expected);
        assertThat(keyCount(account)).isEqualTo(expected);
        assertNoMoneyMoved(account);
    }

    private void assertNoMoneyMoved(Account account) {
        if (account.walletId() != null) {
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM flowpay.wallets WHERE wallet_id = ?",
                    Long.class, account.walletId())).isZero();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.ledger_entries e
                JOIN flowpay.transactions t ON t.transaction_id = e.transaction_id
                WHERE t.initiated_by = ?
                """, Long.class, account.userId())).isZero();
    }

    private List<HttpResponse<String>> concurrently(List<Callable<HttpResponse<String>>> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = requests.stream().map(request -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("HTTP requests did not start in time.");
                }
                return request.call();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (var future : futures) {
                responses.add(future.get(45, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private record Account(UUID userId, UUID walletId, String token) {
    }
}
