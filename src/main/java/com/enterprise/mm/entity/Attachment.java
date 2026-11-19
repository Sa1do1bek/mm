package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "attachments")
public class Attachment extends BusinessEntity {
    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "attachment_type", length = 80)
    private String attachmentType;

    @Column(name = "original_file_name", nullable = false, length = 300)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 160)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "storage_provider", nullable = false, length = 40)
    private String storageProvider = "LOCAL";

    @Column(name = "storage_key", nullable = false, length = 700)
    private String storageKey;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    private boolean downloadable = true;
    private boolean previewable = false;
}