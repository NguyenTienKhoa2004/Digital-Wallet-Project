package com.flowpay.unit.wallet;

import java.util.Optional;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.ledger.LedgerAccount;
import com.flowpay.ledger.LedgerAccountRepository;
import com.flowpay.user.User;
import com.flowpay.user.UserRepository;
import com.flowpay.wallet.Wallet;
import com.flowpay.wallet.WalletRepository;
import com.flowpay.wallet.WalletService;
import com.flowpay.wallet.WalletStatus;
import com.flowpay.wallet.dto.WalletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private WalletRepository walletRepository;

	@Mock
	private LedgerAccountRepository ledgerAccountRepository;

	@InjectMocks
	private WalletService walletService;

	private final UUID userId = UUID.randomUUID();

	@Test
	void rejectsMissingUserWithoutSavingWalletOrLedgerAccount() {
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> walletService.createWallet(userId))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(exception.getCode()).isEqualTo("USER_NOT_FOUND");
				});

		verifyNoInteractions(walletRepository, ledgerAccountRepository);
	}

	@Test
	void rejectsExistingWalletWithoutSavingWalletOrLedgerAccount() {
		givenExistingUser();
		when(walletRepository.existsByUserId(userId)).thenReturn(true);

		assertThatThrownBy(() -> walletService.createWallet(userId))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(exception.getCode()).isEqualTo("WALLET_ALREADY_EXISTS");
				});

		verify(walletRepository).existsByUserId(userId);
		verifyNoMoreInteractions(walletRepository);
		verifyNoInteractions(ledgerAccountRepository);
	}

	@Test
	void createsZeroBalanceActiveWalletWithMatchingLedgerAccountAndResponse() {
		givenExistingUser();
		when(walletRepository.existsByUserId(userId)).thenReturn(false);
		UUID walletId = UUID.randomUUID();
		when(walletRepository.saveAndFlush(any(Wallet.class))).thenAnswer(invocation -> {
			Wallet wallet = invocation.getArgument(0);
			ReflectionTestUtils.setField(wallet, "id", walletId);
			return wallet;
		});

		WalletResponse response = walletService.createWallet(userId);

		ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
		verify(walletRepository).saveAndFlush(walletCaptor.capture());
		Wallet wallet = walletCaptor.getValue();
		assertThat(wallet).hasFieldOrPropertyWithValue("userId", userId);
		assertThat(wallet.getBalance()).isZero();
		assertThat(wallet.getCurrency()).isEqualTo("VND");
		assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
		assertThat(wallet.getCreatedAt()).isNotNull();
		assertThat(wallet.getUpdatedAt()).isEqualTo(wallet.getCreatedAt());

		ArgumentCaptor<LedgerAccount> accountCaptor = ArgumentCaptor.forClass(LedgerAccount.class);
		verify(ledgerAccountRepository).saveAndFlush(accountCaptor.capture());
		assertThat(accountCaptor.getValue())
				.hasFieldOrPropertyWithValue("walletId", walletId)
				.hasFieldOrPropertyWithValue("accountCode", "WALLET_" + walletId)
				.hasFieldOrPropertyWithValue("purpose", "WALLET")
				.hasFieldOrPropertyWithValue("accountingType", "LIABILITY")
				.hasFieldOrPropertyWithValue("currency", "VND")
				.hasFieldOrPropertyWithValue("createdAt", wallet.getCreatedAt());

		assertThat(response).isEqualTo(new WalletResponse(walletId, 0, "VND", WalletStatus.ACTIVE,
				wallet.getCreatedAt(), wallet.getUpdatedAt()));
	}

	private void givenExistingUser() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(userId);
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
	}

}
