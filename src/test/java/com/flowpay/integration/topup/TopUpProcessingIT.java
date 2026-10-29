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
import java.util.concurrent.atomic.AtomicBoolean;

import com.flowpay.ledger.LedgerEntry;
import com.flowpay.ledger.LedgerEntryRepository;
import com.flowpay.mockprovider.MockPaymentCallbackSender;
import com.flowpay.mockprovider.MockPaymentProcessor;
import com.flowpay.mockprovider.MockPaymentRepository;
import com.flowpay.payment.PaymentWorkRepository;
import com.flowpay.payment.PaymentWorker;
import com.flowpay.payment.provider.CreateProviderPaymentRequest;
import com.flowpay.payment.provider.HttpPaymentProviderClient;
import com.flowpay.payment.provider.PaymentProviderStatus;
import com.flowpay.payment.provider.ProviderPaymentResult;
import com.flowpay.support.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("mock-provider")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "mock-provider.api-key=provider-test-key",
        "mock-provider.callback-key=callback-test-key",
        "mock-provider.base-url=",
        "mock-provider.callback-base-url=",
        "flowpay.background-jobs-enabled=false",
        "mock-provider.processing-delay-ms=1"
})
@Timeout(90)
class TopUpProcessingIT {

    @Autowired
    private Environment environment;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaymentWorker worker;
    @Autowired
    private PaymentWorkRepository work;
    @Autowired
    private MockPaymentProcessor processor;
    @MockitoSpyBean
    private MockPaymentRepository mockPayments;
    @Autowired
    private MockPaymentCallbackSender callbacks;
    @MockitoSpyBean
    private HttpPaymentProviderClient provider;
    @MockitoSpyBean
    private LedgerEntryRepository entries;
    @PersistenceContext
    private EntityManager entityManager;

    private HttpClient http;
    private Account owner;

    @BeforeEach
    void setUp() throws Exception {
        // Previous tests retain their audit/ledger records; park only their outstanding jobs.
        jdbc.update("UPDATE flowpay.provider_payments SET next_check_at = CURRENT_TIMESTAMP + INTERVAL '1 day' "
                + "WHERE next_check_at IS NOT NULL");
        jdbc.update("UPDATE mock_provider.payments SET callback_next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 day'");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        owner = account();
    }

    @AfterEach
    void closeHttp() {
        http.close();
    }

