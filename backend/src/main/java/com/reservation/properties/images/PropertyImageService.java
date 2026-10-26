package com.reservation.properties.images;

import java.io.IOException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.reservation.properties.Property;
import com.reservation.properties.PropertyRepository;
import com.reservation.properties.PropertyNotFoundException;
import com.reservation.properties.PropertyAccessDeniedException;
import com.reservation.users.UserRepository;
import java.util.UUID;
import java.util.List;
import com.reservation.properties.PropertyNotFoundException;
import org.springframework.http.MediaType;

import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyImageService {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private final S3StorageService storageService;
    private final PropertyRepository propertyRepository;
    private final PropertyImageRepository imageRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public PropertyImageService(
            S3StorageService storageService,
            PropertyRepository propertyRepository,
            PropertyImageRepository imageRepository,
            UserRepository userRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.storageService = storageService;
        this.propertyRepository = propertyRepository;
        this.imageRepository = imageRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public void validateImage(MultipartFile file) {

        if (file.isEmpty()) {
            throw new InvalidPropertyImageException(
                    "Image must not be empty"
            );
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidPropertyImageException(
                    "Image must not exceed 5 MB"
            );
        }

        String contentType = file.getContentType();

        if (!"image/jpeg".equals(contentType)
                && !"image/png".equals(contentType)
                && !"image/webp".equals(contentType)) {
            throw new InvalidPropertyImageException(
                    "Only JPEG, PNG and WebP images are allowed"
            );
        }
    }

    @Transactional(readOnly = true)
    public Property checkUploadPermission(
            Long propertyId,
            String userEmail
    ) {
        var user = userRepository.findByNormalizedEmail(userEmail)
                .orElseThrow(() ->
                        new PropertyAccessDeniedException("Access denied")
                );

        if (!user.isEnabled()
                || user.getDeletedAt() != null
                || !user.isEmailVerified()) {
            throw new PropertyAccessDeniedException("Access denied");
        }

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(PropertyNotFoundException::new);

        if (!property.getOwner().getId().equals(user.getId())) {
            throw new PropertyAccessDeniedException("Access denied");
        }

        if (imageRepository.countByPropertyId(propertyId) >= 5) {
            throw new InvalidPropertyImageException(
                    "A property cannot have more than 5 images"
            );
        }

        return property;
    }

    private String generateObjectKey(Long propertyId, String contentType) {
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new InvalidPropertyImageException(
                    "Unsupported image type"
            );
        };

        return "properties/" + propertyId + "/"
                + UUID.randomUUID() + extension;
    }

    public PropertyImage uploadImage(
            Long propertyId,
            String userEmail,
            MultipartFile file
    ) {
        validateImage(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidPropertyImageException("Could not read image");
        }

        String objectKey = generateObjectKey(
                propertyId,
                file.getContentType()
        );

        boolean[] uploaded = {false};

        try {
            return transactionTemplate.execute(status -> {
                var user = userRepository.findByNormalizedEmail(userEmail)
                        .orElseThrow(() ->
                                new PropertyAccessDeniedException("Access denied"));

                if (!user.isEnabled()
                        || user.getDeletedAt() != null
                        || !user.isEmailVerified()) {
                    throw new PropertyAccessDeniedException("Access denied");
                }

                Property property = propertyRepository.findByIdForUpdate(propertyId)
                        .orElseThrow(PropertyNotFoundException::new);

                if (!property.getOwner().getId().equals(user.getId())) {
                    throw new PropertyAccessDeniedException("Access denied");
                }

                long count = imageRepository.countByPropertyId(propertyId);

                if (count >= 5) {
                    throw new InvalidPropertyImageException(
                            "A property cannot have more than 5 images"
                    );
                }

                storageService.upload(objectKey, bytes, file.getContentType());
                uploaded[0] = true;

                PropertyImage image = new PropertyImage(
                        property,
                        objectKey,
                        file.getContentType(),
                        bytes.length,
                        (int) count
                );

                return imageRepository.saveAndFlush(image);
            });
        } catch (RuntimeException e) {
            if (uploaded[0]) {
                try {
                    storageService.delete(objectKey);
                } catch (RuntimeException cleanupError) {
                    // Ошибку очистки нельзя скрывать, но и исходную ошибку
                    // сохранения нельзя терять.
                    e.addSuppressed(cleanupError);
                }
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public ImageDownload downloadImage(Long propertyId, Long imageId) {

        propertyRepository.findByIdAndPublishedTrue(propertyId)
                .orElseThrow(PropertyNotFoundException::new);

        PropertyImage image = imageRepository
                .findByIdAndPropertyId(imageId, propertyId)
                .orElseThrow(PropertyNotFoundException::new);

        byte[] bytes = storageService.download(image.getObjectKey());

        return new ImageDownload(bytes, image.getContentType());
    }

    @Transactional(readOnly = true)
    public List<PropertyImageResponse> getPropertyImages(Long propertyId) {
        propertyRepository.findByIdAndPublishedTrue(propertyId)
                .orElseThrow(PropertyNotFoundException::new);

        return imageRepository.findByPropertyIdOrderByDisplayOrderAsc(propertyId)
                .stream()
                .map(PropertyImageResponse::from)
                .toList();
    }
}