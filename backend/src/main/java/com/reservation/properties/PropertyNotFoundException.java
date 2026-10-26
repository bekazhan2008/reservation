package com.reservation.properties;

public class PropertyNotFoundException extends RuntimeException {

    public PropertyNotFoundException() {
        super("Property not found");
    }
}