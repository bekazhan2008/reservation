package com.reservation.properties;

public class PropertyAccessDeniedException extends RuntimeException {

    public PropertyAccessDeniedException(String message) {
        super(message);
    }
}
