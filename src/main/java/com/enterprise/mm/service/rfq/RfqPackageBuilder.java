package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Buyer;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.Position;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SourceReference;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupportingDocument;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
public class RfqPackageBuilder {
    private final RFQRepository rfqRepository;
    private final RFQItemRepository itemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final CompanyRepository companyRepository;
    private final AttachmentRepository attachmentRepository;

    @Transactional(readOnly = true)
    public RfqPackageSnapshot build(UUID rfqId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(candidate -> tenantId.equals(candidate.getTenantId()))
            .filter(candidate -> companyId.equals(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + rfqId));

        List<RFQItem> items = itemRepository.findByTenantIdAndRfqIdOrderById(tenantId, rfqId);
        List<RFQPurchaseRequestSource> sources = sourceRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId,
                companyId,
                rfqId
            );
        List<RfqSupplierInvitation> invitations = invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                tenantId,
                companyId,
                rfqId
            );
        Company company = companyRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, companyId)
            .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        List<Attachment> attachments = attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId,
                companyId,
                "RFQ",
                rfqId
            );

        Map<UUID, LocalDate> requiredDateByItem = new LinkedHashMap<>();
        for (RFQPurchaseRequestSource source : sources) {
            if (source.getRfqItemId() != null && source.getSourceRequiredDate() != null) {
                requiredDateByItem.merge(
                    source.getRfqItemId(),
                    source.getSourceRequiredDate(),
                    (left, right) -> left.isBefore(right) ? left : right
                );
            }
        }

        String language = normalizeLanguage(rfq.getPackageLanguage());
        return new RfqPackageSnapshot(
            rfq.getId(),
            rfq.getCode(),
            localized(rfq.getName(), language),
            localized(rfq.getDescription(), language),
            language,
            rfq.getDueDate(),
            buyer(company, rfq, language),
            localized(rfq.getResponseInstructions(), language),
            items.stream().map(item -> position(
                item,
                requiredDateByItem.get(item.getId()),
                rfq.getDueDate(),
                language
            )).toList(),
            sourceReferences(rfq, sources, language),
            invitations.stream()
                .filter(invitation -> "INVITED".equals(invitation.getStatus()))
                .map(RfqSupplierInvitation::getSupplier)
                .filter(Objects::nonNull)
                .map(supplier -> recipient(supplier, language))
                .sorted(Comparator.comparing(recipient -> recipient.supplierId().toString()))
                .toList(),
            attachments.stream().map(this::document).toList()
        );
    }

    private Buyer buyer(Company company, RFQ rfq, String language) {
        return new Buyer(
            localized(firstNonBlank(company.getLegalName(), company.getName()), language),
            localized(company.getLegalAddress(), language),
            localized(rfq.getResponsibleBuyer(), language),
            company.getEmail(),
            company.getPhone()
        );
    }

    private Position position(
        RFQItem item,
        LocalDate sourceRequiredDate,
        LocalDate fallbackDate,
        String language
    ) {
        Material material = item.getMaterial();
        String materialCode = material == null ? item.getCode() : material.getCode();
        String materialName = material == null ? item.getName() : material.getName();
        String specification = material == null
            ? firstNonBlank(item.getServiceDescription(), item.getDescription())
            : firstNonBlank(item.getDescription(), material.getDescription());
        return new Position(
            item.getId(),
            materialCode,
            localized(materialName, language),
            localized(specification, language),
            item.getUnit(),
            item.getQuantity(),
            sourceRequiredDate == null ? fallbackDate : sourceRequiredDate
        );
    }

    private List<SourceReference> sourceReferences(
        RFQ rfq,
        List<RFQPurchaseRequestSource> sources,
        String language
    ) {
        Map<UUID, SourceReference> unique = new LinkedHashMap<>();
        for (RFQPurchaseRequestSource source : sources) {
            PurchaseRequest purchaseRequest = source.getPurchaseRequest();
            if (source.getPurchaseRequestId() != null && purchaseRequest != null) {
                unique.putIfAbsent(
                    source.getPurchaseRequestId(),
                    new SourceReference(
                        purchaseRequest.getCode(),
                        localized(purchaseRequest.getName(), language)
                    )
                );
            }
        }
        if (unique.isEmpty() && rfq.getPurchaseRequest() != null) {
            PurchaseRequest purchaseRequest = rfq.getPurchaseRequest();
            unique.put(
                purchaseRequest.getId(),
                new SourceReference(
                    purchaseRequest.getCode(),
                    localized(purchaseRequest.getName(), language)
                )
            );
        }
        return List.copyOf(unique.values());
    }

    private SupplierRecipient recipient(Supplier supplier, String language) {
        return new SupplierRecipient(
            supplier.getId(),
            localized(firstNonBlank(supplier.getLegalName(), supplier.getName()), language),
            localized(supplier.getDirectorName(), language),
            supplier.getEmail()
        );
    }

    private String localized(String value, String language) {
        return LocalizedText.translate(value, Locale.forLanguageTag(language));
    }

    private String normalizeLanguage(String language) {
        if (language == null) {
            return LocalizedText.DEFAULT_LANGUAGE;
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "en" -> "en";
            case "uz" -> "uz";
            default -> LocalizedText.DEFAULT_LANGUAGE;
        };
    }

    private SupportingDocument document(Attachment attachment) {
        return new SupportingDocument(
            attachment.getId(),
            attachment.getOriginalFileName(),
            attachment.getContentType()
        );
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
