package com.enterprise.mm.service;

import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalBuyer;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalDocument;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalProposalRevision;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqDetail;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqPosition;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.enterprise.mm.service.rfq.RfqMetrics;

@Service
@RequiredArgsConstructor
public class RfqSupplierPortalQueryService {
    private static final Set<String> CURRENT_STATUSES = Set.of(
        "DRAFT", "ACTIVE", "SUBMITTED", "SELECTED", "REJECTED"
    );

    private final RFQRepository rfqRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final RfqInvitationDeliveryRepository deliveryRepository;
    private final SupplierQuotationRepository quotationRepository;
    private final AttachmentRepository attachmentRepository;
    private final CompanyRepository companyRepository;
    private final SupplierRepository supplierRepository;
    private final AttachmentService attachmentService;
    private final AuditService auditService;
    private final TenderSupplierAccessService tenderSupplierAccessService;
    private RfqMetrics metrics;

    @Autowired
    void setMetrics(RfqMetrics metrics) {
        this.metrics = metrics;
    }
    private final ObjectMapper objectMapper;

    @Transactional
    public SupplierPortalRfqDetail detail(
        String tenantId,
        UUID companyId,
        UUID supplierId,
        UUID rfqId
    ) {
        Scope scope = requireScope(tenantId, companyId, supplierId, rfqId);
        markPortalOpened(scope.invitation(), scope.version(), supplierId);
        RfqPackageSnapshot snapshot = snapshot(scope.version());
        List<SupplierQuotation> revisions = quotationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndSupplierIdAndIsDeletedFalseOrderByRevisionNumberAsc(
                tenantId, companyId, rfqId, supplierId
            );
        List<SupplierPortalProposalRevision> proposalRevisions = revisions.stream()
            .sorted(Comparator.comparingInt(this::revision))
            .map(this::proposal)
            .toList();
        SupplierPortalProposalRevision current = proposalRevisions.stream()
            .filter(SupplierPortalProposalRevision::currentRevision)
            .filter(value -> CURRENT_STATUSES.contains(value.status()))
            .findFirst()
            .orElseGet(() -> proposalRevisions.stream()
                .filter(value -> CURRENT_STATUSES.contains(value.status()))
                .reduce((left, right) -> right)
                .orElse(null));
        List<RfqInvitationDelivery> deliveries = deliveries(scope.invitation());
        boolean declined = deliveries.stream().anyMatch(value -> value.getDeclinedAt() != null);
        return new SupplierPortalRfqDetail(
            scope.rfq().getId(),
            scope.version().getId(),
            scope.version().getVersionNumber(),
            snapshot.rfqCode(),
            snapshot.title(),
            snapshot.description(),
            snapshot.language(),
            scope.rfq().getStatus(),
            snapshot.dueDate(),
            scope.rfq().getTenderProcedureType(),
            scope.rfq().getSelectionRule(),
            snapshot.responseInstructions(),
            buyer(scope, snapshot),
            snapshot.positions().stream().map(position -> new SupplierPortalRfqPosition(
                position.rfqItemId(),
                position.materialCode(),
                position.materialName(),
                position.specification(),
                position.unit(),
                position.quantity(),
                position.requiredDate()
            )).toList(),
            documents(tenantId, companyId, scope.version(), snapshot),
            responseState(current, proposalRevisions, declined),
            current,
            proposalRevisions
        );
    }

