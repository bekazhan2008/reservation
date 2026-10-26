package com.reservation.properties;

import com.reservation.users.User;
import com.reservation.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final PropertyTypeRepository propertyTypeRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public PropertyService(
            PropertyRepository propertyRepository,
            PropertyTypeRepository propertyTypeRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository
    ) {
        this.propertyRepository = propertyRepository;
        this.propertyTypeRepository = propertyTypeRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    private User findOwner(String email) {
        User owner = userRepository.findByNormalizedEmail(email)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Authenticated user not found"
                        )
                );

        if (!owner.isEnabled()
                || owner.getDeletedAt() != null
                || !owner.isEmailVerified()) {
            throw new IllegalStateException(
                    "Account is not allowed to publish properties"
            );
        }

        return owner;
    }

    @Transactional
    public PropertyResponse createProperty(
            CreatePropertyRequest request,
            String ownerEmail
    ) {
        User owner = findOwner(ownerEmail);

        PropertyType propertyType = propertyTypeRepository
                .findById(request.propertyTypeId())
                .orElseThrow(() ->
                        new PropertyReferenceNotFoundException("Property type not found")
                );

        Set<Category> categories = new HashSet<>(
                categoryRepository.findAllById(request.categoryIds())
        );

        if (categories.size() != request.categoryIds().size()) {
            throw new PropertyReferenceNotFoundException(
                    "One or more categories not found"
            );
        }

        Property property = new Property(
                owner,
                propertyType,
                request.title(),
                request.description(),
                request.city(),
                request.pricePerMonth(),
                request.areaSqm(),
                request.landAreaSotka(),
                categories
        );

        Property savedProperty = propertyRepository.save(property);
        return toResponse(savedProperty);
    }

    private PropertyResponse toResponse(Property property) {
        return new PropertyResponse(
                property.getId(),
                property.getOwner().getId(),
                property.getPropertyType().getId(),
                property.getTitle(),
                property.getDescription(),
                property.getCity(),
                property.getPricePerMonth(),
                property.getAreaSqm(),
                property.getLandAreaSotka(),
                property.getCategories().stream()
                        .map(Category::getId)
                        .collect(Collectors.toSet()),
                property.isPublished(),
                property.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> getPublishedProperties() {
        return propertyRepository.findByPublishedTrue()
                .stream()
                .map(this::toResponse)
                .toList();
    }
}