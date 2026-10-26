package com.flowpay.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.flowpay.support.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class FlowPayApplicationIT {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Flyway flyway;

	@Autowired
	private Environment environment;

	@Test
	void databaseIsReachableAndMigrationsAreApplied() {
		assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'flowpay'",
				Integer.class)).isEqualTo(1);
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'flowpay'",
				String.class)).containsExactlyInAnyOrder("users", "wallets", "transactions",
				"ledger_accounts", "ledger_entries", "provider_payments", "idempotency_requests",
				"reconciliation_checks");
	}

	@Test
	void walletConstraintsPreventDuplicateOwnershipNegativeBalancesAndReferencedUserDeletion() {
		UUID userId = createUser();
		jdbcTemplate.update("""
				INSERT INTO flowpay.wallets (user_id, currency, updated_at)
				VALUES (?, 'USD', CURRENT_TIMESTAMP)
				""", userId);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT balance FROM flowpay.wallets WHERE user_id = ?", Long.class, userId)).isZero();
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO flowpay.wallets (user_id, currency, updated_at)
				VALUES (?, 'USD', CURRENT_TIMESTAMP)
				""", userId)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_wallets_user");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE flowpay.wallets SET balance = -1 WHERE user_id = ?", userId))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_wallets_balance");
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM flowpay.users WHERE user_id = ?", userId))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_wallets_user");
	}

	@Test
	void transferCannotOmitWalletsAndCompletedIdempotencyCannotOmitResponse() {
		UUID userId = createUser();
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO flowpay.transactions
				(reference, initiated_by, type, amount, currency, status, updated_at)
				VALUES (?, ?, 'TRANSFER', 100, 'USD', 'PENDING', CURRENT_TIMESTAMP)
				""", UUID.randomUUID().toString(), userId))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_transactions_wallets");
		String key = UUID.randomUUID().toString();
		jdbcTemplate.update("""
				INSERT INTO flowpay.idempotency_requests
				(user_id, operation_type, idempotency_key, request_hash, status, updated_at)
				VALUES (?, 'TRANSFER', ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
				""", userId, key, "a".repeat(64));
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO flowpay.idempotency_requests
				(user_id, operation_type, idempotency_key, request_hash, status, updated_at)
				VALUES (?, 'TRANSFER', ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
				""", userId, key, "a".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_idempotency_requests_key");
		assertThatThrownBy(() -> jdbcTemplate.update("""
				UPDATE flowpay.idempotency_requests SET status = 'REJECTED'
				WHERE user_id = ? AND idempotency_key = ?
				""", userId, key)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_idempotency_requests_response");
	}

	private UUID createUser() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO flowpay.users (user_id, username, email, role)
				VALUES (?, 'Migration test', ?, 'USER')
				""", id, id + "@example.com");
		return id;
	}

	@Test
	void basicAuthenticationIsDisabled() throws Exception {
		String credentials = java.util.Base64.getEncoder().encodeToString(
				"ignored:ignored".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + "/wallets"))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Basic " + credentials)
				.POST(HttpRequest.BodyPublishers.noBody()).build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
		}
	}

	@Test
	void healthEndpointReportsUpWithPostgres() throws Exception {
		String port = environment.getRequiredProperty("local.server.port");
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/actuator/health"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		}
	}

}
