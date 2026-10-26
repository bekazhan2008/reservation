package com.reservation.properties.images;

public record ImageDownload(
        byte[] bytes,
        String contentType
) {
}