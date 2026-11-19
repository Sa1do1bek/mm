package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPoResponseLine;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.AsnSummary;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.CollaborationStatusResponse;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.CommitmentView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.DispatchView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.ReceiptSummary;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.ResponseLineView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.ResponseView;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side for the internal "Supplier collaboration" tab / registry annotation.
 * Owns the {@code collaborationStatus} vocabulary (NOT_SENT | DISPATCHED | RESPONDED |
 * PARTIALLY_ACCEPTED | ACCEPTED | REJECTED | REVISION_REQUESTED | FULFILLING |
 * FULFILLED) so both {@code PurchaseOrderController}'s new collaboration endpoint and
 * {@code PurchaseOrderService.toResponse()}'s registry annotation compute it the same
 * way, matching the frontend contract in docs/CURRENT_TASK.md exactly.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoCollaborationQueryService {
    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierPoResponseRepository responseRepository;
    private final SupplierPoResponseLineRepository responseLineRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Transactional(readOnly = true)
    public CollaborationStatusResponse statusView(UUID purchaseOrderId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoDispatch dispatch = dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, purchaseOrderId)
            .orElse(null);
        boolean legacy = dispatch == null;
        SupplierPoResponse currentResponse = dispatch == null
            ? null
            : responseRepository.findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
                tenantId, companyId, dispatch.getId()
            ).orElse(null);
        List<ResponseView> history = responseRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(tenantId, companyId, purchaseOrderId)
            .stream()
            .filter(r -> currentResponse == null || !r.getId().equals(currentResponse.getId()))
            .map(this::toResponseView)
            .toList();
        AsnSummary asnSummary = asnSummary(tenantId, companyId, purchaseOrderId);
        ReceiptSummary receiptSummary = receiptSummary(tenantId, purchaseOrderId);
        boolean fulfilled = isFulfilled(receiptSummary, asnSummary);
        boolean fulfilling = !fulfilled && (asnSummary.submittedCount() > 0 || receiptSummary.receivedQuantity().signum() > 0);
        String collaborationStatus = resolveStatus(dispatch, currentResponse, fulfilled, fulfilling);
        return new CollaborationStatusResponse(
            collaborationStatus,
            legacy,
            dispatch == null ? null : toDispatchView(dispatch),
            currentResponse == null ? null : toResponseView(currentResponse),
            history,
            asnSummary,
            receiptSummary
        );
    }

    /** Cross-checks that the sub-resource ({@code dispatchId}/{@code responseId}/
     * {@code commitmentId}) actually belongs to the path-scoped purchase order before
     * {@code PurchaseOrderCollaborationController} delegates to a command that only
     * checks tenant/company, not the specific PO the caller was granted visibility to
     * via {@code RoleDataScopeService} (see {@code PurchaseOrderService.get(id)}).
     * Without this, a caller who can see PO A could act on a dispatch/response/
     * commitment that actually belongs to PO B just by knowing its id. */
    @Transactional(readOnly = true)
    public void requireDispatchBelongsToPurchaseOrder(UUID purchaseOrderId, UUID dispatchId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoDispatch dispatch = dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)
            .filter(found -> tenantId.equals(found.getTenantId()) && java.util.Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO dispatch not found: " + dispatchId));
        if (!java.util.Objects.equals(dispatch.getPurchaseOrderId(), purchaseOrderId)) {
            throw new NotFoundException("Supplier PO dispatch not found: " + dispatchId);
        }
    }

    @Transactional(readOnly = true)
    public void requireResponseBelongsToPurchaseOrder(UUID purchaseOrderId, UUID responseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse response = responseRepository.findByIdAndIsDeletedFalse(responseId)
            .filter(found -> tenantId.equals(found.getTenantId()) && java.util.Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO response not found: " + responseId));
        if (!java.util.Objects.equals(response.getPurchaseOrderId(), purchaseOrderId)) {
            throw new NotFoundException("Supplier PO response not found: " + responseId);
        }
    }

    @Transactional(readOnly = true)
    public void requireCommitmentBelongsToPurchaseOrder(UUID purchaseOrderId, UUID commitmentId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoCommitment commitment = commitmentRepository.findByIdAndIsDeletedFalse(commitmentId)
            .filter(found -> tenantId.equals(found.getTenantId()) && java.util.Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO commitment not found: " + commitmentId));
        if (!java.util.Objects.equals(commitment.getPurchaseOrderId(), purchaseOrderId)) {
            throw new NotFoundException("Supplier PO commitment not found: " + commitmentId);
        }
    }

    @Transactional(readOnly = true)
    public List<DispatchView> dispatchHistory(UUID purchaseOrderId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, companyId, purchaseOrderId
            )
            .stream()
            .map(this::toDispatchView)
            .toList();
    }

    public String resolveStatus(SupplierPoDispatch dispatch, SupplierPoResponse response, boolean fulfilled, boolean fulfilling) {
        if (dispatch == null) {
            return "NOT_SENT";
        }
        if (response == null || "DRAFT".equals(response.getResponseStatus())) {
            return "DISPATCHED";
        }
        return switch (response.getResponseStatus()) {
            case "SUBMITTED" -> "RESPONDED";
            case "REVISION_REQUESTED" -> "REVISION_REQUESTED";
            case "BUYER_REJECTED" -> "REJECTED";
            case "BUYER_ACCEPTED" -> {
                if (fulfilled) {
                    yield "FULFILLED";
                }
                if (fulfilling) {
                    yield "FULFILLING";
                }
                yield List.of("PARTIAL", "MIXED").contains(response.getResponseType()) ? "PARTIALLY_ACCEPTED" : "ACCEPTED";
            }
            default -> response.getResponseStatus();
        };
    }

    private boolean isFulfilled(ReceiptSummary receiptSummary, AsnSummary asnSummary) {
        return asnSummary.totalConfirmedQuantity().signum() > 0
            && receiptSummary.receivedQuantity().compareTo(asnSummary.totalConfirmedQuantity()) >= 0;
    }

    private AsnSummary asnSummary(String tenantId, UUID companyId, UUID purchaseOrderId) {
        List<AdvanceShippingNotice> asns = asnRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, companyId, purchaseOrderId);
        long submittedCount = asns.stream().filter(a -> !"DRAFT".equals(a.getStatus()) && !"CANCELLED".equals(a.getStatus())).count();
        BigDecimal totalShipped = asns.stream()
            .filter(a -> !"CANCELLED".equals(a.getStatus()))
            .map(a -> a.getTotalQuantity() == null ? BigDecimal.ZERO : a.getTotalQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalConfirmed = commitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                tenantId, companyId, purchaseOrderId, List.of("CONFIRMED")
            )
            .stream()
            .map(c -> c.getConfirmedQuantity() == null ? BigDecimal.ZERO : c.getConfirmedQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AsnSummary(submittedCount, totalConfirmed, totalShipped);
    }

    private ReceiptSummary receiptSummary(String tenantId, UUID purchaseOrderId) {
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, purchaseOrderId);
        BigDecimal received = sum(items, PurchaseOrderItem::getReceivedQuantity);
        BigDecimal rejected = sum(items, PurchaseOrderItem::getRejectedQuantity);
        BigDecimal damaged = sum(items, PurchaseOrderItem::getDamagedQuantity);
        return new ReceiptSummary(received, rejected, damaged);
    }

    private BigDecimal sum(List<PurchaseOrderItem> items, java.util.function.Function<PurchaseOrderItem, BigDecimal> extractor) {
        return items.stream().map(extractor).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public DispatchView toDispatchView(SupplierPoDispatch dispatch) {
        return new DispatchView(
            dispatch.getId(), dispatch.getVersion(), dispatch.getDispatchStatus(), dispatch.getRecipientEmail(), dispatch.getPurchaseOrderVersion(),
            dispatch.isCurrent(), dispatch.getQueuedAt(), dispatch.getSentAt(), dispatch.getDeliveredAt(), dispatch.getViewedAt(),
            dispatch.getResponseDueDate(), dispatch.getLastDeliveryError(), dispatch.getSupersededAt(), dispatch.getCancelledAt()
        );
    }

    public ResponseView toResponseView(SupplierPoResponse response) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<ResponseLineView> lines = responseLineRepository
            .findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(tenantId, companyId, response.getId())
            .stream()
            .map(line -> toLineView(line, tenantId, companyId))
            .toList();
        return new ResponseView(
            response.getId(), response.getVersion(), response.getResponseRevision(), response.getResponseType(), response.getResponseStatus(),
            response.getSupplierComment(), response.getRejectionReason(), response.getSubmittedAt(),
            response.getBuyerDecisionBy(), response.getBuyerDecisionAt(), response.getBuyerDecisionComment(), lines
        );
    }

    private ResponseLineView toLineView(SupplierPoResponseLine line, String tenantId, UUID companyId) {
        List<CommitmentView> commitments = commitmentRepository
            .findByTenantIdAndCompanyIdAndResponseLineIdAndIsDeletedFalse(tenantId, companyId, line.getId())
            .stream()
            .map(this::toCommitmentView)
            .toList();
        return new ResponseLineView(
            line.getId(), line.getPurchaseOrderItemId(), line.getDecision(), line.getOrderedQuantity(),
            line.getOpenQuantityAtDispatch(), line.getAcceptedQuantity(), line.getUnconfirmedQuantity(),
            line.getReasonCode(), line.getSupplierComment(), commitments
        );
    }

    public CommitmentView toCommitmentView(SupplierPoCommitment commitment) {
        ReconfirmationEligibility reconfirmation = reconfirmationEligibility(commitment);
        return new CommitmentView(
            commitment.getId(), commitment.getVersion(), commitment.getProposedQuantity(), commitment.getProposedDate(),
            commitment.getConfirmedQuantity(), commitment.getConfirmedDate(), commitment.getStatus(),
            commitment.getDeliveryScheduleId(), commitment.getSupersededCommitmentId(),
            reconfirmation.allowed(), reconfirmation.blockedReason(), reconfirmation.activeResponseId()
        );
    }

    private ReconfirmationEligibility reconfirmationEligibility(SupplierPoCommitment commitment) {
        if (commitment == null || commitment.getId() == null) {
            return ReconfirmationEligibility.blocked("Commitment is unavailable");
        }
        if (!"CANCELLED".equals(commitment.getStatus()) || commitment.isCurrent()) {
            return ReconfirmationEligibility.blocked("Only a cancelled non-current commitment can be reconfirmed");
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        var activeResponse = responseRepository
            .findByTenantIdAndCompanyIdAndReconfirmationOriginCommitmentIdAndCurrentTrueAndResponseStatusInAndIsDeletedFalse(
                tenantId, companyId, commitment.getId(), List.of("DRAFT", "SUBMITTED", "REVISION_REQUESTED")
            );
        if (activeResponse.isPresent()) {
            return new ReconfirmationEligibility(false, "Supplier reconfirmation is already pending", activeResponse.get().getId());
        }
        boolean replacementExists = !commitmentRepository
            .findByTenantIdAndCompanyIdAndSupersededCommitmentIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                tenantId, companyId, commitment.getId(), List.of("CONFIRMED")
            ).isEmpty();
        if (replacementExists) {
            return ReconfirmationEligibility.blocked("Replacement commitment already exists");
        }
        return new ReconfirmationEligibility(true, null, null);
    }

    private record ReconfirmationEligibility(boolean allowed, String blockedReason, UUID activeResponseId) {
        static ReconfirmationEligibility blocked(String reason) {
            return new ReconfirmationEligibility(false, reason, null);
        }
    }
}
