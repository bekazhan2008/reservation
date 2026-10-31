package com.reservation.reservations;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReservationResponse(
        Long id,
        Long propertyId,
        Long tenantId,
        LocalDate startDate,
        LocalDate endDate,
        int months,
        BigDecimal pricePerMonth,
        BigDecimal totalPrice,
        ReservationStatus status
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getProperty().getId(),
                reservation.getTenant().getId(),
                reservation.getStartDate(),
                reservation.getEndDate(),
                reservation.getMonths(),
                reservation.getPricePerMonth(),
                reservation.getTotalPrice(),
                reservation.getStatus()
        );
    }
}