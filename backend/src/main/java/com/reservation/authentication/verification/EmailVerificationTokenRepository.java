package com.reservation.authentication.verification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

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
}