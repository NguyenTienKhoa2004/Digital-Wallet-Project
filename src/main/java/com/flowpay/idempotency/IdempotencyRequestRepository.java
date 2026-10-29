package com.flowpay.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO flowpay.idempotency_requests
                (user_id, operation_type, idempotency_key, request_hash, status, created_at, updated_at)
            VALUES (:userId, 'TRANSFER', :key, :hash, 'PROCESSING', :now, :now)
            ON CONFLICT (user_id, operation_type, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    // Returns 1 when a new request is inserted, or 0 when the key already exists.
    int insertTransferRequestIfAbsent(@Param("userId") UUID userId, @Param("key") String key,
            @Param("hash") String hash, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM IdempotencyRequest r
            WHERE r.userId = :userId AND r.operationType = :operation AND r.idempotencyKey = :key
            """)
    Optional<IdempotencyRequest> findForUpdate(@Param("userId") UUID userId,
            @Param("operation") IdempotencyOperation operation, @Param("key") String key);

    Optional<IdempotencyRequest> findByUserIdAndOperationTypeAndIdempotencyKey(
            UUID userId, IdempotencyOperation operationType, String idempotencyKey);

    @Modifying
        @Query(value = """
                INSERT INTO flowpay.idempotency_requests
                (user_id, operation_type, idempotency_key,
                request_hash, status, created_at, updated_at)
                VALUES (:userId, 'TOP_UP', :key, :hash,
                        'PROCESSING', :now, :now)
                ON CONFLICT (user_id, operation_type, idempotency_key)
                DO NOTHING
                """, nativeQuery = true)
        int insertTopUpRequestIfAbsent(
                @Param("userId") UUID userId,
                @Param("key") String key,
                @Param("hash") String hash,
                @Param("now") Instant now);
}
