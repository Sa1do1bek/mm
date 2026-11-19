package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPoResponseLine;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer review of a SUBMITTED {@link SupplierPoResponse}: accept (confirms
 * commitments and projects line-aware {@link DeliverySchedule} rows), reject, or
 * request a revision. {@code accept} is idempotent -- a retry does not create
 * duplicate schedule rows because each schedule projection is guarded by an
 * existence check on {@code supplier_po_commitment_id} first.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoBuyerDecisionService {
    private final SupplierPoResponseRepository responseRepository;
    private final SupplierPoResponseLineRepository responseLineRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final DeliveryScheduleRepository deliveryScheduleRepository;
    private final AdvanceShippingNoticeItemRepository asnItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierPoDispatchRepository dispatchRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final SupplierPoReconfirmationNotificationService reconfirmationNotificationService;

    @Transactional
    public SupplierPoResponse accept(UUID responseId, String comment) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse response = requireSubmittedResponse(responseId, tenantId, companyId);

        List<SupplierPoResponseLine> lines = responseLineRepository
            .findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(tenantId, companyId, responseId);
        String actor = currentUsername();
        LocalDateTime now = LocalDateTime.now();
        for (SupplierPoResponseLine line : lines) {
            if ("REJECTED".equals(line.getDecision())) {
                continue;
            }
            List<SupplierPoCommitment> commitments = commitmentRepository
                .findByTenantIdAndCompanyIdAndResponseLineIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, line.getId());
            if (commitments.isEmpty() && shouldCreateDefaultCommitment(line)) {
                SupplierPoCommitment commitment = createDefaultCommitment(response, line, tenantId, companyId, actor, now);
                projectDeliverySchedule(commitment, tenantId, companyId, actor);
                continue;
            }
            for (SupplierPoCommitment commitment : commitments) {
                confirmCommitment(commitment, line, commitments.size(), actor, now);
                projectDeliverySchedule(commitment, tenantId, companyId, actor);
            }
        }

        response.setResponseStatus("BUYER_ACCEPTED");
        response.setBuyerDecisionBy(actor);
        response.setBuyerDecisionAt(now);
        response.setBuyerDecisionComment(comment);
        SupplierPoResponse saved = responseRepository.save(response);
        auditService.log(
            "PurchaseOrder", response.getPurchaseOrderId(), "PO_SUPPLIER_RESPONSE_ACCEPTED", saved.getCode(), null, null
        );
        auditService.log(
            "PurchaseOrder", response.getPurchaseOrderId(), "PO_SUPPLIER_COMMITMENT_CONFIRMED", saved.getCode(), null, null
        );
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Buyer decision recorded",
            "Buyer accepted supplier response " + saved.getCode() + "; commitments confirmed.",
            "PO buyer decision " + saved.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            saved.getPurchaseOrderId()
        );
        return saved;
    }

    private boolean shouldCreateDefaultCommitment(SupplierPoResponseLine line) {
        return List.of("ACCEPTED", "PARTIALLY_ACCEPTED").contains(line.getDecision())
            && nvl(line.getAcceptedQuantity()).signum() > 0;
    }

    private SupplierPoCommitment createDefaultCommitment(
        SupplierPoResponse response,
        SupplierPoResponseLine line,
        String tenantId,
        UUID companyId,
        String actor,
        LocalDateTime now
    ) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(response.getPurchaseOrderId())
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + response.getPurchaseOrderId()));
        PurchaseOrderItem item = purchaseOrderItem(line, tenantId, companyId);
        BigDecimal acceptedQuantity = nvl(line.getAcceptedQuantity());
        LocalDate commitmentDate = purchaseOrder.getExpectedDeliveryDate() == null
            ? LocalDate.now()
            : purchaseOrder.getExpectedDeliveryDate();

        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setCode("PO-COMMITMENT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        commitment.setName(BusinessNameGenerator.workflowDocumentName("Commitment", itemDisplayName(item)));
        commitment.setTenantId(tenantId);
        commitment.setCompanyId(companyId);
        commitment.setStatus("CONFIRMED");
        commitment.setResponseLineId(line.getId());
        commitment.setPurchaseOrderId(response.getPurchaseOrderId());
        commitment.setPurchaseOrderItemId(line.getPurchaseOrderItemId());
        commitment.setSupplierId(response.getSupplierId());
        commitment.setProposedQuantity(acceptedQuantity);
        commitment.setConfirmedQuantity(acceptedQuantity);
        commitment.setProposedDate(commitmentDate);
        commitment.setConfirmedDate(commitmentDate);
        commitment.setConfirmedBy(actor);
        commitment.setConfirmedAt(now);
        commitment.setCurrent(true);
        SupplierPoCommitment saved = commitmentRepository.save(commitment);
        return saved == null ? commitment : saved;
    }

    private PurchaseOrderItem purchaseOrderItem(SupplierPoResponseLine line, String tenantId, UUID companyId) {
        PurchaseOrderItem item = line.getPurchaseOrderItem();
        if (item != null) {
            return item;
        }
        return purchaseOrderItemRepository.findByIdAndIsDeletedFalse(line.getPurchaseOrderItemId())
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Purchase order item not found: " + line.getPurchaseOrderItemId()));
    }

    private String itemDisplayName(PurchaseOrderItem item) {
        return item.getName() == null || item.getName().isBlank() ? "Purchase order item" : item.getName().trim();
    }

    @Transactional
    public SupplierPoResponse reject(UUID responseId, String reason) {
        SupplierPoResponse response = decide(responseId, "BUYER_REJECTED", reason);
        auditService.log(
            "PurchaseOrder", response.getPurchaseOrderId(), "PO_SUPPLIER_RESPONSE_REJECTED", response.getCode(), null, null
        );
        return response;
    }

    @Transactional
    public SupplierPoResponse requestRevision(UUID responseId, String comment) {
        SupplierPoResponse response = decide(responseId, "REVISION_REQUESTED", comment);
        auditService.log(
            "PurchaseOrder", response.getPurchaseOrderId(), "PO_SUPPLIER_REVISION_REQUESTED", response.getCode(), null, null
        );
        return response;
    }

    private SupplierPoResponse decide(UUID responseId, String status, String comment) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse response = requireSubmittedResponse(responseId, tenantId, companyId);
        response.setResponseStatus(status);
        response.setBuyerDecisionBy(currentUsername());
        response.setBuyerDecisionAt(LocalDateTime.now());
        response.setBuyerDecisionComment(comment);
        if ("BUYER_REJECTED".equals(status)) {
            response.setRejectionReason(comment);
        }
        SupplierPoResponse saved = responseRepository.save(response);
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Buyer decision recorded",
            "Buyer decision " + status + " recorded for supplier response " + saved.getCode() + ".",
            "PO buyer decision " + saved.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            saved.getPurchaseOrderId()
        );
        return saved;
    }

    private void confirmCommitment(
        SupplierPoCommitment commitment,
        SupplierPoResponseLine line,
        int commitmentCount,
        String actor,
        LocalDateTime now
    ) {
        boolean alreadyConfirmed = "CONFIRMED".equals(commitment.getStatus());
        commitment.setStatus("CONFIRMED");
        commitment.setConfirmedQuantity(confirmedQuantity(commitment, line, commitmentCount));
        commitment.setConfirmedDate(commitment.getProposedDate());
        if (!alreadyConfirmed || commitment.getConfirmedBy() == null) {
            commitment.setConfirmedBy(actor);
        }
        if (!alreadyConfirmed || commitment.getConfirmedAt() == null) {
            commitment.setConfirmedAt(now);
        }
        commitmentRepository.save(commitment);
    }

    private BigDecimal confirmedQuantity(SupplierPoCommitment commitment, SupplierPoResponseLine line, int commitmentCount) {
        BigDecimal acceptedQuantity = nvl(line.getAcceptedQuantity());
        BigDecimal proposedQuantity = nvl(commitment.getProposedQuantity());
        if (commitmentCount == 1 && acceptedQuantity.signum() > 0) {
            return acceptedQuantity;
        }
        return proposedQuantity.signum() > 0 ? proposedQuantity : acceptedQuantity;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void projectDeliverySchedule(SupplierPoCommitment commitment, String tenantId, UUID companyId, String actor) {
        var existing = deliveryScheduleRepository
            .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(tenantId, companyId, commitment.getId());
        if (existing.isPresent()) {
            if (commitment.getDeliveryScheduleId() == null) {
                commitment.setDeliveryScheduleId(existing.get().getId());
                commitmentRepository.save(commitment);
            }
            return;
        }
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(commitment.getPurchaseOrderId())
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + commitment.getPurchaseOrderId()));

        DeliverySchedule schedule = new DeliverySchedule();
        schedule.setCode("PO-DELIVERY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        schedule.setName(BusinessNameGenerator.workflowDocumentName("DeliverySchedule", purchaseOrder.getName()));
        schedule.setStatus("PLANNED");
        schedule.setPurchaseOrderId(commitment.getPurchaseOrderId());
        schedule.setPurchaseOrderItemId(commitment.getPurchaseOrderItemId());
        schedule.setSupplierPoCommitmentId(commitment.getId());
        schedule.setSource("SUPPLIER_COMMITMENT");
        schedule.setScheduleDate(commitment.getConfirmedDate());
        schedule.setPlannedQuantity(commitment.getConfirmedQuantity() == null ? BigDecimal.ZERO : commitment.getConfirmedQuantity());
        DeliverySchedule saved = deliveryScheduleRepository.save(schedule);

        commitment.setDeliveryScheduleId(saved.getId());
        commitmentRepository.save(commitment);
    }

    /** Buyer cancels a still-open confirmed commitment (e.g. no longer needed). Kept on
     * this service rather than a new sibling class -- the class is small and this is
     * clearly the same "buyer decision on a commitment" concern as accept/reject. */
    @Transactional
    public SupplierPoCommitment cancelCommitment(UUID commitmentId, Long expectedVersion, String reason) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoCommitment commitment = commitmentRepository.findByIdAndIsDeletedFalse(commitmentId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO commitment not found: " + commitmentId));
        if (expectedVersion != null && !expectedVersion.equals(commitment.getVersion())) {
            throw new BusinessException("Commitment changed by another user; refresh and retry");
        }
        if (!List.of("PROPOSED", "CONFIRMED").contains(commitment.getStatus())) {
            throw new BusinessException("Only a PROPOSED or CONFIRMED commitment can be cancelled, current status is " + commitment.getStatus());
        }
        if (!asnItemRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            tenantId, companyId, commitment.getId()
        ).isEmpty()) {
            throw new BusinessException("Commitment already has ASN activity and cannot be cancelled");
        }
        deliveryScheduleRepository
            .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(tenantId, companyId, commitment.getId())
            .ifPresent(schedule -> {
                if (nvl(schedule.getReceivedQuantity()).signum() > 0) {
                    throw new BusinessException("Commitment already has received quantity and cannot be cancelled");
                }
            });
        commitment.setStatus("CANCELLED");
        commitment.setCurrent(false);
        SupplierPoCommitment saved = commitmentRepository.save(commitment);
        deliveryScheduleRepository
            .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(tenantId, companyId, commitment.getId())
            .ifPresent(schedule -> {
                schedule.setStatus("CANCELLED");
                schedule.setControlledAt(LocalDateTime.now());
                deliveryScheduleRepository.save(schedule);
            });
        auditService.log(
            "PurchaseOrder", commitment.getPurchaseOrderId(), "PO_SUPPLIER_COMMITMENT_CANCELLED",
            reason == null || reason.isBlank() ? saved.getCode() : reason.trim(), null, null
        );
        return saved;
    }

    @Transactional
    public SupplierPoResponse requestReconfirmation(UUID commitmentId, Long expectedVersion, String reason) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoCommitment cancelled = commitmentRepository.findByIdAndIsDeletedFalse(commitmentId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO commitment not found: " + commitmentId));
        if (expectedVersion != null && !expectedVersion.equals(cancelled.getVersion())) {
            throw new BusinessException("Commitment changed by another user; refresh and retry");
        }
        if (!"CANCELLED".equals(cancelled.getStatus()) || cancelled.isCurrent()) {
            throw new BusinessException("Only a non-current CANCELLED commitment can be reconfirmed");
        }
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(cancelled.getPurchaseOrderId())
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + cancelled.getPurchaseOrderId()));
        if (List.of("CLOSED", "CANCELLED").contains(purchaseOrder.getStatus())) {
            throw new BusinessException("Purchase order status does not permit supplier reconfirmation");
        }
        SupplierPoResponseLine originLine = responseLineRepository.findByIdAndIsDeletedFalse(cancelled.getResponseLineId())
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO response line not found: " + cancelled.getResponseLineId()));
        SupplierPoResponse originResponse = responseRepository.findByIdAndIsDeletedFalse(originLine.getResponseId())
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO response not found: " + originLine.getResponseId()));
        if (!"BUYER_ACCEPTED".equals(originResponse.getResponseStatus())) {
            throw new BusinessException("Only a buyer-accepted supplier response can be reconfirmed");
        }
        if (!Objects.equals(originResponse.getPurchaseOrderId(), cancelled.getPurchaseOrderId())
            || !Objects.equals(originResponse.getSupplierId(), cancelled.getSupplierId())) {
            throw new BusinessException("Commitment does not match the accepted supplier response");
        }
        if (!commitmentRepository.findByTenantIdAndCompanyIdAndSupersededCommitmentIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
            tenantId, companyId, cancelled.getId(), List.of("CONFIRMED")
        ).isEmpty()) {
            throw new BusinessException("Replacement commitment already exists for this cancelled commitment");
        }
        var activeReconfirmation = responseRepository
            .findByTenantIdAndCompanyIdAndReconfirmationOriginCommitmentIdAndCurrentTrueAndResponseStatusInAndIsDeletedFalse(
                tenantId, companyId, cancelled.getId(), List.of("DRAFT", "SUBMITTED", "REVISION_REQUESTED")
            );
        if (activeReconfirmation.isPresent()) {
            return activeReconfirmation.get();
        }
        var dispatch = dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, purchaseOrder.getId())
            .orElseThrow(() -> new BusinessException("Purchase order has no current supplier dispatch"));
        if ("SUPERSEDED".equals(dispatch.getDispatchStatus()) || "CANCELLED".equals(dispatch.getDispatchStatus())) {
            throw new BusinessException("Current supplier dispatch cannot receive reconfirmation");
        }

        originResponse.setCurrent(false);
        responseRepository.saveAndFlush(originResponse);

        String actor = currentUsername();
        LocalDateTime now = LocalDateTime.now();
        SupplierPoResponse draft = new SupplierPoResponse();
        draft.setCode("PO-RESPONSE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        draft.setName(BusinessNameGenerator.workflowDocumentName("SupplierPoResponse", dispatch.getCode()));
        draft.setDispatchId(dispatch.getId());
        draft.setPurchaseOrderId(purchaseOrder.getId());
        draft.setSupplierId(cancelled.getSupplierId());
        draft.setPortalAccountId(dispatch.getPortalAccountId());
        draft.setResponseRevision(nextResponseRevision(dispatch.getId(), tenantId, companyId));
        draft.setResponseStatus("DRAFT");
        draft.setSupplierComment(reason);
        draft.setSupersededResponseId(originResponse.getId());
        draft.setReconfirmationOriginCommitmentId(cancelled.getId());
        draft.setReconfirmationReason(reason);
        draft.setReconfirmationRequestedBy(actor);
        draft.setReconfirmationRequestedAt(now);
        draft.setCurrent(true);
        SupplierPoResponse savedDraft = responseRepository.saveAndFlush(draft);

        SupplierPoResponseLine line = new SupplierPoResponseLine();
        line.setCode("PO-RESPONSE-LINE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        line.setName("Reconfirmation line - " + itemDisplayName(purchaseOrderItem(originLine, tenantId, companyId)));
        line.setResponseId(savedDraft.getId());
        line.setPurchaseOrderItemId(cancelled.getPurchaseOrderItemId());
        line.setLineSnapshotJson(originLine.getLineSnapshotJson());
        line.setDecision("ACCEPTED");
        line.setOrderedQuantity(originLine.getOrderedQuantity());
        line.setOpenQuantityAtDispatch(originLine.getOpenQuantityAtDispatch());
        line.setAcceptedQuantity(quantityForReconfirmation(cancelled));
        line.setUnconfirmedQuantity(BigDecimal.ZERO);
        line.setSupplierComment(reason);
        SupplierPoResponseLine savedLine = responseLineRepository.saveAndFlush(line);

        SupplierPoCommitment proposed = new SupplierPoCommitment();
        proposed.setCode("PO-COMMITMENT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        proposed.setName("Reconfirmation commitment - " + itemDisplayName(purchaseOrderItem(originLine, tenantId, companyId)));
        proposed.setStatus("PROPOSED");
        proposed.setResponseLineId(savedLine.getId());
        proposed.setPurchaseOrderId(cancelled.getPurchaseOrderId());
        proposed.setPurchaseOrderItemId(cancelled.getPurchaseOrderItemId());
        proposed.setSupplierId(cancelled.getSupplierId());
        proposed.setProposedQuantity(quantityForReconfirmation(cancelled));
        proposed.setProposedDate(cancelled.getConfirmedDate() == null ? cancelled.getProposedDate() : cancelled.getConfirmedDate());
        proposed.setSupersededCommitmentId(cancelled.getId());
        proposed.setCurrent(true);
        commitmentRepository.save(proposed);

        auditService.log(
            "PurchaseOrder", purchaseOrder.getId(), "PO_SUPPLIER_RECONFIRMATION_REQUESTED",
            reason == null || reason.isBlank() ? cancelled.getCode() : reason.trim(), null, null
        );
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Supplier reconfirmation requested",
            "Supplier reconfirmation requested for purchase order " + purchaseOrder.getCode() + ".",
            "PO supplier reconfirmation " + purchaseOrder.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            purchaseOrder.getId()
        );
        reconfirmationNotificationService.queue(savedDraft, cancelled, purchaseOrder, reason, actor);
        return savedDraft;
    }

    private BigDecimal quantityForReconfirmation(SupplierPoCommitment commitment) {
        BigDecimal confirmed = nvl(commitment.getConfirmedQuantity());
        return confirmed.signum() > 0 ? confirmed : nvl(commitment.getProposedQuantity());
    }

    private int nextResponseRevision(UUID dispatchId, String tenantId, UUID companyId) {
        return responseRepository
            .findByTenantIdAndCompanyIdAndDispatchIdAndIsDeletedFalseOrderByResponseRevisionDesc(
                tenantId, companyId, dispatchId
            )
            .stream()
            .mapToInt(SupplierPoResponse::getResponseRevision)
            .max()
            .orElse(0) + 1;
    }

    private SupplierPoResponse requireSubmittedResponse(UUID responseId, String tenantId, UUID companyId) {
        SupplierPoResponse response = responseRepository.findByIdAndIsDeletedFalse(responseId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO response not found: " + responseId));
        if (!"SUBMITTED".equals(response.getResponseStatus())) {
            throw new BusinessException("Only a SUBMITTED response can receive a buyer decision, current status is " + response.getResponseStatus());
        }
        return response;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
