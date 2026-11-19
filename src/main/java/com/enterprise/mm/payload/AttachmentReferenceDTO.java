package com.enterprise.mm.payload;

import java.util.UUID;

public record AttachmentReferenceDTO(
    UUID attachmentId,
    String entityType,
    UUID entityId,
    String attachmentType,
    String originalFileName,
    String contentType,
    String fileName,
    String url
) {
}
