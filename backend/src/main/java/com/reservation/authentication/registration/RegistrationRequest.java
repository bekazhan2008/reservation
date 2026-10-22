package com.reservation.authentication.registration;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record RegistrationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, message = "must be at least {min} characters") @MaxUtf8Bytes(72) String password,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName
) {

    // Нормализация выполняется при создании record, то есть до Bean Validation.
    // Пароль намеренно не изменяется.
    public RegistrationRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        firstName = firstName == null ? null : firstName.strip();
        lastName = lastName == null ? null : lastName.strip();
    }

    @Override
    public String toString() {
        return "RegistrationRequest{email='" + email + "', password=[PROTECTED]}";
    }
}
