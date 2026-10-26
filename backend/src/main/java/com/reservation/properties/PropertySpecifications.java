package com.reservation.properties;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;

public final class PropertySpecifications {

    private PropertySpecifications() {
    }

    public static Specification<Property> published() {
        return (root, query, cb) ->
                cb.isTrue(root.get("published"));
    }

    public static Specification<Property> titleContains(String title) {
        return (root, query, cb) -> {
            String escaped = title.strip()
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");

            return cb.like(
                    cb.lower(root.get("title")),
                    "%" + escaped + "%",
                    '\\'
            );
        };
    }

    public static Specification<Property> hasType(Long typeId) {
        return (root, query, cb) ->
                cb.equal(root.get("propertyType").get("id"), typeId);
    }

    public static Specification<Property> minPrice(BigDecimal price) {
        return (root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("pricePerMonth"), price);
    }

    public static Specification<Property> maxPrice(BigDecimal price) {
        return (root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("pricePerMonth"), price);
    }

    public static Specification<Property> hasCategory(Long categoryId) {
        return (root, query, cb) ->
                cb.equal(
                        root.join("categories", JoinType.INNER).get("id"),
                        categoryId
                );
    }
}