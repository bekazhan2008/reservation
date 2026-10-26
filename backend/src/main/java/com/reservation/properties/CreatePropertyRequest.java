package com.reservation.properties;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Set;

public record CreatePropertyRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @NotBlank
        String description,

        @NotBlank
        @Size(max = 150)
        String city,

        @NotNull
        Long propertyTypeId,

        @NotNull
        @DecimalMin("0.01")
        BigDecimal pricePerMonth,

        @NotNull
        @DecimalMin("0.01")
        BigDecimal areaSqm,

        @DecimalMin("0.01")
        BigDecimal landAreaSotka,

        @NotEmpty
        Set<@NotNull Long> categoryIds

) {
}