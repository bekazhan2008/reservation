package com.reservation.notifications;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VerificationEmailEventListener {

    private final EmailJobPublisher emailJobPublisher;

    public VerificationEmailEventListener(
            EmailJobPublisher emailJobPublisher
    ) {
        this.emailJobPublisher = emailJobPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(
            VerificationEmailRequested event
    ) {
        emailJobPublisher.publishVerificationEmail(
                event.email(),
                event.rawToken()
        );
    }
}