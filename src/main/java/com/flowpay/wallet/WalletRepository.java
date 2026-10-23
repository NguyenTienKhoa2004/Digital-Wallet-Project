package com.flowpay.wallet;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

	boolean existsByUserId(UUID userId);

}
