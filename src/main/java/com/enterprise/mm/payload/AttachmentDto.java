package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AttachmentDto extends BaseRecordDto {
    private String entityType;
    private UUID entityId;
    private String attachmentType;
    private String originalFileName;
    private String contentType;
    private Long sizeBytes;
    private String checksumSha256;
    private String storageProvider;
    private LocalDateTime uploadedAt;
    private boolean downloadable;
    private boolean previewable;
}