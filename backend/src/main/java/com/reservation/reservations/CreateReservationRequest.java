package com.reservation.reservations;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateReservationRequest(
        @NotNull @FutureOrPresent LocalDate startDate,
        @Min(1) @Max(12) int months
) {
}