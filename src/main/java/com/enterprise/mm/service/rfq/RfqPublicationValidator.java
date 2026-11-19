package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.RfqPurpose;
import com.enterprise.mm.payload.RfqPublicationDtos.ValidationIssue;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RfqPublicationValidator {
    private static final Set<String> PUBLISHABLE_STATUSES = Set.of("DRAFT", "AMENDMENT_DRAFT");
    private static final Set<String> SUPPLIER_DELIVERY_BLOCKER_CODES = Set.of(
        SupplierPortalEligibilityValidator.SUPPLIER_EMAIL_INVALID,
        SupplierPortalEligibilityValidator.PORTAL_EMAIL_CONFLICT
    );

    private final RFQItemRepository itemRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final SupplierPortalEligibilityValidator supplierPortalEligibilityValidator;
    private final AttachmentRepository attachmentRepository;
    private final ProcurementProcedureRepository procedureRepository;
    private final List<AttachmentStorage> storages;
    private final RfqMailProperties mailProperties;
    private final Clock clock;



    @Transactional(readOnly = true)
    public List<ValidationIssue> validate(RFQ rfq) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (rfq == null) {
            return List.of(issue("RFQ_NOT_FOUND", "general", null));
        }
        if (!PUBLISHABLE_STATUSES.contains(rfq.getStatus())) {
            issues.add(issue("RFQ_STATUS_NOT_PUBLISHABLE", "general", null));
        }
        if (rfq.getDueDate() == null) {
            issues.add(issue("RFQ_DUE_DATE_REQUIRED", "general", null));
        } else if (rfq.getDueDate().isBefore(LocalDate.now(clock))) {
            issues.add(issue("RFQ_DUE_DATE_EXPIRED", "general", null));
        }
        String language = normalize(rfq.getPackageLanguage());
        if (language == null || !Set.of("ru", "uz", "en").contains(language)) {
            issues.add(issue("RFQ_PACKAGE_LANGUAGE_INVALID", "general", null));
        }
        validateTenderPriceBasis(rfq, issues);

        List<RFQItem> items = items(rfq.getId());
        if (items.isEmpty()) {
            issues.add(issue("RFQ_ITEMS_REQUIRED", "positions", null));
        }

        List<RfqSupplierInvitation> invitations = invitations(rfq.getId());
        if (requiresInvitations(rfq) && invitations.isEmpty()) {
            issues.add(issue("RFQ_SUPPLIERS_REQUIRED", "suppliers", null));
        } else if (requiresInvitations(rfq)) {
            int minimum = rfq.getMinQuotationsRequired() == null ? 1
                : Math.max(1, rfq.getMinQuotationsRequired());
            long invitedSuppliers = invitations.stream().map(RfqSupplierInvitation::getSupplierId)
                .filter(Objects::nonNull).distinct().count();
            if (invitedSuppliers < minimum) {
                issues.add(issue("RFQ_MINIMUM_SUPPLIERS_NOT_MET", "suppliers", null));
            }
        }
        for (RfqSupplierInvitation invitation : invitations) {
            Supplier supplier = invitation.getSupplier();
            UUID supplierId = invitation.getSupplierId();
            if (supplier == null) {
                issues.add(issue("RFQ_SUPPLIER_NOT_FOUND", "suppliers", supplierId));
                continue;
            }
            supplierPortalEligibilityValidator.validate(supplier)
                .forEach(eligibilityIssue -> issues.add(issue(eligibilityIssue.code(), "suppliers", supplierId)));
        }
        for (Attachment attachment : attachments(rfq.getId())) {
            if (!readable(attachment)) {
                issues.add(issue("RFQ_DOCUMENT_MISSING", "documents", null));
            }
        }
        if (!mailConfigured()) {
            issues.add(issue("RFQ_MAIL_NOT_CONFIGURED", "delivery", null));
        }
        return List.copyOf(issues);
    }

    @Transactional(readOnly = true)
    public List<ValidationIssue> blockingIssues(RFQ rfq) {
        List<ValidationIssue> issues = validate(rfq);
        boolean hasSupplierDeliveryBlocker = issues.stream().anyMatch(RfqPublicationValidator::isSupplierDeliveryBlocker);
        if (!hasSupplierDeliveryBlocker) {
            return issues;
        }
        List<ValidationIssue> blocking = issues.stream()
            .filter(issue -> !isSupplierDeliveryBlocker(issue))
            .collect(Collectors.toCollection(ArrayList::new));
        if (requiresInvitations(rfq) && invitedSupplierCount(rfq) <= supplierDeliveryBlockerCount(issues)) {
            blocking.add(issue("RFQ_NO_ELIGIBLE_SUPPLIERS", "suppliers", null));
        }
        return List.copyOf(blocking);
    }

    private void validateTenderPriceBasis(RFQ rfq, List<ValidationIssue> issues) {
        if (rfq.getProcurementProcedureId() == null
            || !RfqPurpose.PROCUREMENT.name().equals(rfq.getPurpose())
            || !Set.of(ProcurementMethodType.OPEN_TENDER.name(), ProcurementMethodType.CLOSED_TENDER.name())
                .contains(rfq.getTenderProcedureType())) {
            return;
        }
        ProcurementProcedure procedure = procedureRepository.findByIdAndIsDeletedFalse(rfq.getProcurementProcedureId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> CompanyContext.currentCompany().equals(candidate.getCompanyId()))
            .orElse(null);
        if (procedure == null) {
            issues.add(issue("RFQ_PROCUREMENT_PROCEDURE_REQUIRED", "general", null));
            return;
        }
        if (!procedure.isRequiresPriceDiscovery()) {
            return;
        }
        if (procedure.getPriceBasisCalculationId() == null
            || rfq.getPriceBasisCalculationId() == null
            || !Objects.equals(procedure.getPriceBasisCalculationId(), rfq.getPriceBasisCalculationId())) {
            issues.add(issue("RFQ_TENDER_PRICE_BASIS_REQUIRED", "general", null));
        }
    }

    public void revalidateLocked(RFQ rfq, RfqPackageSnapshot snapshot) {
        List<ValidationIssue> issues = new ArrayList<>(blockingIssues(rfq));
        Set<UUID> currentItemIds = items(rfq.getId()).stream().map(RFQItem::getId).collect(Collectors.toSet());
        Set<UUID> snapshotItemIds = snapshot.positions().stream().map(RfqPackageSnapshot.Position::rfqItemId)
            .collect(Collectors.toSet());
        if (!currentItemIds.equals(snapshotItemIds)) {
            issues.add(issue("RFQ_PACKAGE_STALE", "positions", null));
        }
        if (requiresInvitations(rfq)) {
            Map<UUID, String> currentRecipients = new LinkedHashMap<>();
            for (RfqSupplierInvitation invitation : invitations(rfq.getId())) {
                if (invitation.getSupplier() != null) {
                    currentRecipients.put(invitation.getSupplierId(), normalizeEmail(invitation.getSupplier().getEmail()));
                }
            }
            Map<UUID, String> snapshotRecipients = new LinkedHashMap<>();
            snapshot.suppliers().forEach(recipient -> snapshotRecipients.put(
                recipient.supplierId(),
                normalizeEmail(recipient.email())
            ));
            if (!currentRecipients.equals(snapshotRecipients)) {
                issues.add(issue("RFQ_PACKAGE_STALE", "suppliers", null));
            }
        }
        if (!issues.isEmpty()) {
            throw new RfqPublicationValidationException(issues.stream().distinct().toList());
        }
    }

    public static ValidationIssue issue(String code, String section, UUID supplierId) {
        String messageKey = "rfq.publication." + code.toLowerCase(Locale.ROOT).replace('_', '.');
        return new ValidationIssue(code, section, supplierId, messageKey);
    }

    private List<RFQItem> items(UUID rfqId) {
        return itemRepository.findByTenantIdAndRfqIdOrderById(TenantContext.currentTenant(), rfqId);
    }

    private List<RfqSupplierInvitation> invitations(UUID rfqId) {
        return invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfqId
            )
            .stream()
            .filter(invitation -> "INVITED".equals(invitation.getStatus()))
            .toList();
    }

    private long invitedSupplierCount(RFQ rfq) {
        if (rfq == null || rfq.getId() == null) {
            return 0;
        }
        return invitations(rfq.getId()).stream()
            .map(RfqSupplierInvitation::getSupplierId)
            .filter(Objects::nonNull)
            .distinct()
            .count();
    }

    private long supplierDeliveryBlockerCount(List<ValidationIssue> issues) {
        return issues.stream()
            .filter(RfqPublicationValidator::isSupplierDeliveryBlocker)
            .map(ValidationIssue::supplierId)
            .filter(Objects::nonNull)
            .distinct()
            .count();
    }

    public static boolean isSupplierDeliveryBlocker(ValidationIssue issue) {
        return issue != null
            && issue.supplierId() != null
            && SUPPLIER_DELIVERY_BLOCKER_CODES.contains(issue.code());
    }

    private boolean requiresInvitations(RFQ rfq) {
        return !RfqSupplierAccessPolicy.usesOpenSupplierAccess(rfq);
    }

    private List<Attachment> attachments(UUID rfqId) {
        return attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                "RFQ",
                rfqId
            );
    }

    private boolean readable(Attachment attachment) {
        try {
            AttachmentStorage storage = storages.stream()
                .filter(candidate -> candidate.supports(attachment.getStorageProvider()))
                .findFirst()
                .orElse(null);
            if (storage == null) {
                return false;
            }
            try (var input = storage.read(attachment.getStorageKey()).getInputStream()) {
                input.readNBytes(1);
                return true;
            }
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean mailConfigured() {
        return mailProperties.isEnabled()
            && validMailAddress(normalizeEmail(mailProperties.getFrom()))
            && validMailAddress(normalizeEmail(mailProperties.getReplyTo()));
    }

    private String normalizeEmail(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean codeEquals(String expected, String actual) {
        String normalized = normalize(actual);
        return expected.equals(normalized == null ? null : normalized.toUpperCase(Locale.ROOT));
    }

    private boolean validMailAddress(String email) {
        return supplierPortalEligibilityValidator.validEmailAddress(email);
    }
}
