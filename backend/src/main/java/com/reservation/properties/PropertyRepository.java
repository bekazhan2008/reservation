package com.reservation.properties;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PropertyRepository
        extends JpaRepository<Property, Long>,
        JpaSpecificationExecutor<Property> {
    List<Property> findByPublishedTrue();
    Optional<Property> findByIdAndPublishedTrue(Long id);
    List<Property> findByPublishedTrueAndTitleContainingIgnoreCase(
            String title
    );
}