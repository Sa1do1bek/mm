package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.entity.SupplierQuotationThread;
import com.enterprise.mm.enums.QuotationSource;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqProposalDtos.ManualProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalDeclineRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalDeclineResponse;
import com.enterprise.mm.payload.RfqProposalDtos.PortalProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalReplacementRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalLineRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalLineView;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalView;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierQuotationThreadRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import com.enterprise.mm.service.rfq.RfqMetrics;

@Service
public class SupplierQuotationRevisionService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 4;
    private static final Set<String> CLOSED_RFQ_STATUSES = Set.of(
        "DRAFT", "CANCELLED", "CLOSED", "ARCHIVED", "BIDS_OPENED",
        "CONFLICT_CHECKED", "EVALUATED", "AWARDED"
    );

    private final SupplierQuotationRepository quotationRepository;
    private final SupplierQuotationItemRepository itemRepository;
    private final SupplierQuotationThreadRepository threadRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final RfqInvitationDeliveryRepository deliveryRepository;
    private final SupplierRepository supplierRepository;
    private final AttachmentService attachmentService;
    private final AuditService auditService;
    private final TenderSupplierAccessService tenderSupplierAccessService;
    private final Clock clock;
    private final ZoneId applicationZone;
    private RfqMetrics metrics;

    public SupplierQuotationRevisionService(
        SupplierQuotationRepository quotationRepository,
        SupplierQuotationItemRepository itemRepository,
        SupplierQuotationThreadRepository threadRepository,
        RFQRepository rfqRepository,
        RFQItemRepository rfqItemRepository,
        RfqSupplierInvitationRepository invitationRepository,
        RfqPublishedVersionRepository versionRepository,
        RfqInvitationDeliveryRepository deliveryRepository,
        SupplierRepository supplierRepository,
        AttachmentService attachmentService,
        AuditService auditService,
        TenderSupplierAccessService tenderSupplierAccessService,
        Clock clock,
        @Value("${app.time-zone:Asia/Tashkent}") String timeZone
    ) {
        this.quotationRepository = quotationRepository;
        this.itemRepository = itemRepository;
        this.threadRepository = threadRepository;
        this.rfqRepository = rfqRepository;
        this.rfqItemRepository = rfqItemRepository;
        this.invitationRepository = invitationRepository;
        this.versionRepository = versionRepository;
        this.deliveryRepository = deliveryRepository;
        this.supplierRepository = supplierRepository;
        this.attachmentService = attachmentService;
        this.auditService = auditService;
        this.tenderSupplierAccessService = tenderSupplierAccessService;
        this.clock = clock;
        try {
            this.applicationZone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "Asia/Tashkent" : timeZone);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid app.time-zone", exception);
        }
    }

    @Autowired
    void setMetrics(RfqMetrics metrics) {
        this.metrics = metrics;
    }

    @Transactional
    public ProposalView recordManualProposal(
        UUID rfqId,
        ManualProposalRequest request,
        MultipartFile originalFile,
        List<MultipartFile> supportingFiles
    ) {
        requireOriginalEvidence(originalFile);
        requireManualRequest(request);
        RFQ rfq = requireLockedOpenRfq(rfqId);
        requireManualReceiptOnTime(rfq, request.receivedAt());
        Supplier supplier = requireSupplier(request.supplierId());
        RfqSupplierInvitation invitation = requireInvitation(rfq.getId(), supplier.getId());
        RfqPublishedVersion version = requireActiveVersion(rfq);
        List<RFQItem> rfqItems = rfqItems(rfq);
        validateExactLines(rfqItems, request.items());

        SupplierQuotationThread thread = lockOrCreateThread(rfq, supplier);
        SupplierQuotation previous = currentSubmitted(thread);
        requireSupersessionReason(previous, request.supersessionReason());
        int revisionNumber = nextRevisionNumber(thread, previous);
        LocalDateTime recordedAt = now();
        SupplierQuotation revision = newRevision(
            rfq,
            supplier,
            thread,
            version,
            revisionNumber,
            request.source(),
            "SUBMITTED",
            request.receivedAt(),
            recordedAt,
            request.currencyCode(),
            request.paymentTerms(),
            request.deliveryTerms(),
            request.validUntil(),
            null,
            previous,
            request.supersessionReason(),
            request.sourceExplanation()
        );
        List<SupplierQuotationItem> lines = saveLines(revision, rfqItems, request.items());
        Attachment original = attachmentService.uploadForCommand(
            "SUPPLIER_QUOTATION_EVIDENCE",
            revision.getId(),
            "ORIGINAL_SUPPLIER_PROPOSAL",
            originalFile
        );
        revision.setOriginalAttachmentId(original.getId());
        storeSupportingFiles(revision, supportingFiles);
        switchCurrentRevision(thread, previous, revision);
        quotationRepository.save(revision);
        markInvitationResponded(invitation, request.receivedAt());
        auditService.log(
            "SupplierQuotation",
            revision.getId(),
            "MANUAL_PROPOSAL_RECORDED",
            request.source() + " receivedAt=" + request.receivedAt()
                + " recordedAt=" + recordedAt + " evidence=" + original.getId()
        , null, null);
        recordProposalMetric(request.source(), version, request.receivedAt());
        return view(revision, lines);
    }

    @Transactional
    public ProposalView createPortalDraft(UUID rfqId, UUID supplierId, PortalProposalRequest request) {
        requirePortalRequest(request);
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        RfqSupplierInvitation invitation = participationInvitation(rfq, supplier);
        requireNotDeclined(invitation);
        RfqPublishedVersion version = requireActiveVersion(rfq);
        List<RFQItem> rfqItems = rfqItems(rfq);
        validateExactLines(rfqItems, request.items());
        SupplierQuotationThread thread = lockOrCreateThread(rfq, supplier);
        if (currentSubmitted(thread) != null) {
            throw new BusinessException("A submitted proposal already exists; use the replacement command");
        }
        if (findDraft(thread) != null) {
            throw new BusinessException("A supplier proposal draft already exists");
        }
        SupplierQuotation draft = newRevision(
            rfq, supplier, thread, version, 1, QuotationSource.SUPPLIER_PORTAL,
            "DRAFT", null, null, request.currencyCode(), request.paymentTerms(),
            request.deliveryTerms(), request.validUntil(), request.taxIncluded(),
            null, null, null
        );
        List<SupplierQuotationItem> lines = saveLines(draft, rfqItems, request.items());
        auditService.log("SupplierQuotation", draft.getId(), "PORTAL_DRAFT_CREATED", rfq.getCode(), null, null);
        return view(draft, lines);
    }

    @Transactional
    public ProposalView updatePortalDraft(
        UUID rfqId,
        UUID supplierId,
        UUID revisionId,
        PortalProposalRequest request
    ) {
        requirePortalRequest(request);
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        RfqSupplierInvitation invitation = participationInvitation(rfq, supplier);
        requireNotDeclined(invitation);
        SupplierQuotation draft = requireOwnedDraft(rfq, supplierId, revisionId);
        List<RFQItem> rfqItems = rfqItems(rfq);
        validateExactLines(rfqItems, request.items());
        draft.setCurrencyCode(currency(request.currencyCode()));
        draft.setPaymentTerms(trim(request.paymentTerms()));
        draft.setDeliveryTerms(trim(request.deliveryTerms()));
        draft.setValidUntil(request.validUntil());
        draft.setTaxIncluded(request.taxIncluded());
        replaceDraftLines(draft, rfqItems, request.items());
        quotationRepository.save(draft);
        auditService.log("SupplierQuotation", draft.getId(), "PORTAL_DRAFT_UPDATED", rfq.getCode(), null, null);
        return view(draft, loadLines(draft.getId()));
    }

    @Transactional
    public ProposalView submitPortalRevision(UUID rfqId, UUID supplierId, UUID revisionId) {
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        RfqSupplierInvitation invitation = participationInvitation(rfq, supplier);
        requireNotDeclined(invitation);
        SupplierQuotation draft = requireOwnedDraft(rfq, supplierId, revisionId);
        List<SupplierQuotationItem> lines = loadLines(draft.getId());
        validateStoredExactLines(rfqItems(rfq), lines);
        SupplierQuotationThread thread = lockThread(rfqId, supplierId);
        SupplierQuotation previous = currentSubmitted(thread);
        if (previous != null && !Objects.equals(draft.getSupersedesQuotationId(), previous.getId())) {
            throw new BusinessException(
                "Supplier proposal changed; refresh and retry", "rfq.proposal.revision_conflict"
            );
        }
        LocalDateTime submittedAt = now();
        draft.setStatus("SUBMITTED");
        draft.setReceivedAt(submittedAt);
        draft.setSubmittedAt(submittedAt);
        draft.setRecordedBy(currentActor());
        draft.setCurrentRevision(true);
        switchCurrentRevision(thread, previous, draft);
        quotationRepository.save(draft);
        markInvitationResponded(invitation, submittedAt);
        auditService.log("SupplierQuotation", draft.getId(), "PORTAL_PROPOSAL_SUBMITTED", rfq.getCode(), null, null);
        recordProposalMetric(QuotationSource.SUPPLIER_PORTAL, requireActiveVersion(rfq), submittedAt);
        return view(draft, lines);
    }

    @Transactional
    public ProposalView replacePortalRevision(
        UUID rfqId,
        UUID supplierId,
        PortalReplacementRequest request
    ) {
        return replacePortalRevision(rfqId, supplierId, null, request);
    }

    @Transactional
    public ProposalView replacePortalRevision(
        UUID rfqId,
        UUID supplierId,
        UUID revisionId,
        PortalReplacementRequest request
    ) {
        if (request == null || request.proposal() == null
            || request.supersessionReason() == null || request.supersessionReason().trim().length() < 3) {
            throw new BusinessException("Supersession reason is required");
        }
        requirePortalRequest(request.proposal());
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        RfqSupplierInvitation invitation = participationInvitation(rfq, supplier);
        RfqPublishedVersion version = requireActiveVersion(rfq);
        List<RFQItem> rfqItems = rfqItems(rfq);
        validateExactLines(rfqItems, request.proposal().items());
        SupplierQuotationThread thread = lockOrCreateThread(rfq, supplier);
        SupplierQuotation previous = currentSubmitted(thread);
        SupplierQuotation superseded = previous;
        if (previous != null && revisionId != null && !revisionId.equals(previous.getId())) {
            throw new BusinessException(
                "Supplier proposal changed; refresh and retry", "rfq.proposal.revision_conflict"
            );
        }
        if (previous == null) {
            if (revisionId == null || !"DECLINED".equals(thread.getStatus())) {
                throw new BusinessException("No submitted or declined response exists to replace");
            }
            superseded = quotationRepository.lockScopedById(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), revisionId
            ).filter(value -> Objects.equals(value.getThreadId(), thread.getId()))
                .filter(value -> Objects.equals(value.getSupplierId(), supplierId))
                .filter(value -> Objects.equals(value.getRfqId(), rfqId))
                .filter(value -> "DECLINED".equals(value.getStatus()))
                .orElseThrow(() -> new BusinessException("Declined supplier response not found"));
        }
        LocalDateTime submittedAt = now();
        SupplierQuotation revision = newRevision(
            rfq, supplier, thread, version, nextRevisionNumber(thread, previous),
            QuotationSource.SUPPLIER_PORTAL, "SUBMITTED", submittedAt, submittedAt,
            request.proposal().currencyCode(), request.proposal().paymentTerms(),
            request.proposal().deliveryTerms(), request.proposal().validUntil(),
            request.proposal().taxIncluded(), superseded, request.supersessionReason(), null
        );
        List<SupplierQuotationItem> lines = saveLines(revision, rfqItems, request.proposal().items());
        switchCurrentRevision(thread, previous, revision);
        thread.setStatus("OPEN");
        threadRepository.save(thread);
        quotationRepository.save(revision);
        markInvitationResponded(invitation, submittedAt);
        auditService.log("SupplierQuotation", revision.getId(), "PORTAL_PROPOSAL_REPLACED", request.supersessionReason().trim(), null, null);
        recordProposalMetric(QuotationSource.SUPPLIER_PORTAL, version, submittedAt);
        return view(revision, lines);
    }

    @Transactional
    public PortalDeclineResponse declinePortalInvitation(
        UUID rfqId,
        UUID supplierId,
        PortalDeclineRequest request
    ) {
        if (request == null || request.reason() == null || request.reason().trim().length() < 3) {
            throw new BusinessException("Decline reason is required");
        }
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        RfqSupplierInvitation invitation = participationInvitation(rfq, supplier);
        RfqInvitationDelivery delivery = invitation == null ? null : latestDelivery(invitation);
        if (delivery != null && delivery.getDeclinedAt() != null) {
            SupplierQuotation declined = declinedRevision(rfqId, supplierId);
            return new PortalDeclineResponse(
                rfqId,
                supplierId,
                declined == null ? null : declined.getId(),
                delivery.getDeclinedAt(),
                delivery.getDescription()
            );
        }
        SupplierQuotationThread thread = lockOrCreateThread(rfq, supplier);
        if (currentSubmitted(thread) != null) {
            throw new BusinessException("A submitted proposal cannot be declined");
        }
        SupplierQuotation declined = findDraft(thread);
        LocalDateTime declinedAt = now();
        String reason = request.reason().trim();
        if (declined == null) {
            declined = newRevision(
                rfq,
                supplier,
                thread,
                requireActiveVersion(rfq),
                nextRevisionNumber(thread, null),
                QuotationSource.SUPPLIER_PORTAL,
                "DECLINED",
                declinedAt,
                declinedAt,
                "UZS",
                null,
                null,
                null,
                null,
                null,
                null,
                reason
            );
        } else {
            declined.setStatus("DECLINED");
            declined.setDescription(reason);
            declined.setReceivedAt(declinedAt);
            declined.setSubmittedAt(declinedAt);
            quotationRepository.save(declined);
        }
        thread.setStatus("DECLINED");
        threadRepository.save(thread);
        if (delivery != null) {
            delivery.setDeclinedAt(declinedAt);
            delivery.setDescription(reason);
            deliveryRepository.save(delivery);
        }
        auditService.log("SupplierQuotation", declined.getId(), "PORTAL_RFQ_DECLINED", reason, null, null);
        return new PortalDeclineResponse(rfqId, supplierId, declined.getId(), declinedAt, reason);
    }

    @Transactional
    public Attachment addPortalDraftAttachment(
        UUID rfqId,
        UUID supplierId,
        UUID revisionId,
        MultipartFile file
    ) {
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        participationInvitation(rfq, supplier);
        SupplierQuotation draft = requireOwnedDraft(rfq, supplierId, revisionId);
        return attachmentService.uploadForCommand(
            "SUPPLIER_QUOTATION_EVIDENCE", draft.getId(), "SUPPORTING_DOCUMENT", file
        );
    }

    @Transactional
    public void deletePortalDraftAttachment(
        UUID rfqId,
        UUID supplierId,
        UUID revisionId,
        UUID attachmentId
    ) {
        RFQ rfq = requireLockedOpenPortalRfq(rfqId);
        Supplier supplier = requireSupplier(supplierId);
        participationInvitation(rfq, supplier);
        requireOwnedDraft(rfq, supplierId, revisionId);
        attachmentService.softDeleteForCommand(
            "SUPPLIER_QUOTATION_EVIDENCE", revisionId, attachmentId
        );
    }

    private SupplierQuotation newRevision(
        RFQ rfq,
        Supplier supplier,
        SupplierQuotationThread thread,
        RfqPublishedVersion version,
        int revisionNumber,
        QuotationSource source,
        String status,
        LocalDateTime receivedAt,
        LocalDateTime submittedAt,
        String currencyCode,
        String paymentTerms,
        String deliveryTerms,
        java.time.LocalDate validUntil,
        Boolean taxIncluded,
        SupplierQuotation previous,
        String supersessionReason,
        String description
    ) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setTenantId(TenantContext.currentTenant());
        quotation.setCompanyId(CompanyContext.currentCompany());
        quotation.setCode("SQ-" + rfq.getCode() + "-R" + revisionNumber + "-" + shortId());
        quotation.setName(BusinessNameGenerator.workflowDocumentName("SupplierQuotation", rfq.getName()));
        quotation.setStatus(status);
        quotation.setDescription(trim(description));
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setThreadId(thread.getId());
        quotation.setPublishedVersionId(version.getId());
        quotation.setRevisionNumber(revisionNumber);
        quotation.setSource(source);
        quotation.setReceivedAt(receivedAt);
        quotation.setSubmittedAt(submittedAt);
        quotation.setRecordedBy(currentActor());
        quotation.setCurrencyCode(currency(currencyCode));
        quotation.setPaymentTerms(trim(paymentTerms));
        quotation.setDeliveryTerms(trim(deliveryTerms));
        quotation.setValidUntil(validUntil);
        quotation.setTaxIncluded(taxIncluded);
        quotation.setCurrentRevision(false);
        quotation.setSupersedesQuotationId(previous == null ? null : previous.getId());
        quotation.setSupersessionReason(trim(supersessionReason));
        quotation.setTotalAmount(BigDecimal.ZERO);
        return quotationRepository.save(quotation);
    }

    private List<SupplierQuotationItem> saveLines(
        SupplierQuotation quotation,
        List<RFQItem> rfqItems,
        List<ProposalLineRequest> requests
    ) {
        Map<UUID, RFQItem> itemsById = new HashMap<>();
        rfqItems.forEach(item -> itemsById.put(item.getId(), item));
        List<SupplierQuotationItem> saved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ProposalLineRequest request : requests) {
            RFQItem rfqItem = itemsById.get(request.rfqItemId());
            SupplierQuotationItem line = new SupplierQuotationItem();
            line.setTenantId(TenantContext.currentTenant());
            line.setCompanyId(CompanyContext.currentCompany());
            line.setCode("SQI-" + quotation.getRevisionNumber() + "-" + shortId());
            line.setName(rfqItem.getName() == null ? "Quotation line" : rfqItem.getName());
            line.setStatus("ACTIVE");
            line.setQuotationId(quotation.getId());
            line.setRfqItemId(rfqItem.getId());
            line.setLineType(rfqItem.getLineType());
            line.setMaterialId(rfqItem.getMaterialId());
            line.setMaterialTypeSnapshot(rfqItem.getMaterialTypeSnapshot());
            line.setServiceRequestId(rfqItem.getServiceRequestId());
            line.setServiceDescription(rfqItem.getServiceDescription());
            line.setServiceCategory(rfqItem.getServiceCategory());
            line.setUnit(rfqItem.getUnit());
            line.setPeriod(rfqItem.getPeriod());
            line.setStage(rfqItem.getStage());
            line.setBudgetItemId(rfqItem.getBudgetItemId());
            line.setNoBid(request.noBid());
            line.setNoBidReason(trim(request.noBidReason()));
            line.setQuantity(amount(request.offeredQuantity()));
            line.setUnitPrice(request.noBid() ? BigDecimal.ZERO : amount(request.unitPrice()));
            line.setDeliveryDays(request.deliveryDays() == null ? 0 : request.deliveryDays());
            line.setTaxRate(request.taxRate());
            line.setSupplierNote(trim(request.note()));
            itemRepository.save(line);
            saved.add(line);
            if (!request.noBid()) {
                total = total.add(lineGrandTotal(line, quotation.getTaxIncluded()));
            }
        }
        quotation.setTotalAmount(total.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        quotationRepository.save(quotation);
        return saved;
    }

    private BigDecimal lineGrandTotal(SupplierQuotationItem line, Boolean taxIncluded) {
        BigDecimal subtotal = amount(line.getQuantity()).multiply(amount(line.getUnitPrice()));
        if (Boolean.TRUE.equals(taxIncluded)) {
            return subtotal;
        }
        BigDecimal taxAmount = subtotal.multiply(amount(line.getTaxRate()))
            .divide(ONE_HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        return subtotal.add(taxAmount);
    }

    private void replaceDraftLines(
        SupplierQuotation draft,
        List<RFQItem> rfqItems,
        List<ProposalLineRequest> requests
    ) {
        for (SupplierQuotationItem existing : loadLines(draft.getId())) {
            existing.softDelete();
            itemRepository.save(existing);
        }
        saveLines(draft, rfqItems, requests);
    }

    private void validateExactLines(List<RFQItem> rfqItems, List<ProposalLineRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("Proposal lines are required");
        }
        Set<UUID> expected = rfqItems.stream().map(RFQItem::getId)
            .collect(java.util.stream.Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        for (ProposalLineRequest request : requests) {
            if (request == null || request.rfqItemId() == null || !expected.contains(request.rfqItemId())) {
                throw new BusinessException("Proposal contains an unknown RFQ item");
            }
            if (!seen.add(request.rfqItemId())) {
                throw new BusinessException("Proposal contains a duplicate RFQ item");
            }
            validateLine(request);
        }
        if (!seen.equals(expected)) {
            throw new BusinessException("Proposal must contain every RFQ item exactly once");
        }
    }

    private void validateLine(ProposalLineRequest request) {
        if (request.noBid()) {
            if (request.noBidReason() == null || request.noBidReason().trim().length() < 3) {
                throw new BusinessException("No-bid reason is required");
            }
            return;
        }
        if (request.offeredQuantity() == null || request.offeredQuantity().signum() <= 0) {
            throw new BusinessException("Offered quantity must be positive");
        }
        if (request.unitPrice() == null || request.unitPrice().signum() < 0) {
            throw new BusinessException("Unit price must be zero or positive");
        }
        if (request.deliveryDays() != null && request.deliveryDays() < 0) {
            throw new BusinessException("Delivery days must be zero or positive");
        }
        if (request.taxRate() != null
            && (request.taxRate().signum() < 0 || request.taxRate().compareTo(new BigDecimal("100")) > 0)) {
            throw new BusinessException("Tax rate must be between 0 and 100");
        }
    }

    private void validateStoredExactLines(List<RFQItem> rfqItems, List<SupplierQuotationItem> lines) {
        Set<UUID> expected = rfqItems.stream().map(RFQItem::getId)
            .collect(java.util.stream.Collectors.toSet());
        Set<UUID> actual = lines.stream().map(SupplierQuotationItem::getRfqItemId)
            .collect(java.util.stream.Collectors.toSet());
        if (lines.size() != expected.size() || !actual.equals(expected)) {
            throw new BusinessException("Proposal must contain every RFQ item exactly once");
        }
    }

    private RFQ requireLockedOpenRfq(UUID rfqId) {
        RFQ rfq = rfqRepository.lockScopedById(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
        ).orElseThrow(() -> new BusinessException("RFQ not found"));
        if (rfq.getActivePublishedVersionId() == null
            || rfq.getClosedAt() != null
            || CLOSED_RFQ_STATUSES.contains(normalizeStatus(rfq.getStatus()))) {
            throw new BusinessException("RFQ is not open for supplier proposals");
        }
        return rfq;
    }

    private RFQ requireLockedOpenPortalRfq(UUID rfqId) {
        RFQ rfq = requireLockedOpenRfq(rfqId);
        requirePortalDeadlineOpen(rfq);
        return rfq;
    }

    private void requirePortalDeadlineOpen(RFQ rfq) {
        if (rfq.getDueDate() == null || now().isAfter(rfq.getDueDate().atTime(LocalTime.MAX))) {
            throw new BusinessException("RFQ response deadline has passed");
        }
    }

    private void requireManualReceiptOnTime(RFQ rfq, LocalDateTime receivedAt) {
        if (receivedAt == null) {
            throw new BusinessException("Supplier proposal receivedAt is required");
        }
        if (rfq.getDueDate() == null || receivedAt.isAfter(rfq.getDueDate().atTime(LocalTime.MAX))) {
            throw new BusinessException("Supplier proposal was received after the RFQ deadline");
        }
    }

    private RfqPublishedVersion requireActiveVersion(RFQ rfq) {
        return versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getActivePublishedVersionId()
        ).orElseThrow(() -> new BusinessException("Active RFQ published version not found"));
    }

    private Supplier requireSupplier(UUID supplierId) {
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), supplierId
        ).filter(value -> CompanyContext.currentCompany().equals(value.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
        if (!List.of("ACTIVE", "LIMITED").contains(supplier.getStatus())) {
            throw new BusinessException("Supplier is not active");
        }
        return supplier;
    }

    private RfqSupplierInvitation requireInvitation(UUID rfqId, UUID supplierId) {
        return invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
            ).stream()
            .filter(value -> Objects.equals(value.getSupplierId(), supplierId))
            .filter(value -> "INVITED".equals(value.getStatus()))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Active RFQ supplier invitation is required"));
    }

    private RfqSupplierInvitation participationInvitation(RFQ rfq, Supplier supplier) {
        tenderSupplierAccessService.validateBidSubmission(rfq, supplier);
        return tenderSupplierAccessService.activeInvitation(rfq, supplier).orElse(null);
    }

    private void requireNotDeclined(RfqSupplierInvitation invitation) {
        if (invitation == null) {
            return;
        }
        List<RfqInvitationDelivery> deliveries = deliveries(invitation);
        if (!deliveries.isEmpty() && deliveries.get(0).getDeclinedAt() != null) {
            throw new BusinessException("RFQ was declined; use the replacement response command");
        }
    }

    private RfqInvitationDelivery latestDelivery(RfqSupplierInvitation invitation) {
        List<RfqInvitationDelivery> deliveries = deliveries(invitation);
        if (deliveries.isEmpty()) {
            throw new BusinessException("RFQ invitation delivery not found");
        }
        return deliveries.get(0);
    }

    private List<RfqInvitationDelivery> deliveries(RfqSupplierInvitation invitation) {
        if (invitation == null) {
            return List.of();
        }
        return deliveryRepository
            .findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), invitation.getId()
            );
    }

    private SupplierQuotation declinedRevision(UUID rfqId, UUID supplierId) {
        SupplierQuotationThread thread = threadRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndSupplierIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId, supplierId
            ).orElse(null);
        if (thread == null) {
            return null;
        }
        return quotationRepository
            .findByTenantIdAndCompanyIdAndThreadIdAndIsDeletedFalseOrderByRevisionNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), thread.getId()
            ).stream()
            .filter(value -> "DECLINED".equals(value.getStatus()))
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private List<RFQItem> rfqItems(RFQ rfq) {
        List<RFQItem> items = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), rfq.getId()
        ).stream().filter(value -> CompanyContext.currentCompany().equals(value.getCompanyId())).toList();
        if (items.isEmpty()) {
            throw new BusinessException("RFQ has no positions");
        }
        return items;
    }

    private SupplierQuotationThread lockOrCreateThread(RFQ rfq, Supplier supplier) {
        SupplierQuotationThread existing = threadRepository.lockByRfqIdAndSupplierId(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId(), supplier.getId()
        ).orElse(null);
        if (existing != null) {
            return existing;
        }
        SupplierQuotationThread thread = new SupplierQuotationThread();
        thread.setTenantId(TenantContext.currentTenant());
        thread.setCompanyId(CompanyContext.currentCompany());
        thread.setCode("SQT-" + shortId());
        thread.setName("Proposal thread for " + (supplier.getName() == null || supplier.getName().isBlank()
            ? "supplier"
            : supplier.getName().trim()));
        thread.setStatus("OPEN");
        thread.setRfqId(rfq.getId());
        thread.setSupplierId(supplier.getId());
        return threadRepository.saveAndFlush(thread);
    }

    private SupplierQuotationThread lockThread(UUID rfqId, UUID supplierId) {
        return threadRepository.lockByRfqIdAndSupplierId(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId, supplierId
        ).orElseThrow(() -> new BusinessException("Supplier proposal thread not found"));
    }

    private SupplierQuotation currentSubmitted(SupplierQuotationThread thread) {
        if (thread.getCurrentQuotationId() != null) {
            SupplierQuotation current = quotationRepository.findByIdAndIsDeletedFalse(thread.getCurrentQuotationId())
                .filter(value -> Objects.equals(value.getThreadId(), thread.getId()))
                .orElse(null);
            if (current != null && current.isCurrentRevision()) {
                return current;
            }
        }
        return quotationRepository
            .findByTenantIdAndCompanyIdAndThreadIdAndCurrentRevisionTrueAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), thread.getId()
            ).orElse(null);
    }

    private SupplierQuotation findDraft(SupplierQuotationThread thread) {
        return quotationRepository
            .findByTenantIdAndCompanyIdAndThreadIdAndIsDeletedFalseOrderByRevisionNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), thread.getId()
            ).stream()
            .filter(value -> "DRAFT".equals(value.getStatus()))
            .findFirst()
            .orElse(null);
    }

    private SupplierQuotation requireOwnedDraft(RFQ rfq, UUID supplierId, UUID revisionId) {
        SupplierQuotation quotation = quotationRepository.lockScopedById(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), revisionId
        ).filter(value -> Objects.equals(value.getRfqId(), rfq.getId()))
            .filter(value -> Objects.equals(value.getSupplierId(), supplierId))
            .orElseThrow(() -> new BusinessException("Supplier proposal draft not found"));
        if (!"DRAFT".equals(quotation.getStatus())) {
            throw new BusinessException("Submitted supplier proposal revisions are immutable");
        }
        return quotation;
    }

    private int nextRevisionNumber(SupplierQuotationThread thread, SupplierQuotation previous) {
        if (previous != null && previous.getRevisionNumber() != null) {
            return previous.getRevisionNumber() + 1;
        }
        return quotationRepository
            .findByTenantIdAndCompanyIdAndThreadIdAndIsDeletedFalseOrderByRevisionNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), thread.getId()
            ).stream()
            .map(SupplierQuotation::getRevisionNumber)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    private void switchCurrentRevision(
        SupplierQuotationThread thread,
        SupplierQuotation previous,
        SupplierQuotation revision
    ) {
        if (previous != null) {
            previous.setCurrentRevision(false);
            quotationRepository.save(previous);
        }
        revision.setCurrentRevision(true);
        thread.setCurrentQuotationId(revision.getId());
        threadRepository.save(thread);
    }

    private void markInvitationResponded(RfqSupplierInvitation invitation, LocalDateTime receivedAt) {
        if (invitation == null) {
            return;
        }
        List<RfqInvitationDelivery> deliveries = deliveries(invitation);
        if (!deliveries.isEmpty()) {
            RfqInvitationDelivery delivery = deliveries.get(0);
            delivery.setQuotationReceivedAt(receivedAt);
            deliveryRepository.save(delivery);
        }
    }

    private void recordProposalMetric(
        QuotationSource source,
        RfqPublishedVersion version,
        LocalDateTime receivedAt
    ) {
        if (metrics != null) {
            metrics.proposal(source == null ? null : source.name(), version.getPublishedAt(), receivedAt);
        }
    }

    private void storeSupportingFiles(SupplierQuotation revision, List<MultipartFile> files) {
        if (files == null) {
            return;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                attachmentService.uploadForCommand(
                    "SUPPLIER_QUOTATION_EVIDENCE",
                    revision.getId(),
                    "SUPPORTING_DOCUMENT",
                    file
                );
            }
        }
    }

    private ProposalView view(SupplierQuotation quotation, List<SupplierQuotationItem> lines) {
        return new ProposalView(
            quotation.getId(),
            quotation.getThreadId(),
            quotation.getRfqId(),
            quotation.getSupplierId(),
            quotation.getPublishedVersionId(),
            quotation.getRevisionNumber() == null ? 1 : quotation.getRevisionNumber(),
            quotation.getStatus(),
            quotation.getSource(),
            quotation.getReceivedAt(),
            quotation.getSubmittedAt(),
            quotation.getRecordedBy(),
            quotation.getCurrencyCode(),
            quotation.getTotalAmount(),
            quotation.getPaymentTerms(),
            quotation.getDeliveryTerms(),
            quotation.getValidUntil(),
            quotation.getTaxIncluded(),
            quotation.getDescription(),
            quotation.isCurrentRevision(),
            quotation.getSupersedesQuotationId(),
            quotation.getSupersessionReason(),
            quotation.getOriginalAttachmentId(),
            lines.stream().map(line -> lineView(line, quotation.getTaxIncluded())).toList()
        );
    }

    private ProposalLineView lineView(SupplierQuotationItem line, Boolean taxIncluded) {
        return new ProposalLineView(
            line.getId(),
            line.getRfqItemId(),
            line.getQuantity(),
            line.getUnitPrice(),
            line.isNoBid() ? null : lineGrandTotal(line, taxIncluded).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            line.getDeliveryDays(),
            line.getTaxRate(),
            line.isNoBid(),
            line.getNoBidReason(),
            line.getSupplierNote()
        );
    }

    private List<SupplierQuotationItem> loadLines(UUID quotationId) {
        return itemRepository.findByTenantIdAndCompanyIdAndQuotationIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), quotationId
        );
    }

    private void requireOriginalEvidence(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(
                "Original supplier proposal attachment is required", "rfq.proposal.original_required"
            );
        }
    }

    private void requireManualRequest(ManualProposalRequest request) {
        if (request == null) {
            throw new BusinessException("Manual supplier proposal request is required");
        }
        if (request.source() == null || request.source() == QuotationSource.SUPPLIER_PORTAL) {
            throw new BusinessException("Manual proposal source must be EMAIL, PAPER or OTHER");
        }
        if (request.source() == QuotationSource.OTHER
            && (request.sourceExplanation() == null || request.sourceExplanation().trim().length() < 3)) {
            throw new BusinessException("Source explanation is required for OTHER proposals");
        }
    }

    private void requirePortalRequest(PortalProposalRequest request) {
        if (request == null || request.currencyCode() == null || request.currencyCode().isBlank()) {
            throw new BusinessException("Portal proposal currency is required");
        }
    }

    private void requireSupersessionReason(SupplierQuotation previous, String reason) {
        if (previous != null && (reason == null || reason.trim().length() < 3)) {
            throw new BusinessException("Supersession reason is required");
        }
    }

    private String currency(String value) {
        String currency = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new BusinessException("Currency code must contain three letters");
        }
        return currency;
    }

    private String normalizeStatus(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), applicationZone);
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
            ? "system"
            : authentication.getName();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
