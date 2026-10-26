package com.reservation.properties;

import jakarta.validation.Valid;
import java.util.List;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @PostMapping
    public ResponseEntity<PropertyResponse> create(
            @Valid @RequestBody CreatePropertyRequest request,
            Authentication authentication
    ) {
        PropertyResponse response = propertyService.createProperty(
                request,
                authentication.getName()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PutMapping("/{id}")
    public PropertyResponse update(
            @PathVariable Long id,
            @Valid @RequestBody CreatePropertyRequest request,
            Authentication authentication
    ) {
        return propertyService.updateProperty(id, request, authentication.getName());
    }

    @PatchMapping("/{id}/unpublish")
    public PropertyResponse unpublish(
            @PathVariable Long id,
            Authentication authentication
    ) {
        return propertyService.unpublishProperty(id, authentication.getName());
    }

    @GetMapping
    public List<PropertyResponse> getAll(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long propertyTypeId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice
    ) {
        return propertyService.searchProperties(
                title,
                propertyTypeId,
                categoryId,
                minPrice,
                maxPrice
        );
    }

    @GetMapping("/{id}")
    public PropertyResponse getById(@PathVariable Long id) {
        return propertyService.getPublishedProperty(id);
    }
}