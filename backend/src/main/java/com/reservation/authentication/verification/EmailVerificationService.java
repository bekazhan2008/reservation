package com.reservation.authentication.verification;

import com.reservation.notifications.VerificationEmailRequested;
import com.reservation.users.User;
import com.reservation.users.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
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

    static final Duration RESEND_COOLDOWN =
            Duration.ofSeconds(60);

    private final VerificationTokenGenerator tokenGenerator;
    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EmailVerificationService(
            VerificationTokenGenerator tokenGenerator,
            EmailVerificationTokenRepository tokenRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.tokenGenerator = tokenGenerator;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    // Ничего не сообщает вызывающему о результате: ответ API одинаков для любого email.
    @Transactional
    public void resendVerification(String normalizedEmail) {
        // Блокировка строки пользователя сериализует параллельные запросы для одного аккаунта.
        User user = userRepository.findByNormalizedEmailForUpdate(normalizedEmail)
                .orElse(null);

        if (user == null
                || user.isEmailVerified()
                || !user.isEnabled()
                || user.getDeletedAt() != null) {
            return;
        }

        Instant now = Instant.now();

        boolean coolingDown = tokenRepository.findLatestCreatedAt(user.getId())
                .filter(createdAt -> createdAt.plus(RESEND_COOLDOWN).isAfter(now))
                .isPresent();

        if (coolingDown) {
            return;
        }

        tokenRepository.invalidateUnused(user.getId(), now);
        String rawToken = createToken(user);

        eventPublisher.publishEvent(
                new VerificationEmailRequested(user.getEmail(), rawToken)
        );
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

    @Transactional(readOnly = true)
    public boolean isTokenActive(String rawToken) {
        String tokenHash = hashToken(rawToken);

        return tokenRepository.findByTokenHash(tokenHash)
                .filter(token -> token.getUsedAt() == null)
                .filter(token -> !token.isExpired())
                .filter(token -> token.getUser().isEnabled())
                .filter(token -> token.getUser().getDeletedAt() == null)
                .filter(token -> !token.getUser().isEmailVerified())
                .isPresent();
    }
}