package com.flowpay.payment;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderPaymentRepository
        extends JpaRepository<ProviderPayment, UUID> {

    Optional<ProviderPayment> findByTransactionId(UUID transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProviderPayment p where p.id = :id")
    Optional<ProviderPayment> findByIdForUpdate(@Param("id") UUID id);
}
