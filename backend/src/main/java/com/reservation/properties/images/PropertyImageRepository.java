package com.reservation.properties.images;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PropertyImageRepository
        extends JpaRepository<PropertyImage, Long> {

    List<PropertyImage> findByPropertyIdOrderByDisplayOrderAsc(Long propertyId);

    long countByPropertyId(Long propertyId);

    Optional<PropertyImage> findByIdAndPropertyId(
            Long id,
            Long propertyId
    );
}