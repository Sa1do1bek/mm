package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AttachmentRepository;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupplierAccreditationEvidencePolicy {
    public static final String ATTACHMENT_ENTITY_TYPE = "SUPPLIER_ACCREDITATION_DOCUMENT";

    private final AttachmentRepository attachmentRepository;

    public void requireActiveLinkedAttachment(SupplierAccreditationDocument document) {
        if (!hasActiveLinkedAttachment(document)) {
            throw new BusinessException(
                "Accreditation evidence requires an active linked attachment for the same tenant, company, and document"
            );
        }
    }

    public boolean isCurrentVerifiedEvidence(SupplierAccreditationDocument document, LocalDate today) {
        return document != null
            && "VERIFIED".equals(document.getStatus())
            && (document.getValidFrom() == null || !document.getValidFrom().isAfter(today))
            && (document.getValidTo() == null || !document.getValidTo().isBefore(today))
            && hasActiveLinkedAttachment(document);
    }

    private boolean hasActiveLinkedAttachment(SupplierAccreditationDocument document) {
        if (document == null
            || document.getId() == null
            || document.getAttachmentId() == null
            || document.getCompanyId() == null
            || !Objects.equals(TenantContext.currentTenant(), document.getTenantId())) {
            return false;
        }
        return attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                document.getTenantId(), document.getCompanyId(), document.getAttachmentId())
            .filter(this::isActive)
            .filter(attachment -> Objects.equals(document.getTenantId(), attachment.getTenantId()))
            .filter(attachment -> Objects.equals(document.getCompanyId(), attachment.getCompanyId()))
            .filter(attachment -> ATTACHMENT_ENTITY_TYPE.equals(attachment.getEntityType()))
            .filter(attachment -> document.getId().equals(attachment.getEntityId()))
            .isPresent();
    }

    private boolean isActive(Attachment attachment) {
        return attachment != null && "ACTIVE".equalsIgnoreCase(attachment.getStatus());
    }
}
