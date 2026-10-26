package com.reservation.properties;

import com.reservation.users.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "properties")
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_type_id", nullable = false)
    private PropertyType propertyType;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "property_categories",
            joinColumns = @JoinColumn(name = "property_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new HashSet<>();

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 150)
    private String city;

    @Column(name = "price_per_month", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricePerMonth;

    @Column(name = "area_sqm", nullable = false, precision = 12, scale = 2)
    private BigDecimal areaSqm;

    @Column(name = "land_area_sotka", precision = 12, scale = 2)
    private BigDecimal landAreaSotka;

    @Column(nullable = false)
    private boolean published = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Property() {
    }

    public Property(
            User owner,
            PropertyType propertyType,
            String title,
            String description,
            String city,
            BigDecimal pricePerMonth,
            BigDecimal areaSqm,
            BigDecimal landAreaSotka,
            Set<Category> categories
    ) {
        this.owner = owner;
        this.propertyType = propertyType;
        this.title = title;
        this.description = description;
        this.city = city;
        this.pricePerMonth = pricePerMonth;
        this.areaSqm = areaSqm;
        this.landAreaSotka = landAreaSotka;
        this.categories = new HashSet<>(categories);
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCity() {
        return city;
    }

    public BigDecimal getPricePerMonth() {
        return pricePerMonth;
    }

    public BigDecimal getAreaSqm() {
        return areaSqm;
    }

    public BigDecimal getLandAreaSotka() {
        return landAreaSotka;
    }

    public Set<Category> getCategories() {
        return Set.copyOf(categories);
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void update(
            PropertyType propertyType,
            String title,
            String description,
            String city,
            BigDecimal pricePerMonth,
            BigDecimal areaSqm,
            BigDecimal landAreaSotka,
            Set<Category> categories
    ) {
        this.propertyType = propertyType;
        this.title = title;
        this.description = description;
        this.city = city;
        this.pricePerMonth = pricePerMonth;
        this.areaSqm = areaSqm;
        this.landAreaSotka = landAreaSotka;
        this.categories.clear();
        this.categories.addAll(categories);
    }

    public void unpublish() {
        this.published = false;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}