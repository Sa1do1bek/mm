package com.enterprise.mm.warehouse.print;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record WarehouseDocumentEvidenceBundle(
    UUID documentId,
    WarehousePrintDocumentType documentType,
    String documentNumber,
    String status,
    String tamperProofHash,
    boolean isIntegrityVerified,
    List<EvidenceSignature> signatures,
    List<EvidenceAttachment> attachments,
    String acceptanceStatus,
    String notes
) {
    public record EvidenceSignature(
        UUID signerId,
        String signerUsername,
        String signerFullName,
        String signerRole,
        String pinfl,
        LocalDateTime signedAt,
        String signatureType,
        String signatureHash,
        String comment
    ) {}

    public record EvidenceAttachment(
        UUID id,
        String fileName,
        String attachmentType,
        String contentType,
        Long fileSize,
        LocalDateTime uploadedAt,
        String downloadUrl
    ) {}

    public record SignEvidenceRequest(
        String signatureType,
        String pinfl,
        String comment,
        List<UUID> attachmentIds
    ) {}
}
