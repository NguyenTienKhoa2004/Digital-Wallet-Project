package com.flowpay.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flowpay.wallet.WalletStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	// Serialize wallet creation for the same user, including concurrent requests.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") UUID id);

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	@Query("""
			select w.id, u.username, u.email, w.currency
			from User u, Wallet w
			where w.userId = u.id
			  and u.id <> :currentUserId
			  and w.status = :status
			  and w.currency = :currency
			  and (
			      u.email = :exactEmail
			      or lower(u.username) like :usernamePattern escape '!'
			  )
			order by lower(u.username), u.id
			""")
	List<RecipientCandidate> searchTransferRecipients(
			@Param("currentUserId") UUID currentUserId,
			@Param("status") WalletStatus status,
			@Param("currency") String currency,
			@Param("exactEmail") String exactEmail,
			@Param("usernamePattern") String usernamePattern,
			Pageable pageable);

}
