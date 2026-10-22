package com.reservation.authentication.registration;

public record RegistrationResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        boolean emailVerified
) {
}
