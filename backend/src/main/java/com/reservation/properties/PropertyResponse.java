package com.reservation.properties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record PropertyResponse(
        Long id,
        Long ownerId,
        Long propertyTypeId,
        String title,
        String description,
        String city,
        BigDecimal pricePerMonth,
        BigDecimal areaSqm,
        BigDecimal landAreaSotka,
        Set<Long> categoryIds,
        boolean published,
        Instant createdAt
) {
}