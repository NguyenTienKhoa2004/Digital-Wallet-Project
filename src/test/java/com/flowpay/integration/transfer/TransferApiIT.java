package com.flowpay.integration.transfer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.flowpay.idempotency.TransferRequestFingerprint;
import com.flowpay.ledger.LedgerEntry;
import com.flowpay.ledger.LedgerEntryRepository;
import com.flowpay.support.TestcontainersConfiguration;
import com.flowpay.transfer.dto.TransferRequest;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.application.name=flowpay-transfer-api-test"
})
@Timeout(90)
class TransferApiIT {

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private TransferRequestFingerprint fingerprint;

    private HttpClient client;
    private Account sender;
    private Account receiver;

    @BeforeEach
    void createAuthenticatedWalletsThroughHttp() throws Exception {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        sender = account();
        receiver = account();
        setBalance(sender, 10000);
        setBalance(receiver, 5000);
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void authenticatedTransferCommitsAndBothPartiesCanReadIt() throws Exception {
        HttpResponse<String> response = post(sender.token(), key(), payload(3000));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(body.get("currency").asString()).isEqualTo("USD");
        assertThat(body.get("amount").asLong()).isEqualTo(3000);
        assertThat(body.get("sourceWalletId").asString()).isEqualTo(sender.walletId().toString());
        assertThat(body.get("destinationWalletId").asString()).isEqualTo(receiver.walletId().toString());
        assertThat(balance(sender)).isEqualTo(7000);
        assertThat(balance(receiver)).isEqualTo(8000);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(2);
        String path = "/transfers/" + body.get("reference").asString();
        assertThat(get(sender.token(), path, null).statusCode()).isEqualTo(200);
        assertThat(get(receiver.token(), path, null).statusCode()).isEqualTo(200);
        assertThat(json(get(sender.token(), "/transfers", null)).get("items").size()).isEqualTo(1);
        assertThat(json(get(receiver.token(), "/transfers", null)).get("items").size()).isEqualTo(1);
    }

    @Test
    void retryReturnsTheOriginalResponseWithoutMovingMoneyAgain() throws Exception {
        String key = key();
        HttpResponse<String> first = post(sender.token(), key, payload(3000));
        // A retry must replay before inspecting the current wallet state.
        jdbcTemplate.update("UPDATE flowpay.wallets SET status = 'FROZEN' WHERE wallet_id = ?", sender.walletId());
        HttpResponse<String> retry = post(sender.token(), key, payload(3000));
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(json(retry)).isEqualTo(json(first));
        assertThat(balance(sender)).isEqualTo(7000);
        assertThat(balance(receiver)).isEqualTo(8000);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(keyCount(sender)).isEqualTo(1);
    }

    @Test
    void jsonPropertyOrderAndWhitespaceDoNotChangeRequestIdentity() throws Exception {
        String key = key();
        HttpResponse<String> first = post(sender.token(), key, payload(3000));
        String reordered = """
                { "description": "Dinner", "amount": 3000, "destinationWalletId": "%s" }
                """.formatted(receiver.walletId());
        HttpResponse<String> retry = post(sender.token(), key, reordered);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(json(retry)).isEqualTo(json(first));
        assertThat(transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "destination", "description"})
    void sameKeyWithDifferentBusinessFieldsIsRejected(String changed) throws Exception {
        String key = key();
        assertThat(post(sender.token(), key, payload(3000)).statusCode()).isEqualTo(201);
        String body = objectMapper.writeValueAsString(new TransferRequest(
                "destination".equals(changed) ? sender.walletId() : receiver.walletId(),
                "amount".equals(changed) ? 3001 : 3000,
                "description".equals(changed) ? "Other" : "Dinner"));

        assertProblem(post(sender.token(), key, body), 409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(balance(sender)).isEqualTo(7000);
        assertThat(balance(receiver)).isEqualTo(8000);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void manyConcurrentRetriesProduceExactlyOneTransfer() throws Exception {
        String key = key();
        String body = payload(3000);
        List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(() -> post(sender.token(), key, body));
        }
        List<HttpResponse<String>> responses = concurrently(requests);
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(json(response)).isEqualTo(json(responses.getFirst()));
        }
        assertThat(balance(sender)).isEqualTo(7000);
        assertThat(balance(receiver)).isEqualTo(8000);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(keyCount(sender)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadsWithOneKeyCannotBothExecute() throws Exception {
        String key = key();
        List<HttpResponse<String>> responses = concurrently(List.of(
                () -> post(sender.token(), key, payload(3000)),
                () -> post(sender.token(), key, payload(4000))));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        HttpResponse<String> success = responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
        long moved = json(success).get("amount").asLong();
        assertProblem(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(),
                409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(balance(sender)).isEqualTo(10000 - moved);
        assertThat(balance(receiver)).isEqualTo(5000 + moved);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    void concurrentIndependentRequestsCannotDoubleSpend() throws Exception {
        List<HttpResponse<String>> responses = concurrently(List.of(
                () -> post(sender.token(), key(), payload(8000)),
                () -> post(sender.token(), key(), payload(8000))));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        assertProblem(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(),
                409, "INSUFFICIENT_BALANCE");
        assertThat(balance(sender)).isEqualTo(2000);
        assertThat(balance(receiver)).isEqualTo(13000);
        assertThat(transactionCount()).isEqualTo(2);
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    void insufficientBalanceIsRecordedAndItsFailureIsReplayedAfterBalanceChanges() throws Exception {
        String key = key();
        HttpResponse<String> first = post(sender.token(), key, payload(10001));
        assertProblem(first, 409, "INSUFFICIENT_BALANCE");
        String reference = json(first).get("reference").asString();
        assertThat(balance(sender)).isEqualTo(10000);
        assertThat(balance(receiver)).isEqualTo(5000);
        assertThat(ledgerCount()).isZero();
        HttpResponse<String> lookup = get(sender.token(), "/transfers/" + reference, null);
        assertThat(lookup.statusCode()).isEqualTo(200);
        assertThat(json(lookup).get("status").asString()).isEqualTo("FAILED");
        assertThat(json(lookup).get("failureCode").asString()).isEqualTo("INSUFFICIENT_BALANCE");
        assertProblem(get(receiver.token(), "/transfers/" + reference, null), 404, "TRANSFER_NOT_FOUND");
        assertThat(json(get(receiver.token(), "/transfers", null)).get("items").isEmpty()).isTrue();

        setBalance(sender, 20000);
        HttpResponse<String> retry = post(sender.token(), key, payload(10001));
        assertThat(json(retry)).isEqualTo(json(first));
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(balance(sender)).isEqualTo(20000);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(post(sender.token(), key(), payload(10001)).statusCode()).isEqualTo(201);
        assertThat(balance(sender)).isEqualTo(9999);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void frozenWalletsDoNotMoveMoney(boolean source) throws Exception {
        jdbcTemplate.update("UPDATE flowpay.wallets SET status = 'FROZEN' WHERE wallet_id = ?",
                source ? sender.walletId() : receiver.walletId());
        assertProblem(post(sender.token(), key(), payload(3000)),
                409, source ? "SOURCE_WALLET_NOT_ACTIVE" : "DESTINATION_WALLET_NOT_ACTIVE");
        assertNoMoneyMoved();
        assertThat(transactionCount()).isZero();
    }

    @Test
    void selfTransferAndMissingWalletAreRejected() throws Exception {
        String self = objectMapper.writeValueAsString(new TransferRequest(sender.walletId(), 1, null));
        assertProblem(post(sender.token(), key(), self), 400, "SELF_TRANSFER_NOT_ALLOWED");
        String missing = objectMapper.writeValueAsString(new TransferRequest(UUID.randomUUID(), 1, null));
        assertProblem(post(sender.token(), key(), missing), 404, "WALLET_NOT_FOUND");
        assertNoMoneyMoved();
        assertThat(transactionCount()).isZero();
    }

    @Test
    void resultCanBeRecoveredByKeyWhenTheClientDidNotKeepThePostResponse() throws Exception {
        String key = key();
        assertThat(post(sender.token(), key, payload(3000)).statusCode()).isEqualTo(201);
        HttpResponse<String> recovered = get(sender.token(), "/transfers/by-idempotency-key", key);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(json(recovered).get("status").asString()).isEqualTo("SUCCESS");
        assertProblem(get(receiver.token(), "/transfers/by-idempotency-key", key), 404, "OPERATION_NOT_FOUND");
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void aSystemFailureAfterSqlRollsBackAndAllowsASafeRetryWithTheSameKey() throws Exception {
        String key = key();
        doAnswer(invocation -> {
            Iterable<LedgerEntry> entries = invocation.getArgument(0);
            entries.forEach(entityManager::persist);
            entityManager.flush();
            assertThat(balance(sender)).isEqualTo(7000);
            assertThat(balance(receiver)).isEqualTo(8000);
            assertThat(ledgerCount()).isEqualTo(2);
            throw new DataAccessResourceFailureException("Simulated connection failure after SQL");
        }).when(ledgerEntryRepository).saveAllAndFlush(any());

        assertProblem(post(sender.token(), key, payload(3000)), 503, "OPERATION_OUTCOME_UNKNOWN");
        assertNoMoneyMoved();
        assertThat(transactionCount()).isZero();
        assertThat(keyCount(sender)).isZero();
        assertProblem(get(sender.token(), "/transfers/by-idempotency-key", key), 404, "OPERATION_NOT_FOUND");

        reset(ledgerEntryRepository);
        assertThat(post(sender.token(), key, payload(3000)).statusCode()).isEqualTo(201);
        assertThat(balance(sender)).isEqualTo(7000);
        assertThat(balance(receiver)).isEqualTo(8000);
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(keyCount(sender)).isEqualTo(1);
    }

    @Test
    void unknownOrProcessingKeysAreNotReportedAsFailedTransfers() throws Exception {
        assertProblem(get(sender.token(), "/transfers/by-idempotency-key", key()), 404, "OPERATION_NOT_FOUND");
        String key = key();
        String hash = fingerprint.hash(new TransferRequest(receiver.walletId(), 3000, "Dinner"));
        jdbcTemplate.update("""
                INSERT INTO flowpay.idempotency_requests
                (user_id, operation_type, idempotency_key, request_hash, status, updated_at)
                VALUES (?, 'TRANSFER', ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                """, sender.userId(), key, hash);
        assertProblem(get(sender.token(), "/transfers/by-idempotency-key", key), 409, "IDEMPOTENCY_IN_PROGRESS");
        assertProblem(post(sender.token(), key, payload(3000)), 409, "IDEMPOTENCY_IN_PROGRESS");
        assertNoMoneyMoved();
        assertThat(transactionCount()).isZero();
    }

    @Test
    void keysAreScopedToTheUserAndOperationType() throws Exception {
        String key = key();
        jdbcTemplate.update("""
                INSERT INTO flowpay.idempotency_requests
                (user_id, operation_type, idempotency_key, request_hash, status, updated_at)
                VALUES (?, 'TOP_UP', ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                """, sender.userId(), key, "0".repeat(64));
        assertThat(post(sender.token(), key, payload(3000)).statusCode()).isEqualTo(201);
        String reverse = objectMapper.writeValueAsString(new TransferRequest(sender.walletId(), 3000, "Dinner"));
        assertThat(post(receiver.token(), key, reverse).statusCode()).isEqualTo(201);
        assertThat(balance(sender)).isEqualTo(10000);
        assertThat(balance(receiver)).isEqualTo(5000);
        assertThat(transactionCount()).isEqualTo(2);
    }

    @Test
    void unrelatedUsersCannotReadTransactionDetailsOrHistory() throws Exception {
        JsonNode transfer = json(post(sender.token(), key(), payload(3000)));
        Account outsider = account();
        assertProblem(get(outsider.token(), "/transfers/" + transfer.get("reference").asString(), null),
                404, "TRANSFER_NOT_FOUND");
        JsonNode history = json(get(outsider.token(), "/transfers", null));
        assertThat(history.get("items").isEmpty()).isTrue();
        assertThat(history.get("totalElements").asLong()).isZero();
    }

    @Test
    void theJwtSubjectDeterminesWhichWalletIsDebited() throws Exception {
        String body = objectMapper.writeValueAsString(new TransferRequest(sender.walletId(), 3000, null));
        HttpResponse<String> response = post(receiver.token(), key(), body);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).get("sourceWalletId").asString()).isEqualTo(receiver.walletId().toString());
        assertThat(balance(sender)).isEqualTo(13000);
        assertThat(balance(receiver)).isEqualTo(2000);
    }

    @Test
    void historyIsPaginatedAndUsesAStableTieBreaker() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(post(sender.token(), key(), payload(100)).statusCode()).isEqualTo(201);
        }
        jdbcTemplate.update("""
                UPDATE flowpay.transactions SET created_at = '2026-01-01T00:00:00Z' WHERE initiated_by = ?
                """, sender.userId());
        List<String> expected = jdbcTemplate.queryForList("""
                SELECT reference FROM flowpay.transactions WHERE initiated_by = ?
                ORDER BY created_at DESC, transaction_id DESC
                """, String.class, sender.userId());
        JsonNode first = json(get(sender.token(), "/transfers?page=0&size=2", null));
        JsonNode second = json(get(sender.token(), "/transfers?page=1&size=2", null));
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(List.of(
                first.get("items").get(0).get("reference").asString(),
                first.get("items").get(1).get("reference").asString(),
                second.get("items").get(0).get("reference").asString())).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc"})
    void invalidPaginationIsRejected(String query) throws Exception {
        assertThat(get(sender.token(), "/transfers?" + query, null).statusCode()).isEqualTo(400);
    }

    @Test
    void missingAndInvalidIdempotencyHeadersAreRejectedBeforeMoneyMoves() throws Exception {
        assertProblem(post(sender.token(), null, payload(3000)), 400, "MISSING_REQUIRED_HEADER");
        assertProblem(post(sender.token(), "", payload(3000)), 400, "INVALID_IDEMPOTENCY_KEY");
        assertProblem(post(sender.token(), "x".repeat(256), payload(3000)), 400, "INVALID_IDEMPOTENCY_KEY");
        assertNoMoneyMoved();
        assertThat(keyCount(sender)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"destinationWalletId\":null,\"amount\":1}",
            "{\"destinationWalletId\":\"%s\"}",
            "{\"destinationWalletId\":\"%s\",\"amount\":0}",
            "{\"destinationWalletId\":\"%s\",\"amount\":-1}",
            "{\"destinationWalletId\":\"%s\",\"amount\":1.5}",
            "{\"destinationWalletId\":\"%s\",\"amount\":9223372036854775808}",
            "{\"destinationWalletId\":\"not-a-uuid\",\"amount\":1}",
            "not-json"
    })
    void malformedOrInvalidRequestsDoNotConsumeAKeyOrMoveMoney(String template) throws Exception {
        HttpResponse<String> response = post(sender.token(), key(), template.formatted(receiver.walletId()));
        assertThat(response.statusCode()).isEqualTo(400);
        assertNoMoneyMoved();
        assertThat(keyCount(sender)).isZero();
        assertThat(transactionCount()).isZero();
    }

    @Test
    void optionalDescriptionIsAcceptedButOversizedDescriptionIsRejected() throws Exception {
        String large = objectMapper.writeValueAsString(new TransferRequest(receiver.walletId(), 1, "x".repeat(256)));
        assertProblem(post(sender.token(), key(), large), 400, "VALIDATION_FAILED");
        String absent = objectMapper.writeValueAsString(new TransferRequest(receiver.walletId(), 1, null));
        assertThat(post(sender.token(), key(), absent).statusCode()).isEqualTo(201);
    }

    @Test
    void missingInvalidAndTamperedTokensCannotMoveMoney() throws Exception {
        assertProblem(post(null, key(), payload(3000)), 401, "UNAUTHORIZED");
        assertProblem(post("invalid-token", key(), payload(3000)), 401, "UNAUTHORIZED");
        String[] pieces = sender.token().split("\\.");
        String signature = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
        assertProblem(post(pieces[0] + "." + pieces[1] + "." + signature, key(), payload(3000)), 401, "UNAUTHORIZED");
        assertNoMoneyMoved();
        assertThat(keyCount(sender)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "subject", "roles"})
    void jwtClaimsAreVerifiedBeforeTransfer(String invalidClaim) throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("issuer".equals(invalidClaim) ? "other" : "flowpay")
                .audience(List.of("audience".equals(invalidClaim) ? "other" : "flowpay-api"))
                .subject("subject".equals(invalidClaim) ? "not-a-uuid" : sender.userId().toString())
                .issuedAt(now.minusSeconds(600))
                .expiresAt("expired".equals(invalidClaim) ? now.minusSeconds(300) : now.plusSeconds(300))
                .claim("roles", List.of("roles".equals(invalidClaim) ? "ROOT" : "USER"))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        assertProblem(post(token, key(), payload(3000)), 401, "UNAUTHORIZED");
        assertNoMoneyMoved();
        assertThat(keyCount(sender)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/transfers", "/transfers/TRF-missing", "/transfers/by-idempotency-key"})
    void readEndpointsAlsoRequireAuthentication(String path) throws Exception {
        assertProblem(get(null, path, key()), 401, "UNAUTHORIZED");
    }

    @Test
    void officialLedgerEntriesCannotBeEditedOrDeletedWithSql() throws Exception {
        assertThat(post(sender.token(), key(), payload(3000)).statusCode()).isEqualTo(201);
        UUID entryId = jdbcTemplate.queryForObject("""
                SELECT e.entry_id FROM flowpay.ledger_entries e
                JOIN flowpay.ledger_accounts a ON a.account_id = e.account_id
                WHERE a.wallet_id = ?
                """, UUID.class, sender.walletId());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE flowpay.ledger_entries SET amount = 1 WHERE entry_id = ?", entryId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM flowpay.ledger_entries WHERE entry_id = ?", entryId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(ledgerCount()).isEqualTo(2);
    }

    private Account account() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String password = "transfer-api-password";
        HttpResponse<String> registered = send("POST", "/auth/register", null, null,
                objectMapper.writeValueAsString(Map.of("username", "API test", "email", email, "password", password)));
        assertThat(registered.statusCode()).isEqualTo(201);
        UUID userId = UUID.fromString(json(registered).get("userId").asString());
        HttpResponse<String> login = send("POST", "/auth/login", null, null,
                objectMapper.writeValueAsString(Map.of("email", email, "password", password)));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = json(login).get("accessToken").asString();
        HttpResponse<String> wallet = send("POST", "/wallets", token, null, null);
        assertThat(wallet.statusCode()).isEqualTo(201);
        return new Account(userId, UUID.fromString(json(wallet).get("walletId").asString()), token);
    }

    private HttpResponse<String> post(String token, String key, String body) throws Exception {
        return send("POST", "/transfers", token, key, body);
    }

    private HttpResponse<String> get(String token, String path, String key) throws Exception {
        return send("GET", path, token, key, null);
    }

    private HttpResponse<String> send(String method, String path, String token, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
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
        return objectMapper.writeValueAsString(new TransferRequest(receiver.walletId(), amount, "Dinner"));
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }

    private void setBalance(Account account, long balance) {
        jdbcTemplate.update("UPDATE flowpay.wallets SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE wallet_id = ?",
                balance, account.walletId());
    }

    private long balance(Account account) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM flowpay.wallets WHERE wallet_id = ?", Long.class, account.walletId());
    }

    private long transactionCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.transactions
                WHERE initiated_by IN (?, ?)
                """, Long.class, sender.userId(), receiver.userId());
    }

    private long ledgerCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.ledger_entries e
                JOIN flowpay.ledger_accounts a ON a.account_id = e.account_id
                WHERE a.wallet_id IN (?, ?)
                """, Long.class, sender.walletId(), receiver.walletId());
    }

    private long keyCount(Account account) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.idempotency_requests WHERE user_id = ?
                """, Long.class, account.userId());
    }

    private void assertNoMoneyMoved() {
        assertThat(balance(sender)).isEqualTo(10000);
        assertThat(balance(receiver)).isEqualTo(5000);
        assertThat(ledgerCount()).isZero();
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
