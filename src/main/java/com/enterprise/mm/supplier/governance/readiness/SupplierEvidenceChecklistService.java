package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierAccreditationEvidenceRequirement;
import com.enterprise.mm.entity.SupplierEvidenceRequirement;
import com.enterprise.mm.entity.SupplierEvidenceTemplateVersion;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierAccreditationEvidenceRequirementRepository;
import com.enterprise.mm.repository.SupplierEvidenceRequirementRepository;
import com.enterprise.mm.repository.SupplierEvidenceTemplateVersionRepository;
import com.enterprise.mm.service.SupplierAccreditationEvidencePolicy;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceRequirementDefaults.Definition;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierEvidenceChecklistService {
    private static final Map<String, String> DOCUMENT_TYPE_ALIASES = Map.of(
        "TAX_CERTIFICATE", "TAX_STATUS",
        "BANK_DETAILS", "BANK_CONFIRMATION"
    );

    private final SupplierEvidenceTemplateVersionRepository templateRepository;
    private final SupplierEvidenceRequirementRepository requirementRepository;
    private final SupplierAccreditationEvidenceRequirementRepository snapshotRepository;
    private final SupplierAccreditationDocumentRepository documentRepository;
    private final SupplierAccreditationEvidencePolicy evidencePolicy;

    @Transactional
    public void snapshotFor(SupplierAccreditationApplication application) {
        requirePersistentApplication(application);
        if (snapshotRepository.existsByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalse(
            application.getTenantId(), application.getCompanyId(), application.getId())) {
            return;
        }
        String accreditationType = normalizeType(application.getAccreditationType());
        SupplierEvidenceTemplateVersion template = ensureActiveTemplate(
            application.getTenantId(), application.getCompanyId(), accreditationType);
        List<SupplierEvidenceRequirement> definitions = requirementRepository
            .findByTenantIdAndCompanyIdAndTemplateVersionIdAndIsDeletedFalseOrderByDisplayOrderAsc(
                application.getTenantId(), application.getCompanyId(), template.getId());
        LocalDate submitted = application.getSubmittedAt() == null
            ? LocalDate.now()
            : application.getSubmittedAt().toLocalDate();
        snapshotRepository.saveAll(definitions.stream()
            .map(definition -> snapshot(application, template, definition, submitted))
            .toList());
    }

    @Transactional
    public List<SupplierEvidenceChecklistItem> checklist(
        SupplierAccreditationApplication application,
        LocalDate asOfDate
    ) {
        requirePersistentApplication(application);
        LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
        List<SupplierAccreditationEvidenceRequirement> requirements = snapshotRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByDisplayOrderAsc(
                application.getTenantId(), application.getCompanyId(), application.getId());
        List<SupplierAccreditationDocument> applicationDocuments = documentRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
                application.getTenantId(), application.getCompanyId(), application.getId());
        return requirements.stream()
            .map(requirement -> resolve(application, requirement, applicationDocuments, today))
            .toList();
    }

    private SupplierEvidenceChecklistItem resolve(
        SupplierAccreditationApplication application,
        SupplierAccreditationEvidenceRequirement requirement,
        List<SupplierAccreditationDocument> applicationDocuments,
        LocalDate today
    ) {
        SupplierAccreditationDocument document = applicationDocuments.stream()
            .filter(candidate -> sameType(requirement.getDocumentType(), candidate.getDocumentType()))
            .max(Comparator.comparing(
                SupplierAccreditationDocument::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
        if (document == null && requirement.isReusable()) {
            document = acceptedDocumentTypes(requirement.getDocumentType()).stream()
                .flatMap(documentType -> documentRepository
                    .findReusableBySupplierAndDocumentType(
                        application.getTenantId(),
                        application.getCompanyId(),
                        application.getSupplierId(),
                        documentType)
                    .stream())
                .filter(candidate -> sameType(requirement.getDocumentType(), candidate.getDocumentType()))
                .filter(candidate -> evidencePolicy.isCurrentVerifiedEvidence(candidate, today))
                .findFirst()
                .orElse(null);
        }
        UUID linkedDocumentId = document == null ? null : document.getId();
        if (!Objects.equals(requirement.getLinkedDocumentId(), linkedDocumentId)) {
            requirement.setLinkedDocumentId(linkedDocumentId);
            snapshotRepository.save(requirement);
        }
        return new SupplierEvidenceChecklistItem(
            requirement.getId(),
            application.getId(),
            requirement.getRequirementCode(),
            requirement.getDocumentType(),
            requirement.isMandatory(),
            requirement.isReusable(),
            linkedDocumentId,
            state(requirement, document, today),
            document == null ? null : document.getValidTo(),
            requirement.getOwnerRole(),
            requirement.getDueDate()
        );
    }

    private EvidenceChecklistItemState state(
        SupplierAccreditationEvidenceRequirement requirement,
        SupplierAccreditationDocument document,
        LocalDate today
    ) {
        if ("LEGACY_VERIFIED_EVIDENCE".equals(requirement.getRequirementCode())
            && requirement.getDueDate() != null
            && requirement.getDueDate().isBefore(today)) {
            return EvidenceChecklistItemState.EXPIRED;
        }
        if (document == null) {
            return EvidenceChecklistItemState.MISSING;
        }
        if ("REJECTED".equals(document.getStatus())) {
            return EvidenceChecklistItemState.REJECTED;
        }
        if (document.getValidTo() != null
            && document.getValidTo().isBefore(today)) {
            return EvidenceChecklistItemState.EXPIRED;
        }
        if ("VERIFIED".equals(document.getStatus())
            && evidencePolicy.isCurrentVerifiedEvidence(document, today)) {
            return EvidenceChecklistItemState.VERIFIED;
        }
        if ("PENDING_VERIFICATION".equals(document.getStatus())) {
            return EvidenceChecklistItemState.UNDER_VERIFICATION;
        }
        return EvidenceChecklistItemState.UPLOADED;
    }

    private SupplierEvidenceTemplateVersion ensureActiveTemplate(
        String tenantId,
        UUID companyId,
        String accreditationType
    ) {
        return templateRepository
            .findFirstByTenantIdAndCompanyIdAndAccreditationTypeAndStatusAndIsDeletedFalseOrderByTemplateVersionDesc(
                tenantId, companyId, accreditationType, "ACTIVE")
            .orElseGet(() -> createDefaultTemplate(tenantId, companyId, accreditationType));
    }

    private SupplierEvidenceTemplateVersion createDefaultTemplate(
        String tenantId,
        UUID companyId,
        String accreditationType
    ) {
        List<Definition> defaults = SupplierEvidenceRequirementDefaults.forType(accreditationType);
        if (defaults.isEmpty()) {
            throw new BusinessException("Unsupported supplier accreditation type: " + accreditationType);
        }
        SupplierEvidenceTemplateVersion template = new SupplierEvidenceTemplateVersion();
        template.setTenantId(tenantId);
        template.setCompanyId(companyId);
        template.setCode(accreditationType + "-V1");
        template.setName(accreditationType + " evidence version 1");
        template.setStatus("ACTIVE");
        template.setAccreditationType(accreditationType);
        template.setTemplateVersion(1);
        template.setEffectiveFrom(LocalDate.now());
        SupplierEvidenceTemplateVersion saved = templateRepository.saveAndFlush(template);
        requirementRepository.saveAll(defaults.stream()
            .map(definition -> definition(saved, definition))
            .toList());
        return saved;
    }

    private SupplierEvidenceRequirement definition(
        SupplierEvidenceTemplateVersion template,
        Definition source
    ) {
        SupplierEvidenceRequirement requirement = new SupplierEvidenceRequirement();
        requirement.setTenantId(template.getTenantId());
        requirement.setCompanyId(template.getCompanyId());
        requirement.setCode(source.requirementCode());
        requirement.setName(readableName(source.requirementCode()));
        requirement.setStatus("ACTIVE");
        requirement.setTemplateVersionId(template.getId());
        requirement.setRequirementCode(source.requirementCode());
        requirement.setDocumentType(source.documentType());
        requirement.setMandatory(source.mandatory());
        requirement.setReusable(source.reusable());
        requirement.setValidityRequired(source.validityRequired());
        requirement.setOwnerRole(source.ownerRole());
        requirement.setSlaDays(source.slaDays());
        requirement.setDisplayOrder(source.displayOrder());
        return requirement;
    }

    private SupplierAccreditationEvidenceRequirement snapshot(
        SupplierAccreditationApplication application,
        SupplierEvidenceTemplateVersion template,
        SupplierEvidenceRequirement definition,
        LocalDate submitted
    ) {
        SupplierAccreditationEvidenceRequirement snapshot =
            new SupplierAccreditationEvidenceRequirement();
        snapshot.setTenantId(application.getTenantId());
        snapshot.setCompanyId(application.getCompanyId());
        snapshot.setCode(
            "REQ-" + application.getId().toString().substring(0, 8)
                + "-" + definition.getRequirementCode());
        snapshot.setName(definition.getName());
        snapshot.setStatus("ACTIVE");
        snapshot.setApplicationId(application.getId());
        snapshot.setTemplateVersionId(template.getId());
        snapshot.setRequirementCode(definition.getRequirementCode());
        snapshot.setDocumentType(definition.getDocumentType());
        snapshot.setMandatory(definition.isMandatory());
        snapshot.setReusable(definition.isReusable());
        snapshot.setValidityRequired(definition.isValidityRequired());
        snapshot.setOwnerRole(definition.getOwnerRole());
        snapshot.setDueDate(submitted.plusDays(definition.getSlaDays()));
        snapshot.setDisplayOrder(definition.getDisplayOrder());
        return snapshot;
    }

    private String normalizeType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "GOODS", "MATERIAL", "MATERIAL_GROUP" -> "GOODS";
            case "SERVICE", "SERVICES" -> "SERVICES";
            case "WORK", "WORKS" -> "WORKS";
            default -> normalized;
        };
    }

    private boolean sameType(String left, String right) {
        String normalizedLeft = canonicalDocumentType(left);
        String normalizedRight = canonicalDocumentType(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String canonicalDocumentType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return DOCUMENT_TYPE_ALIASES.getOrDefault(normalized, normalized);
    }

    private List<String> acceptedDocumentTypes(String documentType) {
        String canonical = canonicalDocumentType(documentType);
        if (canonical == null) {
            return List.of();
        }
        List<String> aliases = DOCUMENT_TYPE_ALIASES.entrySet().stream()
            .filter(entry -> canonical.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        return aliases.isEmpty() ? List.of(canonical) : List.of(canonical, aliases.get(0));
    }

    private String readableName(String code) {
        String normalized = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private void requirePersistentApplication(SupplierAccreditationApplication application) {
        if (application == null || application.getId() == null
            || application.getTenantId() == null || application.getCompanyId() == null) {
            throw new BusinessException("Persistent supplier accreditation application is required");
        }
    }
}
