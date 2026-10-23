package com.flowpay.wallet;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

	boolean existsByUserId(UUID userId);

	Optional<Wallet> findByUserId(UUID userId);

}
