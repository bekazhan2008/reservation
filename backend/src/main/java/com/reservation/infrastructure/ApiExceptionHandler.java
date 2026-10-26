package com.reservation.infrastructure;

import com.reservation.authentication.registration.EmailAlreadyRegisteredException;
import com.reservation.authentication.verification.InvalidVerificationTokenException;
import com.reservation.properties.PropertyReferenceNotFoundException;
import com.reservation.properties.PropertyNotFoundException;
import com.reservation.properties.InvalidPriceRangeException;
import com.reservation.properties.PropertyAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        // Отклонённые значения (rejectedValue) не возвращаем: среди них может быть пароль.
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field)
                        .thenComparing(ApiError.FieldError::message))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        // Текст исключения парсера не возвращаем: он может содержать фрагменты тела запроса.
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", List.of());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(EmailAlreadyRegisteredException e) {
        return build(HttpStatus.CONFLICT, e.getMessage(), List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(
            AuthenticationException e
    ) {
        return build(
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password",
                List.of()
        );
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ApiError> handleInvalidVerificationToken(
            InvalidVerificationTokenException e
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                e.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(PropertyReferenceNotFoundException.class)
    public ResponseEntity<ApiError> handlePropertyReferenceNotFound(
            PropertyReferenceNotFoundException e
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                e.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(PropertyNotFoundException.class)
    public ResponseEntity<ApiError> handlePropertyNotFound(
            PropertyNotFoundException e
    ) {
        return build(
                HttpStatus.NOT_FOUND,
                e.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(PropertyAccessDeniedException.class)
    public ResponseEntity<ApiError> handlePropertyAccessDenied(
            PropertyAccessDeniedException e
    ) {
        return build(HttpStatus.FORBIDDEN, e.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidPriceRangeException.class)
    public ResponseEntity<ApiError> handleInvalidPriceRange(
            InvalidPriceRangeException e
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                e.getMessage(),
                List.of()
        );
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message,
                                                  List<ApiError.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message, fieldErrors));
    }
}
