package com.reservation.properties;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyTypeRepository
        extends JpaRepository<PropertyType, Long> {
}