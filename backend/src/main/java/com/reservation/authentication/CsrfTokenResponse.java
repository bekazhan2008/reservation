package com.reservation.authentication;

public record CsrfTokenResponse(String token, String headerName, String parameterName) {
}
