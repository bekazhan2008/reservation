package com.reservation.properties;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/property-types")
public class PropertyTypeController {

    private final PropertyTypeRepository repository;

    public PropertyTypeController(PropertyTypeRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PropertyTypeResponse> getAll() {
        return repository.findAll(Sort.by("id"))
                .stream()
                .map(type -> new PropertyTypeResponse(
                        type.getId(),
                        type.getName()
                ))
                .toList();
    }
}