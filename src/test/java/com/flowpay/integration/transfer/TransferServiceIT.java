package com.flowpay.integration.transfer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.flowpay.auth.AuthService;
import com.flowpay.auth.dto.RegisterRequest;
import com.flowpay.common.exception.ApiException;
import com.flowpay.ledger.LedgerEntry;
import com.flowpay.ledger.LedgerEntryRepository;
import com.flowpay.support.TestcontainersConfiguration;
import com.flowpay.transfer.TransferService;
import com.flowpay.transfer.dto.TransferRequest;
import com.flowpay.transfer.dto.TransferResponse;
import com.flowpay.wallet.WalletService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Timeout(90)
class TransferServiceIT {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AuthService authService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    private UUID senderId;
    private UUID receiverId;
    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void createWallets() {
        senderId = createUser();
        receiverId = createUser();
        sourceId = walletService.createWallet(senderId).walletId();
        destinationId = walletService.createWallet(receiverId).walletId();
        // Fixtures only: transfers are tested independently of the future top-up module.
        setBalance(sourceId, 10000);
        setBalance(destinationId, 5000);
    }

    @Test
    void commitsBalancesTransactionAndMatchingDoubleEntryLedger() {
        TransferResponse response = transfer(
                senderId, new TransferRequest(destinationId, 3000, "Dinner"));

        assertThat(response.transactionId()).isNotNull();
        assertThat(response.reference()).startsWith("TRF-");
        assertThat(response.sourceWalletId()).isEqualTo(sourceId);
        assertThat(response.destinationWalletId()).isEqualTo(destinationId);
        assertThat(response.amount()).isEqualTo(3000);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.description()).isEqualTo("Dinner");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.completedAt()).isAfterOrEqualTo(response.createdAt());
        assertThat(balanceOf(sourceId)).isEqualTo(7000);
        assertThat(balanceOf(destinationId)).isEqualTo(8000);
        assertThat(balanceOf(sourceId) + balanceOf(destinationId)).isEqualTo(15000);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT * FROM flowpay.transactions WHERE transaction_id = ?", response.transactionId());
        assertThat(stored).containsEntry("reference", response.reference())
                .containsEntry("initiated_by", senderId)
                .containsEntry("type", "TRANSFER")
                .containsEntry("currency", "USD")
                .containsEntry("description", "Dinner")
                .containsEntry("failure_code", null);
        assertCommittedTransfers(1);
    }

    @Test
    void allowsTransferringTheEntireAvailableBalance() {
        transfer(senderId, new TransferRequest(destinationId, 10000, "Entire balance"));

        assertThat(balanceOf(sourceId)).isZero();
        assertThat(balanceOf(destinationId)).isEqualTo(15000);
        assertCommittedTransfers(1);
    }

    @Test
    void acceptsAnOptionalDescription() {
        TransferResponse response = transfer(
                senderId, new TransferRequest(destinationId, 3000, null));

        assertThat(response.description()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM flowpay.transactions WHERE transaction_id = ?",
                String.class, response.transactionId())).isNull();
        assertCommittedTransfers(1);
    }

    @Test
    void rejectsInsufficientBalanceWithoutFinancialEffects() {
        assertRejected(senderId, new TransferRequest(destinationId, 10001, null),
                HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE");
        assertFailedTransfer("INSUFFICIENT_BALANCE", 10000, 5000);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveAmountsWithoutFinancialEffects(long amount) {
        assertRejected(senderId, new TransferRequest(destinationId, amount, null),
                HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
        assertNoFinancialEffects(10000, 5000);
    }

    @Test
    void rejectsSelfTransferWithoutFinancialEffects() {
        assertRejected(senderId, new TransferRequest(sourceId, 3000, null),
                HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED");
        assertNoFinancialEffects(10000, 5000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsAFrozenSourceOrDestination(boolean freezeSource) {
        UUID frozenId = freezeSource ? sourceId : destinationId;
        jdbcTemplate.update("""
                UPDATE flowpay.wallets SET status = 'FROZEN', updated_at = CURRENT_TIMESTAMP
                WHERE wallet_id = ?
                """, frozenId);

        assertRejected(senderId, new TransferRequest(destinationId, 3000, null), HttpStatus.CONFLICT,
                freezeSource ? "SOURCE_WALLET_NOT_ACTIVE" : "DESTINATION_WALLET_NOT_ACTIVE");
        assertNoFinancialEffects(10000, 5000);
    }

    @Test
    void rejectsAUserWithoutASourceWallet() {
        UUID userWithoutWallet = createUser();

        assertRejected(userWithoutWallet, new TransferRequest(destinationId, 3000, null),
                HttpStatus.NOT_FOUND, "SOURCE_WALLET_NOT_FOUND");
        assertNoFinancialEffects(10000, 5000);
    }

    @Test
    void rejectsAMissingDestinationWithoutFinancialEffects() {
        assertRejected(senderId, new TransferRequest(UUID.randomUUID(), 3000, null),
                HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND");
        assertNoFinancialEffects(10000, 5000);
    }

    @Test
    void aDifferentUserCannotSpendTheSendersBalance() {
        setBalance(destinationId, 0);

        assertRejected(receiverId, new TransferRequest(sourceId, 3000, null),
                HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE");
        assertFailedTransfer("INSUFFICIENT_BALANCE", 10000, 0);
    }

    @Test
    void aMissingLedgerAccountDoesNotMoveMoney() {
        jdbcTemplate.update("DELETE FROM flowpay.ledger_accounts WHERE wallet_id = ?", destinationId);

        assertThatThrownBy(() -> transfer(
                senderId, new TransferRequest(destinationId, 3000, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ledger account is missing");
        assertNoFinancialEffects(10000, 5000);
    }

    @Test
    void destinationBalanceOverflowFailsWithoutMovingMoney() {
        setBalance(destinationId, Long.MAX_VALUE);
        WalletSnapshot sourceBefore = snapshot(sourceId);
        WalletSnapshot destinationBefore = snapshot(destinationId);

        assertRejected(senderId, new TransferRequest(destinationId, 1, null),
                HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED");

        assertFailedTransfer("BALANCE_LIMIT_EXCEEDED", 10000, Long.MAX_VALUE);
        assertThat(snapshot(sourceId)).isEqualTo(sourceBefore);
        assertThat(snapshot(destinationId)).isEqualTo(destinationBefore);
    }

    @Test
    void failureAfterRealLedgerFlushRollsBackAlreadyExecutedSql() {
        WalletSnapshot sourceBefore = snapshot(sourceId);
        WalletSnapshot destinationBefore = snapshot(destinationId);
        doAnswer(invocation -> {
            // Repository methods are abstract on the Spring Data proxy. Persist the supplied entries
            // through the real EntityManager, then fail after SQL has executed in the service transaction.
            Iterable<LedgerEntry> entries = invocation.getArgument(0);
            entries.forEach(entityManager::persist);
            entityManager.flush();
            // JdbcTemplate participates in the service transaction: these reads prove SQL really ran.
            assertThat(balanceOf(sourceId)).isEqualTo(7000);
            assertThat(balanceOf(destinationId)).isEqualTo(8000);
            assertThat(ledgerEntryCount()).isEqualTo(2);
            assertThat(transactions()).singleElement().satisfies(transaction ->
                    assertThat(transaction).containsEntry("status", "PENDING"));
            throw new IllegalStateException("Simulated failure after flush");
        }).when(ledgerEntryRepository).saveAllAndFlush(any());

        assertThatThrownBy(() -> transfer(
                senderId, new TransferRequest(destinationId, 3000, "Rollback test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure after flush");

        // These reads occur after the service transaction has rolled back.
        assertNoFinancialEffects(10000, 5000);
        assertThat(snapshot(sourceId)).isEqualTo(sourceBefore);
        assertThat(snapshot(destinationId)).isEqualTo(destinationBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.idempotency_requests WHERE user_id = ?
                """, Long.class, senderId)).isZero();
    }

    @Test
    void twoConcurrentTransfersCannotBothSpendTheSameBalance() throws Exception {
        List<String> results = concurrently(List.of(
                () -> attemptTransfer(senderId, destinationId, 8000),
                () -> attemptTransfer(senderId, destinationId, 8000)));

        assertThat(results).containsExactlyInAnyOrder("SUCCESS", "INSUFFICIENT_BALANCE");
        assertThat(balanceOf(sourceId)).isEqualTo(2000);
        assertThat(balanceOf(destinationId)).isEqualTo(13000);
        assertThat(transactions()).hasSize(2);
        assertCommittedTransfers(1);
    }

    @Test
    void oneHundredConcurrentTransfersCommitExactlyFiftyWithoutOverspending() throws Exception {
        List<Callable<String>> requests = IntStream.range(0, 100)
                .mapToObj(index -> (Callable<String>) () -> attemptTransfer(senderId, destinationId, 200))
                .toList();

        List<String> results = concurrently(requests);

        assertThat(results).filteredOn("SUCCESS"::equals).hasSize(50);
        assertThat(results).filteredOn("INSUFFICIENT_BALANCE"::equals).hasSize(50);
        assertThat(balanceOf(sourceId)).isZero();
        assertThat(balanceOf(destinationId)).isEqualTo(15000);
        assertThat(transactions()).hasSize(100);
        assertCommittedTransfers(50);
    }

    @Test
    void oppositeDirectionTransfersFinishWithoutDeadlockOrLostUpdates() throws Exception {
        setBalance(destinationId, 10000);
        List<String> results = concurrently(List.of(
                () -> attemptTransfer(senderId, destinationId, 8000),
                () -> attemptTransfer(receiverId, sourceId, 8000)));

        assertThat(results).containsExactly("SUCCESS", "SUCCESS");
        assertThat(balanceOf(sourceId)).isEqualTo(10000);
        assertThat(balanceOf(destinationId)).isEqualTo(10000);
        assertCommittedTransfers(2);
    }

    private TransferResponse transfer(UUID userId, TransferRequest request) {
        return transferService.transfer(userId, UUID.randomUUID().toString(), request);
    }

    private UUID createUser() {
        return authService.register(new RegisterRequest(
                "Transfer test", UUID.randomUUID() + "@example.com", "transfer-test-password")).userId();
    }

    private void setBalance(UUID walletId, long balance) {
        jdbcTemplate.update("""
                UPDATE flowpay.wallets SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE wallet_id = ?
                """, balance, walletId);
    }

    private long balanceOf(UUID walletId) {
        return snapshot(walletId).balance();
    }

    private WalletSnapshot snapshot(UUID walletId) {
        return jdbcTemplate.queryForObject("""
                SELECT balance, updated_at FROM flowpay.wallets WHERE wallet_id = ?
                """, (row, index) -> new WalletSnapshot(
                row.getLong("balance"), row.getTimestamp("updated_at").toInstant()), walletId);
    }

    private List<Map<String, Object>> transactions() {
        return jdbcTemplate.queryForList("""
                SELECT transaction_id, source_wallet_id, destination_wallet_id, amount, status, completed_at
                FROM flowpay.transactions
                WHERE source_wallet_id IN (?, ?) OR destination_wallet_id IN (?, ?)
                """, sourceId, destinationId, sourceId, destinationId);
    }

    private long ledgerEntryCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flowpay.ledger_entries e
                JOIN flowpay.ledger_accounts a ON a.account_id = e.account_id
                WHERE a.wallet_id IN (?, ?)
                """, Long.class, sourceId, destinationId);
    }

    private void assertNoFinancialEffects(long sourceBalance, long destinationBalance) {
        assertThat(balanceOf(sourceId)).isEqualTo(sourceBalance);
        assertThat(balanceOf(destinationId)).isEqualTo(destinationBalance);
        assertThat(transactions()).isEmpty();
        assertThat(ledgerEntryCount()).isZero();
    }

    private void assertCommittedTransfers(int expectedCount) {
        List<Map<String, Object>> stored = transactions();
        assertThat(stored).filteredOn(row -> "SUCCESS".equals(row.get("status"))).hasSize(expectedCount);
        assertThat(ledgerEntryCount()).isEqualTo(expectedCount * 2L);
        for (Map<String, Object> transaction : stored) {
            assertThat(transaction.get("status")).isIn("SUCCESS", "FAILED");
            assertThat(transaction.get("completed_at")).isNotNull();
            List<Map<String, Object>> entries = jdbcTemplate.queryForList("""
                    SELECT a.wallet_id, e.entry_type, e.amount
                    FROM flowpay.ledger_entries e
                    JOIN flowpay.ledger_accounts a ON a.account_id = e.account_id
                    WHERE e.transaction_id = ?
                    """, transaction.get("transaction_id"));
            if ("FAILED".equals(transaction.get("status"))) {
                assertThat(entries).isEmpty();
                continue;
            }
            assertThat(entries).containsExactlyInAnyOrder(
                    Map.of("wallet_id", transaction.get("source_wallet_id"),
                            "entry_type", "DEBIT", "amount", transaction.get("amount")),
                    Map.of("wallet_id", transaction.get("destination_wallet_id"),
                            "entry_type", "CREDIT", "amount", transaction.get("amount")));
        }
    }

    private void assertFailedTransfer(String code, long sourceBalance, long destinationBalance) {
        assertThat(balanceOf(sourceId)).isEqualTo(sourceBalance);
        assertThat(balanceOf(destinationId)).isEqualTo(destinationBalance);
        assertThat(ledgerEntryCount()).isZero();
        assertThat(transactions()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "FAILED");
            assertThat(row.get("completed_at")).isNotNull();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT failure_code FROM flowpay.transactions WHERE transaction_id = ?
                    """, String.class, row.get("transaction_id"))).isEqualTo(code);
        });
    }

    private void assertRejected(UUID userId, TransferRequest request, HttpStatus status, String code) {
        assertThatThrownBy(() -> transfer(userId, request))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }

    private String attemptTransfer(UUID userId, UUID receivingWalletId, long amount) {
        try {
            return transfer(userId, new TransferRequest(receivingWalletId, amount, null)).status();
        } catch (ApiException exception) {
            if (!"INSUFFICIENT_BALANCE".equals(exception.getCode())) {
                throw exception;
            }
            return exception.getCode();
        }
    }

    private List<String> concurrently(List<Callable<String>> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = requests.stream().map(request -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test did not start in time.");
                }
                return request.call();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<String> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(45, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private record WalletSnapshot(long balance, Instant updatedAt) {
    }
}
