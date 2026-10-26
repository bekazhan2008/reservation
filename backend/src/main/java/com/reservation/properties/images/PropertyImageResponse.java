package com.reservation.properties.images;

public record PropertyImageResponse(
        Long id,
        Long propertyId,
        String contentType,
        long fileSize,
        int displayOrder
) {
    public static PropertyImageResponse from(PropertyImage image) {
        return new PropertyImageResponse(
                image.getId(),
                image.getProperty().getId(),
                image.getContentType(),
                image.getFileSize(),
                image.getDisplayOrder()
        );
    }
}