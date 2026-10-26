package com.flowpay.transaction;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository
        extends JpaRepository<WalletTransaction, UUID> {

    @Query("""
            SELECT t FROM WalletTransaction t
            WHERE t.type = :type AND t.reference = :reference
              AND (t.initiatedByUserId = :userId
                OR (t.status = :success AND t.destinationWalletId IN
                    (SELECT w.id FROM Wallet w WHERE w.userId = :userId)))
            """)
    Optional<WalletTransaction> findVisibleByReference(@Param("userId") UUID userId,
            @Param("reference") String reference, @Param("type") TransactionType type,
            @Param("success") TransactionStatus success);

    @Query("""
            SELECT t FROM WalletTransaction t
            WHERE t.type = :type
              AND (t.initiatedByUserId = :userId
                OR (t.status = :success AND t.destinationWalletId IN
                    (SELECT w.id FROM Wallet w WHERE w.userId = :userId)))
            """)
    Page<WalletTransaction> findVisibleHistory(@Param("userId") UUID userId,
            @Param("type") TransactionType type, @Param("success") TransactionStatus success, Pageable pageable);
}

