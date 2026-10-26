package com.reservation.properties;

public class InvalidPriceRangeException extends RuntimeException {

    public InvalidPriceRangeException() {
        super("Minimum price cannot exceed maximum price");
    }
}