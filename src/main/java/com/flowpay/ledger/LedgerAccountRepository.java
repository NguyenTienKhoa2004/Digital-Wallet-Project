package com.flowpay.ledger;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByWalletId(UUID walletId);

    Optional<LedgerAccount> findByAccountCode(String accountCode);
}
