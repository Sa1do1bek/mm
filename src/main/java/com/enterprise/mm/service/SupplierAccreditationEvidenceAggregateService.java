package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.SupplierAccreditationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SupplierAccreditationEvidenceAggregateService {
    private static final String ENTITY_TYPE = "SUPPLIER_ACCREDITATION_DOCUMENT";
    private static final String OPERATION = "SUPPLIER_ACCREDITATION_EVIDENCE_CREATE";

    private final SupplierAccreditationDocumentService documentService;
    private final AttachmentService attachmentService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierAccreditationDocumentResponse create(
        UUID applicationId,
        String documentType,
        String name,
        LocalDate validFrom,
        LocalDate validTo,
        MultipartFile file,
        String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for accreditation evidence upload");
        }
        if (applicationId == null) {
            throw new BadRequestException("applicationId is required");
        }
        if (documentType == null || documentType.isBlank()) {
            throw new BadRequestException("documentType is required");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new BadRequestException("Evidence validTo must not be before validFrom");
        }
        EvidenceUploadRequest command = new EvidenceUploadRequest(
            applicationId,
            documentType.trim(),
            name,
            validFrom,
            validTo,
            file.getOriginalFilename(),
            file.getContentType(),
            file.getSize(),
            checksum(file)
        );
        return idempotencyService.execute(
            OPERATION,
            applicationId,
            idempotencyKey,
            command,
            SupplierAccreditationDocumentResponse.class,
            () -> createAggregate(command, file)
        );
    }

    private SupplierAccreditationDocumentResponse createAggregate(
        EvidenceUploadRequest command,
        MultipartFile file
    ) {
        SupplierAccreditationDocumentCreateRequest create = new SupplierAccreditationDocumentCreateRequest();
        create.setApplicationId(command.applicationId());
        create.setDocumentType(command.documentType());
        create.setName(command.name() == null || command.name().isBlank()
            ? command.fileName()
            : command.name().trim());
        create.setValidFrom(command.validFrom());
        create.setValidTo(command.validTo());
        SupplierAccreditationDocumentResponse document = documentService.create(create);

        AttachmentDto attachment = attachmentService.upload(
            ENTITY_TYPE,
            document.getId(),
            command.documentType(),
            file,
            null
        );

        SupplierAccreditationDocumentUpdateRequest update = new SupplierAccreditationDocumentUpdateRequest();
        update.setApplicationId(command.applicationId());
        update.setDocumentType(command.documentType());
        update.setName(create.getName());
        update.setValidFrom(command.validFrom());
        update.setValidTo(command.validTo());
        update.setAttachmentId(attachment.getId());
        return documentService.update(document.getId(), update);
    }

    private String checksum(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new BusinessException("Cannot calculate accreditation evidence checksum");
        }
    }

    private record EvidenceUploadRequest(
        UUID applicationId,
        String documentType,
        String name,
        LocalDate validFrom,
        LocalDate validTo,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256
    ) {
    }
}
