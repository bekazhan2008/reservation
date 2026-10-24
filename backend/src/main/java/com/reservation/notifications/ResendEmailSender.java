package com.reservation.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class ResendEmailSender {

    private final RestClient restClient;
    private final String apiKey;
    private final String from;

    public ResendEmailSender(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:}") String from
    ) {
        this.apiKey = apiKey;
        this.from = from;

        this.restClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .build();
    }

    public void sendTestEmail(String recipient) {
        if (apiKey.isBlank() || from.isBlank()) {
            throw new IllegalStateException("Resend is not configured");
        }

        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(recipient),
                "subject", "Reservation - Test Email",
                "html", "<h2>Reservation</h2><p>Email sending works!</p>"
        );

        restClient.post()
                .uri("/emails")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public void sendVerificationEmail(String recipient, String rawToken) {
        if (apiKey.isBlank() || from.isBlank()) {
            throw new IllegalStateException("Resend is not configured");
        }

        String verificationUrl =
                "http://localhost:5173/verify-email?token="
                        + rawToken;

        String html = """
            <h2>Welcome to Reservation!</h2>
            <p>Confirm your email by clicking the link below:</p>
            <p><a href="%s">Verify email</a></p>
            <p>This link expires in 24 hours.</p>
            """.formatted(verificationUrl);

        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(recipient),
                "subject", "Confirm your Reservation email",
                "html", html
        );

        restClient.post()
                .uri("/emails")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}