package com.reservation.authentication.verification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select t from EmailVerificationToken t
        where t.tokenHash = :tokenHash
        """)
    Optional<EmailVerificationToken> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    @Query("select max(t.createdAt) from EmailVerificationToken t where t.user.id = :userId")
    Optional<Instant> findLatestCreatedAt(@Param("userId") Long userId);

    // Помечает все неиспользованные токены пользователя использованными, чтобы старые ссылки перестали работать.
    @Modifying
    @Query("""
        update EmailVerificationToken t set t.usedAt = :now
        where t.user.id = :userId and t.usedAt is null
        """)
    int invalidateUnused(@Param("userId") Long userId, @Param("now") Instant now);
}
