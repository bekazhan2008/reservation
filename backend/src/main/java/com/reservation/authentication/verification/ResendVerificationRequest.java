package com.reservation.authentication.verification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record ResendVerificationRequest(
        @NotBlank @Email @Size(max = 254) String email
) {

    // Та же нормализация, что при регистрации (RegistrationRequest).
    public ResendVerificationRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
