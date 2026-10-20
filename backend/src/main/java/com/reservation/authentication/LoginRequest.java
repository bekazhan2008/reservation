package com.reservation.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank String password
) {
    public LoginRequest {
        email = email == null
                ? null
                : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "LoginRequest{email='" + email
                + "', password=[PROTECTED]}";
    }
}