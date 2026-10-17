package com.reservation.infrastructure;

import java.util.List;

public record ApiError(int status, String error, String message, List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }
}
