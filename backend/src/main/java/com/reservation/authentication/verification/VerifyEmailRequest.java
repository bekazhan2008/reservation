package com.reservation.authentication.verification;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
        @NotBlank String token
) {
    @Override
    public String toString() {
        return "VerifyEmailRequest{token=[PROTECTED]}";
    }
}