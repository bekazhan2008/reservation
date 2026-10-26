package com.reservation.properties.images;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/properties/{propertyId}/images")
public class PropertyImageController {

    private final PropertyImageService imageService;

    public PropertyImageController(PropertyImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<PropertyImageResponse> upload(
            @PathVariable Long propertyId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        PropertyImage image = imageService.uploadImage(
                propertyId,
                authentication.getName(),
                file
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PropertyImageResponse.from(image));
    }

    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> download(
            @PathVariable Long propertyId,
            @PathVariable Long imageId
    ) {
        ImageDownload image = imageService.downloadImage(propertyId, imageId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.bytes());
    }

    @GetMapping
    public List<PropertyImageResponse> getAll(
            @PathVariable Long propertyId
    ) {
        return imageService.getPropertyImages(propertyId);
    }
}