    @Test
    void fullHttpFlowCreditsWalletWithBalancedLedgerAndExposesCurrentStatus() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        assertState(pending, "PENDING", 0, 0);
        assertThat(provider.findByPaymentId(pending.paymentId()).status()).isEqualTo(PaymentProviderStatus.PENDING);

        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        callbacks.dispatchDueCallbacks();

        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("""
                SELECT SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END)
                FROM flowpay.ledger_entries WHERE transaction_id = ?
                """, Long.class, pending.transactionId())).isZero();
        assertThat(jdbc.queryForList("""
                SELECT a.purpose || ':' || e.entry_type FROM flowpay.ledger_entries e
                JOIN flowpay.ledger_accounts a ON a.account_id = e.account_id WHERE e.transaction_id = ?
                """, String.class, pending.transactionId()))
                .containsExactlyInAnyOrder("PROVIDER_CLEARING:DEBIT", "WALLET:CREDIT");
        assertThat(jdbc.queryForObject("SELECT callback_delivered_at IS NOT NULL FROM mock_provider.payments WHERE payment_id = ?",
                Boolean.class, pending.paymentId())).isTrue();

        JsonNode latest = json(get("/top-ups/" + pending.reference(), owner));
        assertThat(latest.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(latest.get("completedAt").isNull()).isFalse();
        assertThat(json(send("GET", "/top-ups/by-idempotency-key",
                Map.of("Authorization", "Bearer " + owner.token(), "Idempotency-Key", pending.key()), null)))
                .isEqualTo(latest);
        assertThat(json(get("/top-ups", owner)).get("items").get(0)).isEqualTo(latest);
        assertThat(json(send("POST", "/top-ups", userHeaders(owner, pending.key()), Map.of("amount", 1000)))
                .get("status").asString()).isEqualTo("PENDING");
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flowpay.reconciliation_checks WHERE payment_id = ? AND result = 'RESOLVED'
                """, Long.class, pending.paymentId())).isEqualTo(1);
    }

    @Test
    void failedPaymentDoesNotCreditWalletOrCreateLedgerEntries() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.FAILED);
        callbacks.dispatchDueCallbacks();
        assertState(pending, "FAILED", 0, 0);
        assertThat(json(get("/top-ups/" + pending.reference(), owner)).get("failureCode").asString())
                .isEqualTo("PROVIDER_PAYMENT_FAILED");
    }

    @Test
    void theScheduledProviderProcessorCompletesDuePayments() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        jdbc.update("UPDATE mock_provider.payments SET created_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE payment_id = ?",
                pending.paymentId());
        processor.processPendingPayments();
        assertThat(provider.findByPaymentId(pending.paymentId()).status()).isEqualTo(PaymentProviderStatus.SUCCESS);
        callbacks.dispatchDueCallbacks();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void duplicateConcurrentCallbacksAndPollingCreditExactlyOnce() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        ProviderPaymentResult result = provider.findByPaymentId(pending.paymentId());
        due(pending);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                assertThat(callback(result, "callback-test-key").statusCode()).isEqualTo(204);
                return null;
            });
        }
        tasks.add(() -> {
            worker.processDuePayments();
            return null;
        });
        concurrently(tasks);
        assertState(pending, "SUCCESS", 1000, 2);

        ProviderPaymentResult stale = new ProviderPaymentResult(result.paymentId(), result.providerPaymentId(),
                result.amount(), result.currency(), PaymentProviderStatus.PENDING);
        assertThat(callback(stale, "callback-test-key").statusCode()).isEqualTo(204);
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("SELECT provider_status FROM flowpay.provider_payments WHERE payment_id = ?",
                String.class, pending.paymentId())).isEqualTo("SUCCESS");
    }

    @Test
    void concurrentSuccessfulPaymentsForOneWalletDoNotLoseCredits() throws Exception {
        Pending first = topUp(1000);
        Pending second = topUp(2000);
        worker.processDuePayments();
        completeAtProvider(first, PaymentProviderStatus.SUCCESS);
        completeAtProvider(second, PaymentProviderStatus.SUCCESS);
        ProviderPaymentResult a = provider.findByPaymentId(first.paymentId());
        ProviderPaymentResult b = provider.findByPaymentId(second.paymentId());
        concurrently(List.of(
                () -> {
                    assertThat(callback(a, "callback-test-key").statusCode()).isEqualTo(204);
                    return null;
                },
                () -> {
                    assertThat(callback(b, "callback-test-key").statusCode()).isEqualTo(204);
                    return null;
                }));
        assertState(first, "SUCCESS", 3000, 2);
        assertState(second, "SUCCESS", 3000, 2);
    }

    @Test
    void lostPostResponseIsRecoveredByFlowPayPaymentIdWithoutCreatingAnotherProviderPayment() throws Exception {
        Pending pending = topUp(1000);
        AtomicBoolean loseResponse = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ProviderPaymentResult response = (ProviderPaymentResult) invocation.callRealMethod();
            CreateProviderPaymentRequest request = invocation.getArgument(0);
            if (request.paymentId().equals(pending.paymentId()) && loseResponse.getAndSet(false)) {
                throw new ResourceAccessException("Simulated lost response after provider commit");
            }
            return response;
        }).when(provider).createPayment(any());

        worker.processDuePayments();
        assertState(pending, "PENDING", 0, 0);
        assertThat(jdbc.queryForObject("SELECT provider_payment_id FROM flowpay.provider_payments WHERE payment_id = ?",
                String.class, pending.paymentId())).isNull();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        due(pending);
        worker.processDuePayments();
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_provider.payments WHERE payment_id = ?",
                Long.class, pending.paymentId())).isEqualTo(1);
    }

    @Test
    void providerUnavailableDoesNotBecomeAFailedPayment() throws Exception {
        Pending pending = topUp(1000);
        doThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(provider).findByPaymentId(pending.paymentId());
        worker.processDuePayments();
        assertState(pending, "PENDING", 0, 0);
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM flowpay.provider_payments WHERE payment_id = ?",
                String.class, pending.paymentId())).isEqualTo("PROVIDER_HTTP_503");
        reset(provider);
        due(pending);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        due(pending);
        worker.processDuePayments();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void pollingRecoversASuccessWhenTheCallbackWasNeverDelivered() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        due(pending);
        worker.processDuePayments();
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("SELECT callback_delivered_at FROM mock_provider.payments WHERE payment_id = ?",
                Object.class, pending.paymentId())).isNull();
        callbacks.dispatchDueCallbacks();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void expiredDurableLeaseCanBeRecoveredByAnotherWorker() throws Exception {
        Pending pending = topUp(1000);
        assertThat(work.claimNext().orElseThrow().paymentId()).isEqualTo(pending.paymentId());
        worker.processDuePayments();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_provider.payments WHERE payment_id = ?",
                Long.class, pending.paymentId())).isZero();
        due(pending); // Simulate the lease expiring after the original worker crashed.
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        due(pending);
        worker.processDuePayments();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void failedCallbackSettlementRollsBackAndDurableCallbackRetrySucceeds() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        doAnswer(invocation -> {
            Iterable<LedgerEntry> posting = invocation.getArgument(0);
            posting.forEach(entityManager::persist);
            entityManager.flush();
            assertState(pending, "PENDING", 1000, 2);
            throw new DataAccessResourceFailureException("Simulated crash after ledger SQL");
        }).when(entries).saveAllAndFlush(any());

        callbacks.dispatchDueCallbacks();
        assertState(pending, "PENDING", 0, 0);
        assertThat(jdbc.queryForObject("SELECT callback_delivered_at FROM mock_provider.payments WHERE payment_id = ?",
                Object.class, pending.paymentId())).isNull();
        assertThat(jdbc.queryForObject("SELECT callback_attempt_count FROM mock_provider.payments WHERE payment_id = ?",
                Integer.class, pending.paymentId())).isEqualTo(1);
        reset(entries);
        jdbc.update("UPDATE mock_provider.payments SET callback_next_attempt_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                pending.paymentId());
        callbacks.dispatchDueCallbacks();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void lostCallbackAcknowledgementDoesNotCreditTwiceWhenProviderRedelivers() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        doThrow(new DataAccessResourceFailureException("Simulated lost callback acknowledgement"))
                .doCallRealMethod().when(mockPayments).callbackDelivered(pending.paymentId());

        callbacks.dispatchDueCallbacks();
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("SELECT callback_delivered_at FROM mock_provider.payments WHERE payment_id = ?",
                Object.class, pending.paymentId())).isNull();
        jdbc.update("UPDATE mock_provider.payments SET callback_next_attempt_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                pending.paymentId());
        callbacks.dispatchDueCallbacks();
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("SELECT callback_delivered_at IS NOT NULL FROM mock_provider.payments WHERE payment_id = ?",
                Boolean.class, pending.paymentId())).isTrue();
    }

    @Test
    void anAbandonedCallbackLeaseIsDeliveredAfterItExpires() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        assertThat(mockPayments.claimCallback().orElseThrow().paymentId()).isEqualTo(pending.paymentId());
        callbacks.dispatchDueCallbacks();
        assertState(pending, "PENDING", 0, 0);
        jdbc.update("UPDATE mock_provider.payments SET callback_next_attempt_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                pending.paymentId());
        callbacks.dispatchDueCallbacks();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void frozenWalletDefersConfirmedFundsUntilItCanBeSettled() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        jdbc.update("UPDATE flowpay.wallets SET status = 'FROZEN' WHERE wallet_id = ?", owner.walletId());
        callbacks.dispatchDueCallbacks();
        assertState(pending, "PENDING", 0, 0);
        assertThat(jdbc.queryForObject("SELECT provider_status FROM flowpay.provider_payments WHERE payment_id = ?",
                String.class, pending.paymentId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM flowpay.provider_payments WHERE payment_id = ?",
                String.class, pending.paymentId())).isEqualTo("WALLET_NOT_ACTIVE");
        jdbc.update("UPDATE flowpay.wallets SET status = 'ACTIVE' WHERE wallet_id = ?", owner.walletId());
        due(pending);
        worker.processDuePayments();
        assertState(pending, "SUCCESS", 1000, 2);
    }

    @Test
    void overflowDoesNotDiscardAConfirmedPaymentOrOverflowTheWallet() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        jdbc.update("UPDATE flowpay.wallets SET balance = ? WHERE wallet_id = ?", Long.MAX_VALUE, owner.walletId());
        callbacks.dispatchDueCallbacks();
        assertState(pending, "PENDING", Long.MAX_VALUE, 0);
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM flowpay.provider_payments WHERE payment_id = ?",
                String.class, pending.paymentId())).isEqualTo("BALANCE_LIMIT_EXCEEDED");
    }

    @Test
    void callbackAuthenticationAndPayloadCorrelationAreRequired() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        ProviderPaymentResult result = provider.findByPaymentId(pending.paymentId());
        assertProblem(callback(result, null), 401, "INVALID_CALLBACK_KEY");
        assertProblem(callback(result, "wrong"), 401, "INVALID_CALLBACK_KEY");
        assertProblem(callback(new ProviderPaymentResult(result.paymentId(), result.providerPaymentId(),
                2000, "USD", PaymentProviderStatus.SUCCESS), "callback-test-key"), 409, "PROVIDER_PAYMENT_MISMATCH");
        assertProblem(callback(new ProviderPaymentResult(result.paymentId(), "different-id",
                1000, "USD", PaymentProviderStatus.SUCCESS), "callback-test-key"), 409, "PROVIDER_PAYMENT_MISMATCH");
        assertProblem(callback(new ProviderPaymentResult(result.paymentId(), result.providerPaymentId(),
                1000, "EUR", PaymentProviderStatus.SUCCESS), "callback-test-key"), 400, "INVALID_PROVIDER_RESULT");
        assertProblem(callback(new ProviderPaymentResult(UUID.randomUUID(), "unknown",
                1000, "USD", PaymentProviderStatus.SUCCESS), "callback-test-key"), 404, "PAYMENT_NOT_FOUND");
        assertState(pending, "PENDING", 0, 0);
    }

    @Test
    void conflictingFinalCallbackIsFlaggedWithoutReversingMoney() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        callbacks.dispatchDueCallbacks();
        var result = provider.findByPaymentId(pending.paymentId());
        assertThat(callback(new ProviderPaymentResult(result.paymentId(), result.providerPaymentId(),
                1000, "USD", PaymentProviderStatus.FAILED), "callback-test-key").statusCode()).isEqualTo(204);
        assertState(pending, "SUCCESS", 1000, 2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flowpay.reconciliation_checks WHERE payment_id = ? AND result = 'MANUAL_REVIEW'
                """, Long.class, pending.paymentId())).isEqualTo(1);
    }

    @Test
    void queriesArePrivateAndHistoryIsPaginated() throws Exception {
        Pending first = topUp(1000);
        topUp(2000);
        topUp(3000);
        Account outsider = account();
        assertProblem(get("/top-ups/" + first.reference(), outsider), 404, "TOP_UP_NOT_FOUND");
        assertProblem(send("GET", "/top-ups/by-idempotency-key", userHeaders(outsider, first.key()), null),
                404, "OPERATION_NOT_FOUND");
        assertThat(json(get("/top-ups", outsider)).get("items").isEmpty()).isTrue();
        JsonNode page = json(get("/top-ups?page=0&size=2", owner));
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(json(get("/top-ups?page=1&size=2", owner)).get("items").size()).isEqualTo(1);
        assertProblem(get("/top-ups?size=0", owner), 400, "INVALID_PAGINATION");
        assertProblem(send("GET", "/top-ups", Map.of(), null), 401, "UNAUTHORIZED");
    }

    @Test
    void reconciliationAuditRequiresAnAdminToken() throws Exception {
        Pending pending = topUp(1000);
        worker.processDuePayments();
        completeAtProvider(pending, PaymentProviderStatus.SUCCESS);
        callbacks.dispatchDueCallbacks();
        String path = "/admin/payments/" + pending.paymentId() + "/checks";
        assertThat(get(path, owner).statusCode()).isEqualTo(403);
        assertThat(send("GET", path, Map.of(), null).statusCode()).isEqualTo(401);

        jdbc.update("UPDATE flowpay.users SET role = 'ADMIN' WHERE user_id = "
                + "(SELECT user_id FROM flowpay.wallets WHERE wallet_id = ?)", owner.walletId());
        String email = jdbc.queryForObject("""
                SELECT u.email FROM flowpay.users u JOIN flowpay.wallets w ON w.user_id = u.user_id WHERE w.wallet_id = ?
                """, String.class, owner.walletId());
        var login = send("POST", "/auth/login", Map.of(), Map.of("email", email, "password", "processing-password"));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        Account admin = new Account(owner.walletId(), json(login).get("accessToken").asString());
        var checks = get(path, admin);
        assertThat(checks.statusCode()).as(checks.body()).isEqualTo(200);
        assertThat(json(checks).get(0).get("result").asString()).isEqualTo("RESOLVED");
        assertProblem(get("/admin/payments/" + UUID.randomUUID() + "/checks", admin), 404, "PAYMENT_NOT_FOUND");
    }

    @Test
    void mockProviderEnforcesApiKeyAndIdempotencyAcrossConcurrentHttpRequests() throws Exception {
        UUID id = UUID.randomUUID();
        var request = new CreateProviderPaymentRequest(id, 1000, "USD");
        assertProblem(send("POST", "/payments", Map.of(), request), 401, "INVALID_PROVIDER_KEY");
        List<Callable<Void>> tasks = new ArrayList<>();
        List<String> ids = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                var response = send("POST", "/payments", Map.of("X-Provider-Key", "provider-test-key"), request);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                ids.add(json(response).get("providerPaymentId").asString());
                return null;
            });
        }
        concurrently(tasks);
        assertThat(ids).hasSize(20).containsOnly(ids.getFirst());
        assertProblem(send("POST", "/payments", Map.of("X-Provider-Key", "provider-test-key"),
                new CreateProviderPaymentRequest(id, 2000, "USD")), 409, "PAYMENT_ID_CONFLICT");
        assertThat(provider.findByPaymentId(id).providerPaymentId()).isEqualTo(ids.getFirst());
        assertProblem(send("GET", "/payments/" + id, Map.of("X-Provider-Key", "wrong"), null),
                401, "INVALID_PROVIDER_KEY");
    }

    private Pending topUp(long amount) throws Exception {
        String key = UUID.randomUUID().toString();
        HttpResponse<String> response = send("POST", "/top-ups", userHeaders(owner, key), Map.of("amount", amount));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        UUID transactionId = UUID.fromString(body.get("transactionId").asString());
        UUID paymentId = jdbc.queryForObject("SELECT payment_id FROM flowpay.provider_payments WHERE transaction_id = ?",
                UUID.class, transactionId);
        return new Pending(paymentId, transactionId, body.get("reference").asString(), key);
    }

    private void completeAtProvider(Pending pending, PaymentProviderStatus status) {
        jdbc.update("UPDATE mock_provider.payments SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                status.name(), pending.paymentId());
    }

    private void due(Pending pending) {
        jdbc.update("UPDATE flowpay.provider_payments SET next_check_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                pending.paymentId());
    }

    private void assertState(Pending pending, String status, long balance, long ledgerCount) {
        assertThat(jdbc.queryForObject("SELECT status FROM flowpay.transactions WHERE transaction_id = ?",
                String.class, pending.transactionId())).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT balance FROM flowpay.wallets WHERE wallet_id = ?",
                Long.class, owner.walletId())).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flowpay.ledger_entries WHERE transaction_id = ?",
                Long.class, pending.transactionId())).isEqualTo(ledgerCount);
    }

    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String password = "processing-password";
        var registered = send("POST", "/auth/register", Map.of(),
                Map.of("username", "Processing test", "email", email, "password", password));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        var login = send("POST", "/auth/login", Map.of(), Map.of("email", email, "password", password));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String token = json(login).get("accessToken").asString();
        var wallet = send("POST", "/wallets", Map.of("Authorization", "Bearer " + token), null);
        assertThat(wallet.statusCode()).as(wallet.body()).isEqualTo(201);
        return new Account(UUID.fromString(json(wallet).get("walletId").asString()), token);
    }

    private Map<String, String> userHeaders(Account account, String key) {
        return Map.of("Authorization", "Bearer " + account.token(), "Idempotency-Key", key);
    }

    private HttpResponse<String> callback(ProviderPaymentResult result, String key) throws Exception {
        return send("POST", "/provider-callbacks/mock", key == null ? Map.of() : Map.of("X-Callback-Key", key), result);
    }

    private HttpResponse<String> get(String path, Account account) throws Exception {
        return send("GET", path, Map.of("Authorization", "Bearer " + account.token()), null);
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> headers, Object body) throws Exception {
        var builder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .uri(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path));
        headers.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }

    private void concurrently(List<Callable<Void>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent requests did not start in time.");
                }
                return task.call();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (var future : futures) {
                future.get(45, TimeUnit.SECONDS);
            }
        }
    }

    private record Account(UUID walletId, String token) {
    }

    private record Pending(UUID paymentId, UUID transactionId, String reference, String key) {
    }
}
