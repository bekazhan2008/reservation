package com.reservation.authentication.verification;

public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("Invalid or expired verification token");
    }
}