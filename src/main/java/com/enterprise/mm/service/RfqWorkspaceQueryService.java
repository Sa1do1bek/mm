package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.RfqWorkspaceDtos.AuditSummary;
import com.enterprise.mm.payload.RfqWorkspaceDtos.DeliveryAttempt;
import com.enterprise.mm.payload.RfqWorkspaceDtos.DocumentSummary;
import com.enterprise.mm.payload.RfqWorkspaceDtos.InvitedSupplierProgress;
import com.enterprise.mm.payload.RfqWorkspaceDtos.ProposalLineSummary;
import com.enterprise.mm.payload.RfqWorkspaceDtos.ProposalRevisionSummary;
import com.enterprise.mm.payload.RfqWorkspaceDtos.ProposalSummary;
import com.enterprise.mm.payload.RfqWorkspaceDtos.PurchaseRequestSource;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqOverview;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqPosition;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqQuotationComparison;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceDetail;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceFilter;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceRow;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.RfqWorkspaceQueryRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RfqWorkspaceQueryService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 4;
    private static final List<String> CURRENT_PROPOSAL_STATUSES = List.of(
        "ACTIVE", "SUBMITTED", "SELECTED", "REJECTED"
    );
    private static final Set<String> COMMERCIAL_OPEN_STATUSES = Set.of(
        "BIDS_OPENED", "CONFLICT_CHECKED", "EVALUATED", "AWARDED"
    );

    private final RfqWorkspaceQueryRepository workspaceRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository itemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final MaterialRepository materialRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final RfqInvitationDeliveryRepository deliveryRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierQuotationRepository quotationRepository;
    private final SupplierQuotationItemRepository quotationItemRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final AttachmentRepository attachmentRepository;
    private final AuditLogRepository auditRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<RfqWorkspaceRow> search(RfqWorkspaceFilter filter, Pageable pageable) {
        validate(filter);
        return workspaceRepository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter,
            roleDataScopeService.currentScope(),
            LocalDate.now(clock),
            pageable
        );
    }

    @Transactional(readOnly = true)
    public RfqWorkspaceDetail detail(UUID rfqId) {
        RFQ rfq = requireRfq(rfqId);
        List<RFQPurchaseRequestSource> sources = sources(rfqId);
        List<PurchaseRequest> purchaseRequests = purchaseRequests(rfq, sources);
        requireDocumentAccess(rfq, purchaseRequests);
        List<RFQItem> items = itemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), rfqId
        ).stream().filter(this::inCompany).toList();
        Map<UUID, Material> materials = materialRepository.findAllById(items.stream()
            .map(RFQItem::getMaterialId).filter(Objects::nonNull).collect(Collectors.toSet()))
            .stream().filter(this::inCompany).collect(Collectors.toMap(Material::getId, Function.identity()));
        List<RfqSupplierInvitation> invitations = invitations(rfqId);
        List<RfqInvitationDelivery> deliveries = deliveries(invitations);
        List<SupplierQuotation> revisions = revisions(rfqId);
        Map<UUID, List<SupplierQuotationItem>> lines = proposalLines(revisions);
        Map<UUID, Supplier> suppliers = suppliers(invitations, revisions);
        List<RfqPublishedVersion> versions = versionRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByVersionNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
            );
        Map<UUID, RFQItem> rfqItems = items.stream().collect(Collectors.toMap(RFQItem::getId, Function.identity()));
        boolean commercialOpen = commercialContentsVisible(rfq);
        List<ProposalSummary> proposals = commercialOpen
            ? proposals(revisions, lines, suppliers, rfqItems)
            : List.of();
        List<SupplierQuotation> documentRevisions = commercialOpen ? revisions : List.of();
        int proposalCount = (int) revisions.stream().filter(this::currentSubmitted)
            .map(SupplierQuotation::getSupplierId)
            .distinct()
            .count();
        int failedCount = (int) deliveries.stream().filter(value -> "FAILED".equals(value.getStatus())).count();
        int declinedCount = (int) deliveries.stream()
            .filter(value -> value.getDeclinedAt() != null)
            .map(RfqInvitationDelivery::getSupplierId)
            .distinct()
            .count();
        RfqPublishedVersion activeVersion = versions.stream()
            .filter(value -> Objects.equals(value.getId(), rfq.getActivePublishedVersionId()))
            .findFirst().orElse(null);
        return new RfqWorkspaceDetail(
            overview(rfq, activeVersion, invitations.size(), proposalCount, failedCount, declinedCount),
            purchaseRequests.stream().map(this::purchaseRequest).toList(),
            positions(items, materials, sources),
            invitedSuppliers(invitations, deliveries, revisions, suppliers),
            proposals,
            documents(versions, documentRevisions),
            audit(rfqId, invitations, deliveries, revisions, versions)
        );
    }

    @Transactional(readOnly = true)
    public RfqQuotationComparison comparison(UUID rfqId) {
        RFQ rfq = requireRfq(rfqId);
        if (!commercialContentsVisible(rfq)) {
            throw new BusinessException("Tender bid contents are sealed until formal bid opening");
        }
        RfqWorkspaceDetail detail = detail(rfqId);
        List<ProposalSummary> proposals = detail.proposals().stream()
            .filter(value -> value.current() != null)
            .toList();
        List<String> currencies = proposals.stream()
            .map(value -> value.current().currencyCode())
            .filter(Objects::nonNull)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
        return new RfqQuotationComparison(rfqId, currencies.size() <= 1, currencies, proposals);
    }

    private RfqWorkspaceFilter validate(RfqWorkspaceFilter filter) {
        if (filter == null) {
            return null;
        }
        if (filter.dueDateFrom() != null && filter.dueDateTo() != null
            && filter.dueDateFrom().isAfter(filter.dueDateTo())) {
            throw new BusinessException("dueDateFrom must not be after dueDateTo");
        }
        if (filter.responseProgress() != null
            && !Set.of("NONE", "PARTIAL", "COMPLETE", "DECLINED")
                .contains(filter.responseProgress().trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException("Unsupported RFQ response progress filter");
        }
        return filter;
    }

    private RFQ requireRfq(UUID rfqId) {
        return rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(this::inCompany)
            .orElseThrow(() -> new NotFoundException("RFQ not found"));
    }

    private List<RFQPurchaseRequestSource> sources(UUID rfqId) {
        return sourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
        );
    }

    private List<PurchaseRequest> purchaseRequests(
        RFQ rfq,
        List<RFQPurchaseRequestSource> sources
    ) {
        LinkedHashSet<UUID> ids = sources.stream().map(RFQPurchaseRequestSource::getPurchaseRequestId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (rfq.getPurchaseRequestId() != null) {
            ids.add(rfq.getPurchaseRequestId());
        }
        Map<UUID, PurchaseRequest> byId = purchaseRequestRepository.findAllById(ids).stream()
            .filter(this::inCompany)
            .collect(Collectors.toMap(PurchaseRequest::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private void requireDocumentAccess(RFQ rfq, List<PurchaseRequest> requests) {
        if (roleDataScopeService.canAccessDocument(
            rfq.getCreatedBy(), rfq.getResponsibleBuyer(), null
        )) {
            return;
        }
        boolean sourceAccess = requests.stream().anyMatch(request -> roleDataScopeService.canAccessDocument(
            request.getCreatedBy(), request.getRequestedBy(), request.getDepartmentId()
        ));
        if (!sourceAccess) {
            throw new NotFoundException("RFQ not found");
        }
    }

    private List<RfqSupplierInvitation> invitations(UUID rfqId) {
        return invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
            )
            .stream()
            .filter(invitation -> "INVITED".equals(invitation.getStatus()))
            .toList();
    }

    private List<RfqInvitationDelivery> deliveries(List<RfqSupplierInvitation> invitations) {
        List<UUID> ids = invitations.stream().map(RfqSupplierInvitation::getId).toList();
        return ids.isEmpty() ? List.of() : deliveryRepository.findForInvitations(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), ids
        );
    }

    private List<SupplierQuotation> revisions(UUID rfqId) {
        return quotationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderBySupplierIdAscRevisionNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
            );
    }

    private Map<UUID, List<SupplierQuotationItem>> proposalLines(List<SupplierQuotation> revisions) {
        List<UUID> ids = revisions.stream().map(SupplierQuotation::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return quotationItemRepository
            .findByTenantIdAndCompanyIdAndQuotationIdInAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids
            ).stream().collect(Collectors.groupingBy(
                SupplierQuotationItem::getQuotationId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
    }

    private Map<UUID, Supplier> suppliers(
        List<RfqSupplierInvitation> invitations,
        List<SupplierQuotation> revisions
    ) {
        Set<UUID> ids = new LinkedHashSet<>();
        invitations.forEach(value -> ids.add(value.getSupplierId()));
        revisions.forEach(value -> ids.add(value.getSupplierId()));
        return supplierRepository.findAllById(ids).stream()
            .filter(this::inCompany)
            .collect(Collectors.toMap(Supplier::getId, Function.identity()));
    }

    private RfqOverview overview(
        RFQ rfq,
        RfqPublishedVersion version,
        int invitationCount,
        int proposalCount,
        int failedCount,
        int declinedCount
    ) {
        return new RfqOverview(
            rfq.getId(),
            rfq.getCode(),
            rfq.getName(),
            rfq.getName(),
            rfq.getDescription(),
            rfq.getStatus(),
            rfq.getDueDate(),
            rfq.getResponsibleBuyer(),
            rfq.getTenderProcedureType(),
            rfq.getSelectionRule(),
            rfq.getMinQuotationsRequired(),
            rfq.getCurrencyCode(),
            rfq.getPackageLanguage(),
            rfq.getActivePublishedVersionId(),
            version == null ? null : version.getVersionNumber(),
            responseProgress(invitationCount, proposalCount, declinedCount),
            invitationCount,
            proposalCount,
            failedCount,
            rfq.getCreatedAt(),
            rfq.getPublishedAt(),
            rfq.getClosedAt()
        );
    }

    private PurchaseRequestSource purchaseRequest(PurchaseRequest request) {
        return new PurchaseRequestSource(
            request.getId(),
            request.getCode(),
            request.getName(),
            request.getStatus(),
            request.getRequestedBy(),
            request.getDepartmentId(),
            request.getRequiredDate()
        );
    }

    private List<RfqPosition> positions(
        List<RFQItem> items,
        Map<UUID, Material> materials,
        List<RFQPurchaseRequestSource> sources
    ) {
        Map<UUID, LocalDate> requiredDates = new HashMap<>();
        sources.stream().filter(value -> value.getSourceRequiredDate() != null).forEach(value ->
            requiredDates.merge(
                value.getRfqItemId(), value.getSourceRequiredDate(),
                (left, right) -> left.isBefore(right) ? left : right
            )
        );
        return items.stream().sorted(Comparator.comparing(RFQItem::getCreatedAt)
            .thenComparing(RFQItem::getId)).map(item -> {
                Material material = materials.get(item.getMaterialId());
                return new RfqPosition(
                    item.getId(),
                    item.getLineType(),
                    item.getMaterialId(),
                    material == null ? null : material.getCode(),
                    material == null ? item.getName() : material.getName(),
                    material == null ? null : material.getMaterialGroupId(),
                    material == null || material.getMaterialGroup() == null ? null : material.getMaterialGroup().getName(),
                    first(item.getDescription(), material == null ? null : material.getDescription()),
                    item.getUnit(),
                    item.getQuantity(),
                    item.getServiceDescription(),
                    item.getServiceCategory(),
                    item.getPeriod(),
                    item.getStage(),
                    requiredDates.get(item.getId())
                ).withUnitShortName(unitDisplay.shortName(item, item.getUnit(), material));
            }).toList();
    }

    private List<InvitedSupplierProgress> invitedSuppliers(
        List<RfqSupplierInvitation> invitations,
        List<RfqInvitationDelivery> deliveries,
        List<SupplierQuotation> revisions,
        Map<UUID, Supplier> suppliers
    ) {
        Map<UUID, List<RfqInvitationDelivery>> byInvitation = deliveries.stream().collect(
            Collectors.groupingBy(RfqInvitationDelivery::getInvitationId, LinkedHashMap::new, Collectors.toList())
        );
        Map<UUID, SupplierQuotation> current = revisions.stream()
            .filter(this::currentSubmitted)
            .collect(Collectors.toMap(
                SupplierQuotation::getSupplierId,
                Function.identity(),
                (left, right) -> revision(left) >= revision(right) ? left : right
            ));
        return invitations.stream().map(invitation -> {
            Supplier supplier = suppliers.get(invitation.getSupplierId());
            List<RfqInvitationDelivery> attempts = byInvitation.getOrDefault(invitation.getId(), List.of());
            RfqInvitationDelivery latest = attempts.isEmpty() ? null : attempts.get(0);
            SupplierQuotation quotation = current.get(invitation.getSupplierId());
            return new InvitedSupplierProgress(
                invitation.getId(),
                invitation.getSupplierId(),
                supplier == null ? null : supplier.getCode(),
                supplier == null ? null : supplier.getName(),
                invitation.getStatus(),
                latest == null ? null : latest.getRecipientName(),
                latest == null ? null : latest.getRecipientEmail(),
                latest == null ? null : latest.getPortalOpenedAt(),
                latest == null ? null : latest.getDeclinedAt(),
                latest == null ? null : latest.getQuotationReceivedAt(),
                quotation == null ? null : quotation.getStatus(),
                quotation == null ? null : quotation.getSource(),
                quotation == null ? null : quotation.getId(),
                attempts.stream().map(this::delivery).toList()
            );
        }).toList();
    }

    private DeliveryAttempt delivery(RfqInvitationDelivery value) {
        return new DeliveryAttempt(
            value.getId(),
            value.getVersion() == null ? 0L : value.getVersion(),
            value.getPublishedVersionId(),
            value.getStatus(),
            value.getRecipientEmail(),
            value.getRecipientName(),
            value.getRetryCount(),
            value.getFirstSentAt(),
            value.getLastSentAt(),
            value.getDeliveredAt(),
            value.getPortalOpenedAt(),
            value.getDeclinedAt(),
            value.getQuotationReceivedAt(),
            value.getLastErrorCategory()
        );
    }

    private List<ProposalSummary> proposals(
        List<SupplierQuotation> revisions,
        Map<UUID, List<SupplierQuotationItem>> lines,
        Map<UUID, Supplier> suppliers,
        Map<UUID, RFQItem> rfqItems
    ) {
        return revisions.stream().collect(Collectors.groupingBy(
            SupplierQuotation::getSupplierId, LinkedHashMap::new, Collectors.toList()
        )).entrySet().stream().map(entry -> {
            Supplier supplier = suppliers.get(entry.getKey());
            List<ProposalRevisionSummary> history = entry.getValue().stream()
                .sorted(Comparator.comparingInt(this::revision))
                .map(value -> proposal(value, lines.getOrDefault(value.getId(), List.of()), rfqItems))
                .toList();
            ProposalRevisionSummary current = history.stream()
                .filter(ProposalRevisionSummary::currentRevision)
                .filter(value -> CURRENT_PROPOSAL_STATUSES.contains(value.status()))
                .findFirst()
                .orElseGet(() -> entry.getValue().stream()
                    .filter(value -> value.getThreadId() == null)
                    .filter(this::currentSubmitted)
                    .findFirst()
                    .map(value -> proposal(value, lines.getOrDefault(value.getId(), List.of()), rfqItems))
                    .orElse(null));
            return new ProposalSummary(
                entry.getKey(),
                supplier == null ? null : supplier.getCode(),
                supplier == null ? null : supplier.getName(),
                current,
                history
            );
        }).toList();
    }

    private ProposalRevisionSummary proposal(
        SupplierQuotation value,
        List<SupplierQuotationItem> lines,
        Map<UUID, RFQItem> rfqItems
    ) {
        return new ProposalRevisionSummary(
            value.getId(),
            value.getThreadId(),
            value.getSupplierId(),
            revision(value),
            value.getStatus(),
            value.getSource(),
            value.getReceivedAt(),
            value.getSubmittedAt(),
            value.getRecordedBy(),
            value.getCurrencyCode(),
            value.getTotalAmount(),
            value.getPaymentTerms(),
            value.getDeliveryTerms(),
            value.getValidUntil(),
            value.getTaxIncluded(),
            value.isCurrentRevision(),
            value.getSupersedesQuotationId(),
            value.getSupersessionReason(),
            value.getOriginalAttachmentId(),
            lines.stream().map(line -> proposalLine(line, rfqItems, value.getTaxIncluded())).toList()
        );
    }

    private ProposalLineSummary proposalLine(SupplierQuotationItem value, Map<UUID, RFQItem> rfqItems, Boolean taxIncluded) {
        RFQItem rfqItem = rfqItems.get(value.getRfqItemId());
        return new ProposalLineSummary(
            value.getId(),
            first(rfqItem == null ? null : rfqItem.getName(), value.getName()),
            value.getRfqItemId(),
            rfqItem == null ? value.getMaterialId() : rfqItem.getMaterialId(),
            rfqItem == null || rfqItem.getMaterial() == null ? null : rfqItem.getMaterial().getCode(),
            rfqItem == null || rfqItem.getMaterial() == null ? null : rfqItem.getMaterial().getName(),
            value.getQuantity(),
            value.getUnitPrice(),
            value.isNoBid() ? null : lineGrandTotal(value, taxIncluded),
            value.getDeliveryDays(),
            value.getTaxRate(),
            value.isNoBid(),
            value.getNoBidReason(),
            value.getSupplierNote()
        );
    }

    private List<DocumentSummary> documents(
        List<RfqPublishedVersion> versions,
        List<SupplierQuotation> revisions
    ) {
        List<DocumentRef> refs = new ArrayList<>();
        for (RfqPublishedVersion version : versions) {
            if (version.getPdfAttachmentId() != null) {
                refs.add(new DocumentRef(
                    version.getPdfAttachmentId(), version.getId(), version.getVersionNumber(), "OFFICIAL_PDF"
                ));
            }
            if (version.getExcelAttachmentId() != null) {
                refs.add(new DocumentRef(
                    version.getExcelAttachmentId(), version.getId(), version.getVersionNumber(), "QUOTATION_TEMPLATE"
                ));
            }
            snapshot(version).documents().forEach(document -> refs.add(new DocumentRef(
                document.attachmentId(), version.getId(), version.getVersionNumber(), "SUPPORTING_DOCUMENT"
            )));
        }
        List<UUID> quotationIds = revisions.stream().map(SupplierQuotation::getId).toList();
        List<Attachment> proposalAttachments = quotationIds.isEmpty() ? List.of() : attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdInAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                "SUPPLIER_QUOTATION_EVIDENCE",
                quotationIds
            );
        proposalAttachments.forEach(attachment -> refs.add(new DocumentRef(
            attachment.getId(), null, null,
            "ORIGINAL_SUPPLIER_PROPOSAL".equals(attachment.getAttachmentType())
                ? "ORIGINAL_SUPPLIER_PROPOSAL"
                : "PROPOSAL_SUPPORTING_DOCUMENT"
        )));
        Map<UUID, Attachment> attachments = attachmentRepository.findAllById(refs.stream()
            .map(DocumentRef::attachmentId).collect(Collectors.toSet())).stream()
            .filter(this::inCompany)
            .filter(value -> !value.isDeleted())
            .collect(Collectors.toMap(Attachment::getId, Function.identity()));
        return refs.stream().distinct().map(ref -> {
            Attachment attachment = attachments.get(ref.attachmentId());
            return attachment == null ? null : new DocumentSummary(
                attachment.getId(),
                ref.publishedVersionId(),
                ref.versionNumber(),
                ref.documentType(),
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getCreatedAt()
            );
        }).filter(Objects::nonNull).toList();
    }

    private List<AuditSummary> audit(
        UUID rfqId,
        List<RfqSupplierInvitation> invitations,
        List<RfqInvitationDelivery> deliveries,
        List<SupplierQuotation> revisions,
        List<RfqPublishedVersion> versions
    ) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        ids.add(rfqId);
        invitations.forEach(value -> ids.add(value.getId()));
        deliveries.forEach(value -> ids.add(value.getId()));
        revisions.forEach(value -> ids.add(value.getId()));
        versions.forEach(value -> ids.add(value.getId()));
        return auditRepository
            .findByTenantIdAndCompanyIdAndEntityIdInAndIsDeletedFalseOrderByOccurredAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), List.copyOf(ids)
            ).stream().map(value -> new AuditSummary(
                value.getId(),
                value.getName(),
                value.getEntityType(),
                value.getEntityId(),
                value.getAction(),
                value.getFullName(),
                value.getDetails(),
                value.getOccurredAt()
            )).toList();
    }

    private RfqPackageSnapshot snapshot(RfqPublishedVersion version) {
        try {
            var tree = objectMapper.readTree(version.getSnapshotJson());
            return tree.isTextual()
                ? objectMapper.readValue(tree.asText(), RfqPackageSnapshot.class)
                : objectMapper.treeToValue(tree, RfqPackageSnapshot.class);
        } catch (Exception exception) {
            return new RfqPackageSnapshot(
                version.getRfqId(), null, null, null, version.getPackageLanguage(), null,
                null, null, List.of(), List.of(), List.of(), List.of()
            );
        }
    }

    private String responseProgress(int invitations, int proposals, int declined) {
        if (declined > 0 && proposals == 0) {
            return "DECLINED";
        }
        if (proposals == 0 && declined == 0) {
            return "NONE";
        }
        return proposals + declined >= invitations ? "COMPLETE" : "PARTIAL";
    }

    private boolean currentSubmitted(SupplierQuotation value) {
        return CURRENT_PROPOSAL_STATUSES.contains(value.getStatus())
            && (value.getThreadId() == null || value.isCurrentRevision());
    }

    private boolean commercialContentsVisible(RFQ rfq) {
        return COMMERCIAL_OPEN_STATUSES.contains(rfq.getStatus())
            || ("OPEN".equals(rfq.getStatus())
                && "OPEN_TENDER".equals(code(rfq.getTenderProcedureType()))
                && "PROCUREMENT".equals(code(rfq.getPurpose())));
    }

    private String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private int revision(SupplierQuotation value) {
        return value.getRevisionNumber() == null ? 1 : value.getRevisionNumber();
    }

    private boolean inCompany(com.enterprise.mm.common.BusinessEntity value) {
        return TenantContext.currentTenant().equals(value.getTenantId())
            && CompanyContext.currentCompany().equals(value.getCompanyId())
            && !value.isDeleted();
    }

    private String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private BigDecimal lineGrandTotal(SupplierQuotationItem line, Boolean taxIncluded) {
        BigDecimal subtotal = amount(line.getQuantity()).multiply(amount(line.getUnitPrice()));
        if (Boolean.TRUE.equals(taxIncluded)) {
            return subtotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal taxAmount = subtotal.multiply(amount(line.getTaxRate()))
            .divide(ONE_HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        return subtotal.add(taxAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record DocumentRef(
        UUID attachmentId,
        UUID publishedVersionId,
        Integer versionNumber,
        String documentType
    ) {
    }
}
