package com.reservation.authentication;

public record LoginResponse(
        String email,
        String role
) {
}