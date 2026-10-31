package com.reservation.reservations;

import com.reservation.properties.Property;
import com.reservation.properties.PropertyNotFoundException;
import com.reservation.properties.PropertyRepository;
import com.reservation.users.User;
import com.reservation.users.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;

    public ReservationService(
            ReservationRepository reservationRepository,
            PropertyRepository propertyRepository,
            UserRepository userRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ReservationResponse create(
            Long propertyId,
            CreateReservationRequest request,
            String userEmail
    ) {
        User tenant = userRepository.findByNormalizedEmail(userEmail)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));

        if (!tenant.isEnabled()
                || tenant.getDeletedAt() != null
                || !tenant.isEmailVerified()) {
            throw new AccessDeniedException("Access denied");
        }

        Property property = propertyRepository.findByIdAndPublishedTrue(propertyId)
                .orElseThrow(PropertyNotFoundException::new);

        if (property.getOwner().getId().equals(tenant.getId())) {
            throw new InvalidReservationException(
                    "You cannot rent your own property"
            );
        }

        Reservation reservation = new Reservation(
                property,
                tenant,
                request.startDate(),
                request.months()
        );

        return ReservationResponse.from(
                reservationRepository.save(reservation)
        );
    }

    @Transactional
    public ReservationResponse confirm(
            Long propertyId,
            Long reservationId,
            String userEmail
    ) {
        Reservation reservation = findForOwner(
                propertyId, reservationId, userEmail
        );

        reservation.confirm();

        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            if (hasSqlState(e, "23P01")) {
                throw new ReservationConflictException(
                        "Property is already reserved for these dates"
                );
            }
            throw e;
        }

        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse reject(
            Long propertyId,
            Long reservationId,
            String userEmail
    ) {
        Reservation reservation = findForOwner(
                propertyId, reservationId, userEmail
        );

        reservation.reject();

        return ReservationResponse.from(reservation);
    }

    private Reservation findForOwner(
            Long propertyId,
            Long reservationId,
            String userEmail
    ) {
        User user = userRepository.findByNormalizedEmail(userEmail)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));

        if (!user.isEnabled()
                || user.getDeletedAt() != null
                || !user.isEmailVerified()) {
            throw new AccessDeniedException("Access denied");
        }

        Reservation reservation = reservationRepository
                .findByIdForUpdate(reservationId)
                .orElseThrow(ReservationNotFoundException::new);

        if (!reservation.getProperty().getId().equals(propertyId)) {
            throw new ReservationNotFoundException();
        }

        if (!reservation.getProperty().getOwner().getId().equals(user.getId())) {
            throw new AccessDeniedException("Access denied");
        }

        return reservation;
    }

    private boolean hasSqlState(Throwable error, String sqlState) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof java.sql.SQLException sqlException
                    && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }
}