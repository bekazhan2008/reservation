package com.reservation.properties;

import com.reservation.users.User;
import com.reservation.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;
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
            throw new PropertyAccessDeniedException(
                    "Account is not allowed to manage properties"
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

        PropertyType propertyType = findPropertyType(request);
        Set<Category> categories = findCategories(request);

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

    @Transactional
    public PropertyResponse updateProperty(
            Long id,
            CreatePropertyRequest request,
            String ownerEmail
    ) {
        Property property = findOwnedProperty(id, ownerEmail);

        property.update(
                findPropertyType(request),
                request.title(),
                request.description(),
                request.city(),
                request.pricePerMonth(),
                request.areaSqm(),
                request.landAreaSotka(),
                findCategories(request)
        );

        return toResponse(propertyRepository.save(property));
    }

    @Transactional
    public PropertyResponse unpublishProperty(Long id, String ownerEmail) {
        Property property = findOwnedProperty(id, ownerEmail);
        property.unpublish();
        return toResponse(propertyRepository.save(property));
    }

    // Чужое скрытое объявление выглядит как несуществующее (404), чтобы не раскрывать его наличие.
    private Property findOwnedProperty(Long id, String email) {
        User user = findOwner(email);

        Property property = propertyRepository.findById(id)
                .orElseThrow(PropertyNotFoundException::new);

        if (!property.getOwner().getId().equals(user.getId())) {
            if (!property.isPublished()) {
                throw new PropertyNotFoundException();
            }
            throw new PropertyAccessDeniedException(
                    "You do not own this property"
            );
        }

        return property;
    }

    private PropertyType findPropertyType(CreatePropertyRequest request) {
        return propertyTypeRepository
                .findById(request.propertyTypeId())
                .orElseThrow(() ->
                        new PropertyReferenceNotFoundException("Property type not found")
                );
    }

    private Set<Category> findCategories(CreatePropertyRequest request) {
        Set<Category> categories = new HashSet<>(
                categoryRepository.findAllById(request.categoryIds())
        );

        if (categories.size() != request.categoryIds().size()) {
            throw new PropertyReferenceNotFoundException(
                    "One or more categories not found"
            );
        }

        return categories;
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

    @Transactional(readOnly = true)
    public PropertyResponse getPublishedProperty(Long id) {
        Property property = propertyRepository
                .findByIdAndPublishedTrue(id)
                .orElseThrow(() ->
                        new PropertyNotFoundException()
                );

        return toResponse(property);
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> searchByTitle(String title) {
        return propertyRepository
                .findByPublishedTrueAndTitleContainingIgnoreCase(title.strip())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> searchProperties(
            String title,
            Long propertyTypeId,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
        if (minPrice != null && maxPrice != null
                && minPrice.compareTo(maxPrice) > 0) {
            throw new InvalidPriceRangeException();
        }

        Specification<Property> spec = PropertySpecifications.published();

        if (title != null && !title.isBlank()) {
            spec = spec.and(PropertySpecifications.titleContains(title));
        }

        if (propertyTypeId != null) {
            spec = spec.and(PropertySpecifications.hasType(propertyTypeId));
        }

        if (categoryId != null) {
            spec = spec.and(PropertySpecifications.hasCategory(categoryId));
        }

        if (minPrice != null) {
            spec = spec.and(PropertySpecifications.minPrice(minPrice));
        }

        if (maxPrice != null) {
            spec = spec.and(PropertySpecifications.maxPrice(maxPrice));
        }

        return propertyRepository.findAll(spec)
                .stream()
                .map(this::toResponse)
                .toList();
    }
}