package com.reservation.reservations;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/properties/{propertyId}/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> create(
            @PathVariable Long propertyId,
            @Valid @RequestBody CreateReservationRequest request,
            Authentication authentication
    ) {
        ReservationResponse response = reservationService.create(
                propertyId,
                request,
                authentication.getName()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PatchMapping("/{reservationId}/confirm")
    public ReservationResponse confirm(
            @PathVariable Long propertyId,
            @PathVariable Long reservationId,
            Authentication authentication
    ) {
        return reservationService.confirm(
                propertyId,
                reservationId,
                authentication.getName()
        );
    }

    @PatchMapping("/{reservationId}/reject")
    public ReservationResponse reject(
            @PathVariable Long propertyId,
            @PathVariable Long reservationId,
            Authentication authentication
    ) {
        return reservationService.reject(
                propertyId,
                reservationId,
                authentication.getName()
        );
    }
}