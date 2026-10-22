package com.reservation.authentication.login;

public record LoginResponse(
        String email,
        String role
) {
}