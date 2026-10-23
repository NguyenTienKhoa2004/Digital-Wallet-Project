package com.flowpay.wallet;

import java.time.Instant;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.ledger.LedgerAccount;
import com.flowpay.ledger.LedgerAccountRepository;
import com.flowpay.user.UserRepository;
import com.flowpay.user.User;
import com.flowpay.wallet.dto.WalletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

	private final UserRepository userRepository;
	private final WalletRepository walletRepository;
	private final LedgerAccountRepository ledgerAccountRepository;

	public WalletService(UserRepository userRepository, WalletRepository walletRepository,
			LedgerAccountRepository ledgerAccountRepository) {
		this.userRepository = userRepository;
		this.walletRepository = walletRepository;
		this.ledgerAccountRepository = ledgerAccountRepository;
	}

	@Transactional
	public WalletResponse createWallet(UUID authenticatedUserId) {
		User user = userRepository.findByIdForUpdate(authenticatedUserId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist."));
		if (walletRepository.existsByUserId(user.getId())) {
			throw new ApiException(HttpStatus.CONFLICT, "WALLET_ALREADY_EXISTS", "User already has a wallet.");
		}

		Instant now = Instant.now();
		// Flush the wallet first because the ledger account references its ID.
		// Both inserts still belong to this transaction and roll back together.
		Wallet wallet = walletRepository.saveAndFlush(Wallet.create(user.getId(), now));
		ledgerAccountRepository.saveAndFlush(LedgerAccount.forWallet(wallet.getId(), now));
		return WalletResponse.from(wallet);
	}

}
