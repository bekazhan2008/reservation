package com.reservation.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VerificationEmailEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(VerificationEmailEventListener.class);

    private final EmailJobPublisher emailJobPublisher;

    public VerificationEmailEventListener(
            EmailJobPublisher emailJobPublisher
    ) {
        this.emailJobPublisher = emailJobPublisher;
    }

    // Выполняется после коммита: пользователь и токен уже сохранены в БД.
    // Сбой Redis не должен превращать успешную регистрацию в HTTP 500, поэтому исключение только логируется.
    // Доставка письма НЕ гарантируется: нужен endpoint повторной отправки (resend-verification).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(
            VerificationEmailRequested event
    ) {
        try {
            emailJobPublisher.publishVerificationEmail(
                    event.email(),
                    event.rawToken()
            );
        } catch (RuntimeException e) {
            // Не логируем email, токен и сообщение исключения (оно может содержать данные задания).
            log.error("Failed to publish verification email job: {}",
                    e.getClass().getSimpleName());
        }
    }
}
