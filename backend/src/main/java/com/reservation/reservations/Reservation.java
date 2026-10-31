package com.reservation.reservations;

import com.reservation.properties.Property;
import com.reservation.users.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private User tenant;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private Integer months;

    @Column(name = "price_per_month", nullable = false,
            precision = 15, scale = 2)
    private BigDecimal pricePerMonth;

    @Column(name = "total_price", nullable = false,
            precision = 17, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false,
            insertable = false, updatable = false)
    private Instant createdAt;

    protected Reservation() {
    }

    public Reservation(
            Property property,
            User tenant,
            LocalDate startDate,
            int months
    ) {
        this.property = property;
        this.tenant = tenant;
        this.startDate = startDate;
        this.months = months;
        this.endDate = startDate.plusMonths(months);
        this.pricePerMonth = property.getPricePerMonth();
        this.totalPrice = pricePerMonth.multiply(BigDecimal.valueOf(months));
        this.status = ReservationStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Property getProperty() {
        return property;
    }

    public User getTenant() {
        return tenant;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Integer getMonths() {
        return months;
    }

    public BigDecimal getPricePerMonth() {
        return pricePerMonth;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void confirm() {
        if (status != ReservationStatus.PENDING) {
            throw new ReservationConflictException(
                    "Only pending reservations can be confirmed"
            );
        }
        status = ReservationStatus.CONFIRMED;
    }

    public void reject() {
        if (status != ReservationStatus.PENDING) {
            throw new ReservationConflictException(
                    "Only pending reservations can be rejected"
            );
        }
        status = ReservationStatus.REJECTED;
    }
}