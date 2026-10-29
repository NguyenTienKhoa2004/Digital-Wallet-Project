package com.flowpay.transaction;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository
        extends JpaRepository<WalletTransaction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WalletTransaction t where t.id = :id")
    Optional<WalletTransaction> findByIdForUpdate(@Param("id") UUID id);

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

