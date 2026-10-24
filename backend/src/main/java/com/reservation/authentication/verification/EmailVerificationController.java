package com.reservation.authentication.verification;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class EmailVerificationController {

    private final EmailVerificationService verificationService;

    public EmailVerificationController(
            EmailVerificationService verificationService
    ) {
        this.verificationService = verificationService;
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request
    ) {
        verificationService.verifyEmail(request.token());

        return ResponseEntity.noContent().build();
    }

    // Всегда 202 с одним и тем же телом, чтобы нельзя было узнать, зарегистрирован ли email.
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request
    ) {
        verificationService.resendVerification(request.email());

        return ResponseEntity.accepted().body(Map.of(
                "message",
                "If the account exists and is not verified, a verification email will be sent."
        ));
    }
}
