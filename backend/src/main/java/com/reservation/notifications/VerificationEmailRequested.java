package com.reservation.notifications;

public record VerificationEmailRequested(
        String email,
        String rawToken
) {
    @Override
    public String toString() {
        return "VerificationEmailRequested{email='"
                + email + "', rawToken=[PROTECTED]}";
    }
}