    @Transactional(readOnly = true)
    public AttachmentService.AttachmentDownload download(
        String tenantId,
        UUID companyId,
        UUID supplierId,
        UUID rfqId,
        UUID attachmentId
    ) {
        Scope scope = requireScope(tenantId, companyId, supplierId, rfqId);
        Attachment attachment = attachmentRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, attachmentId)
            .orElseThrow(this::notFound);
        RfqPackageSnapshot snapshot = snapshot(scope.version());
        boolean packageArtifact = Objects.equals(attachmentId, scope.version().getPdfAttachmentId())
            || Objects.equals(attachmentId, scope.version().getExcelAttachmentId())
            || snapshot.documents().stream()
                .anyMatch(document -> Objects.equals(document.attachmentId(), attachmentId));
        boolean ownProposalArtifact = "SUPPLIER_QUOTATION_EVIDENCE".equals(attachment.getEntityType())
            && quotationRepository.findByIdAndIsDeletedFalse(attachment.getEntityId())
                .filter(value -> tenantId.equals(value.getTenantId()))
                .filter(value -> companyId.equals(value.getCompanyId()))
                .filter(value -> rfqId.equals(value.getRfqId()))
                .filter(value -> supplierId.equals(value.getSupplierId()))
                .isPresent();
        if (!packageArtifact && !ownProposalArtifact) {
            throw notFound();
        }
        return attachmentService.download(attachmentId);
    }

    private Scope requireScope(
        String tenantId,
        UUID companyId,
        UUID supplierId,
        UUID rfqId
    ) {
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(value -> tenantId.equals(value.getTenantId()))
            .filter(value -> companyId.equals(value.getCompanyId()))
            .filter(value -> value.getActivePublishedVersionId() != null)
            .orElseThrow(this::notFound);
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, supplierId)
            .filter(value -> companyId.equals(value.getCompanyId()))
            .orElseThrow(this::notFound);
        try {
            tenderSupplierAccessService.validateViewTender(rfq, supplier);
        } catch (BusinessException exception) {
            if ("Active RFQ supplier invitation is required".equals(exception.getMessage())) {
                throw notFound();
            }
            throw exception;
        }
        RfqSupplierInvitation invitation = tenderSupplierAccessService.activeInvitation(rfq, supplier).orElse(null);
        RfqPublishedVersion version = versionRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                tenantId, companyId, rfq.getActivePublishedVersionId()
            ).filter(value -> rfqId.equals(value.getRfqId()))
            .orElseThrow(this::notFound);
        return new Scope(rfq, invitation, version);
    }

    private void markPortalOpened(
        RfqSupplierInvitation invitation,
        RfqPublishedVersion version,
        UUID supplierId
    ) {
        if (invitation == null) {
            auditService.log("RFQ", version.getRfqId(), "SUPPLIER_PORTAL_OPEN", "supplier=" + supplierId, null, null);
            if (metrics != null) {
                metrics.portalOpen();
            }
            return;
        }
        RfqInvitationDelivery delivery = deliveryRepository
            .findByTenantIdAndCompanyIdAndPublishedVersionIdAndSupplierIdAndIsDeletedFalse(
                invitation.getTenantId(), invitation.getCompanyId(), version.getId(), supplierId
            ).orElse(null);
        if (delivery != null && delivery.getPortalOpenedAt() == null) {
            delivery.setPortalOpenedAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
            auditService.log("RFQ", invitation.getRfqId(), "SUPPLIER_PORTAL_OPEN", "supplier=" + supplierId, null, null);
            if (metrics != null) {
                metrics.portalOpen();
            }
        }
    }

    private List<RfqInvitationDelivery> deliveries(RfqSupplierInvitation invitation) {
        if (invitation == null) {
            return List.of();
        }
        return deliveryRepository
            .findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                invitation.getTenantId(), invitation.getCompanyId(), invitation.getId()
            );
    }

    private SupplierPortalBuyer buyer(Scope scope, RfqPackageSnapshot snapshot) {
        RfqPackageSnapshot.Buyer snapshotBuyer = snapshot.buyer();
        Company company = companyRepository
            .findByTenantIdAndIdAndIsDeletedFalse(scope.rfq().getTenantId(), scope.rfq().getCompanyId())
            .orElse(null);
        if (company == null && snapshotBuyer == null) {
            return null;
        }
        return new SupplierPortalBuyer(
            localizedPreferred(
                company == null ? null : company.getLegalName(),
                company == null ? null : company.getName(),
                snapshotBuyer == null ? null : snapshotBuyer.companyName()
            ),
            localizedPreferred(
                company == null ? null : company.getLegalAddress(),
                snapshotBuyer == null ? null : snapshotBuyer.address()
            ),
            localizedPreferred(snapshotBuyer == null ? null : snapshotBuyer.contactName()),
            firstNonBlank(
                company == null ? null : company.getEmail(),
                snapshotBuyer == null ? null : snapshotBuyer.email()
            ),
            firstNonBlank(
                company == null ? null : company.getPhone(),
                snapshotBuyer == null ? null : snapshotBuyer.phone()
            )
        );
    }

    private String localizedPreferred(String... values) {
        for (String value : values) {
            if (LocalizedText.isMultilingual(value)) {
                return LocalizedText.forCurrentLocale(value);
            }
        }
        return LocalizedText.forCurrentLocale(firstNonBlank(values));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<SupplierPortalDocument> documents(
        String tenantId,
        UUID companyId,
        RfqPublishedVersion version,
        RfqPackageSnapshot snapshot
    ) {
        Map<UUID, String> documentTypes = new LinkedHashMap<>();
        if (version.getPdfAttachmentId() != null) {
            documentTypes.put(version.getPdfAttachmentId(), "OFFICIAL_PDF");
        }
        if (version.getExcelAttachmentId() != null) {
            documentTypes.put(version.getExcelAttachmentId(), "QUOTATION_TEMPLATE");
        }
        snapshot.documents().forEach(document ->
            documentTypes.put(document.attachmentId(), "SUPPORTING_DOCUMENT")
        );
        Map<UUID, Attachment> attachments = attachmentRepository.findAllById(documentTypes.keySet()).stream()
            .filter(value -> tenantId.equals(value.getTenantId()))
            .filter(value -> companyId.equals(value.getCompanyId()))
            .filter(value -> !value.isDeleted())
            .collect(Collectors.toMap(Attachment::getId, Function.identity()));
        return documentTypes.entrySet().stream().map(entry -> {
            Attachment attachment = attachments.get(entry.getKey());
            return attachment == null ? null : new SupplierPortalDocument(
                attachment.getId(),
                entry.getValue(),
                attachment.getOriginalFileName(),
                attachment.getContentType()
            );
        }).filter(Objects::nonNull).toList();
    }

    private SupplierPortalProposalRevision proposal(SupplierQuotation value) {
        return new SupplierPortalProposalRevision(
            value.getId(),
            revision(value),
            value.getStatus(),
            value.getSource(),
            value.getSubmittedAt(),
            value.getCurrencyCode(),
            value.getTotalAmount(),
            value.isCurrentRevision() || value.getThreadId() == null,
            value.getSupersessionReason()
        );
    }

    private int revision(SupplierQuotation value) {
        return value.getRevisionNumber() == null ? 1 : value.getRevisionNumber();
    }

    private String responseState(
        SupplierPortalProposalRevision current,
        List<SupplierPortalProposalRevision> revisions,
        boolean declined
    ) {
        if (current != null) {
            return "DRAFT".equals(current.status()) ? "DRAFT" : "SUBMITTED";
        }
        if (revisions.stream().anyMatch(value -> "DRAFT".equals(value.status()))) {
            return "DRAFT";
        }
        if (declined || revisions.stream().anyMatch(value -> "DECLINED".equals(value.status()))) {
            return "DECLINED";
        }
        return "NONE";
    }

    private RfqPackageSnapshot snapshot(RfqPublishedVersion version) {
        try {
            var tree = objectMapper.readTree(version.getSnapshotJson());
            return tree.isTextual()
                ? objectMapper.readValue(tree.asText(), RfqPackageSnapshot.class)
                : objectMapper.treeToValue(tree, RfqPackageSnapshot.class);
        } catch (Exception exception) {
            throw notFound();
        }
    }

    private NotFoundException notFound() {
        return new NotFoundException("RFQ not found");
    }

    private record Scope(
        RFQ rfq,
        RfqSupplierInvitation invitation,
        RfqPublishedVersion version
    ) {
    }
}
