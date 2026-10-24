package com.reservation.authentication.verification;

import com.reservation.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class EmailVerificationService {

    private static final Duration TOKEN_LIFETIME =
            Duration.ofHours(24);

    private final VerificationTokenGenerator tokenGenerator;
    private final EmailVerificationTokenRepository tokenRepository;

    public EmailVerificationService(
            VerificationTokenGenerator tokenGenerator,
            EmailVerificationTokenRepository tokenRepository
    ) {
        this.tokenGenerator = tokenGenerator;
        this.tokenRepository = tokenRepository;
    }

    @Transactional
    public String createToken(User user) {
        String rawToken = tokenGenerator.generate();

        String tokenHash = hashToken(rawToken);

        EmailVerificationToken verificationToken =
                new EmailVerificationToken(
                        user,
                        tokenHash,
                        Instant.now().plus(TOKEN_LIFETIME)
                );

        tokenRepository.save(verificationToken);

        return rawToken;
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        String tokenHash = hashToken(rawToken);

        EmailVerificationToken token = tokenRepository
                .findByTokenHashForUpdate(tokenHash)
                .orElseThrow(InvalidVerificationTokenException::new);

        if (token.getUsedAt() != null || token.isExpired()) {
            throw new InvalidVerificationTokenException();
        }

        User user = token.getUser();

        if (!user.isEnabled() || user.getDeletedAt() != null) {
            throw new IllegalStateException("Account is disabled");
        }

        user.verifyEmail();
        token.markUsed();
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm unavailable", e
            );
        }
    }
}