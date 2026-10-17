package com.reservation.authentication;

public record RegistrationResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        boolean emailVerified
) {
}
