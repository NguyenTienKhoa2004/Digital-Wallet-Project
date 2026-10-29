package com.flowpay.wallet;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

	boolean existsByUserId(UUID userId);

	Optional<Wallet> findByUserId(UUID userId);
	@Query("select w.id from Wallet w where w.userId = :userId")
	Optional<UUID> findWalletIdByUserId(@Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select w from Wallet w where w.id = :walletId")
	Optional<Wallet> findByIdForUpdate(@Param("walletId") UUID walletId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);

}
