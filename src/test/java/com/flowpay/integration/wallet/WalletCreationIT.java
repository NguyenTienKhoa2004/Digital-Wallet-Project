package com.flowpay.integration.wallet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.flowpay.auth.AuthService;
import com.flowpay.auth.dto.LoginRequest;
import com.flowpay.auth.dto.RegisterRequest;
import com.flowpay.ledger.LedgerAccount;
import com.flowpay.ledger.LedgerAccountRepository;
import com.flowpay.support.TestcontainersConfiguration;
import com.flowpay.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class WalletCreationIT {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Environment environment;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private WalletService walletService;

	@Autowired
	private AuthService authService;

	@MockitoSpyBean
	private LedgerAccountRepository ledgerAccountRepository;

	private UUID userId;
	private String accessToken;

	@BeforeEach
	void createAuthenticatedUser() {
		String email = UUID.randomUUID() + "@example.com";
		String password = "wallet-test-password";
		userId = authService.register(new RegisterRequest("Wallet test", email, password)).userId();
		accessToken = authService.login(new LoginRequest(email, password)).accessToken();
	}

	@Test
	void createsZeroBalanceUsdWalletAndMatchingLedgerAccount() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = createWallet(client, authorization());
			assertThat(response.statusCode()).isEqualTo(201);
			JsonNode body = objectMapper.readTree(response.body());
			UUID walletId = UUID.fromString(body.get("walletId").asString());
			assertThat(body.get("balance").asLong()).isZero();
			assertThat(body.get("currency").asString()).isEqualTo("USD");
			assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
			assertThat(body.get("createdAt").asString()).isNotBlank();
			assertThat(body.get("updatedAt").asString()).isNotBlank();
			assertThat(jdbcTemplate.queryForObject(
					"SELECT user_id FROM flowpay.wallets WHERE wallet_id = ?", UUID.class, walletId)).isEqualTo(userId);
			assertThat(jdbcTemplate.queryForMap("""
					SELECT purpose, accounting_type, currency, account_code
					FROM flowpay.ledger_accounts WHERE wallet_id = ?
					""", walletId)).containsAllEntriesOf(java.util.Map.of(
					"purpose", "WALLET", "accounting_type", "LIABILITY",
					"currency", "USD", "account_code", "WALLET_" + walletId));
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM flowpay.ledger_entries", Integer.class)).isZero();
		}
	}

	@Test
	void repeatedCreationReturnsConflictWithoutCreatingAnotherAccount() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			assertThat(createWallet(client, authorization()).statusCode()).isEqualTo(201);
			HttpResponse<String> response = createWallet(client, authorization());
			assertThat(response.statusCode()).isEqualTo(409);
			assertThat(objectMapper.readTree(response.body()).get("code").asString()).isEqualTo("WALLET_ALREADY_EXISTS");
			assertSingleWalletAndAccount();
		}
	}

	@Test
	void concurrentCreationCommitsOnlyOneWalletAndAccount() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (HttpClient client = HttpClient.newHttpClient();
				var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = executor.submit(() -> {
				start.await();
				return createWallet(client, authorization()).statusCode();
			});
			var second = executor.submit(() -> {
				start.await();
				return createWallet(client, authorization()).statusCode();
			});
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(201, 409);
			assertSingleWalletAndAccount();
		}
	}

	@Test
	void failureCreatingLedgerAccountRollsBackAlreadyFlushedWallet() {
		doThrow(new IllegalStateException("Simulated ledger failure"))
				.when(ledgerAccountRepository).saveAndFlush(any(LedgerAccount.class));
		assertThatThrownBy(() -> walletService.createWallet(userId))
				.isInstanceOf(IllegalStateException.class).hasMessage("Simulated ledger failure");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flowpay.wallets WHERE user_id = ?", Integer.class, userId)).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM flowpay.ledger_accounts a
				JOIN flowpay.wallets w ON a.wallet_id = w.wallet_id WHERE w.user_id = ?
				""", Integer.class, userId)).isZero();
	}

	@Test
	void missingOrInvalidTokenCannotCreateWallet() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> missing = createWallet(client, null);
			assertThat(missing.statusCode()).isEqualTo(401);
			assertThat(missing.headers().firstValue("WWW-Authenticate")).isPresent();
			assertThat(createWallet(client, "Bearer invalid-token").statusCode()).isEqualTo(401);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM flowpay.wallets WHERE user_id = ?", Integer.class, userId)).isZero();
		}
	}

	private void assertSingleWalletAndAccount() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flowpay.wallets WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM flowpay.ledger_accounts a
				JOIN flowpay.wallets w ON a.wallet_id = w.wallet_id WHERE w.user_id = ?
				""", Integer.class, userId)).isEqualTo(1);
	}

	private String authorization() {
		return "Bearer " + accessToken;
	}

	private HttpResponse<String> createWallet(HttpClient client, String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + "/wallets"))
				.timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody());
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
