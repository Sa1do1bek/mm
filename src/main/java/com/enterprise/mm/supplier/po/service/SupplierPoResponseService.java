package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPoResponseLine;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.CommitmentCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.LineDecisionCommand;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft / submit / replace / withdraw lifecycle for a {@link SupplierPoResponse}
 * against a {@link SupplierPoDispatch}. Submitted responses are immutable: there is
 * no "edit a submitted response" method here on purpose -- corrections always go
 * through {@link #replace(UUID, String)}, which creates the next revision.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoResponseService {
    private static final List<String> VALID_DECISIONS =
        List.of("ACCEPTED", "PARTIALLY_ACCEPTED", "CHANGE_REQUESTED", "REJECTED");

    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierPoResponseRepository responseRepository;
    private final SupplierPoResponseLineRepository responseLineRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierPoSnapshotService snapshotService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public SupplierPoResponse startOrGetDraft(UUID dispatchId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoDispatch dispatch = requireDispatchable(dispatchId, tenantId, companyId);

        var current = responseRepository
            .findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, dispatchId);
        if (current.isPresent()) {
            SupplierPoResponse existing = current.get();
            if ("DRAFT".equals(existing.getResponseStatus())) {
                return existing;
            }
            throw new BusinessException(
                "A response already exists for this dispatch in status " + existing.getResponseStatus()
                    + "; use replace() to start a new revision"
            );
        }

        SupplierPoResponse response = new SupplierPoResponse();
        response.setCode("PO-RESPONSE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        // Use dispatch code, not dispatch.name: name is already a multilingual label and
        // nesting workflowDocumentName() exceeds varchar(240) on older schemas.
        response.setName(BusinessNameGenerator.workflowDocumentName("SupplierPoResponse", shortNameSource(dispatch)));
        response.setDispatchId(dispatchId);
        response.setPurchaseOrderId(dispatch.getPurchaseOrderId());
        response.setSupplierId(dispatch.getSupplierId());
        response.setPortalAccountId(dispatch.getPortalAccountId());
        response.setResponseRevision(nextResponseRevision(dispatchId, tenantId, companyId));
        response.setResponseStatus("DRAFT");
        response.setCurrent(true);
        return responseRepository.saveAndFlush(response);
    }

    @Transactional
    public SupplierPoResponse saveDraftLines(UUID responseId, List<LineDecisionCommand> lines, String supplierComment) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse response = requireResponse(responseId, tenantId, companyId);
        if (!"DRAFT".equals(response.getResponseStatus())) {
            throw new BusinessException("Only a DRAFT response can be edited; submitted responses are immutable");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("At least one line decision is required");
        }
        SupplierPoDispatch dispatch = requireDispatch(response.getDispatchId(), tenantId, companyId);

        replaceLines(response, dispatch, lines, tenantId, companyId);
        response.setSupplierComment(supplierComment);
        return responseRepository.save(response);
    }

    @Transactional
    public SupplierPoResponse submit(UUID responseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse response = requireResponse(responseId, tenantId, companyId);
        if (!"DRAFT".equals(response.getResponseStatus())) {
            throw new BusinessException("Only a DRAFT response can be submitted");
        }
        SupplierPoDispatch dispatch = requireDispatchable(response.getDispatchId(), tenantId, companyId);

        List<SupplierPoResponseLine> lines = responseLineRepository
            .findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(tenantId, companyId, responseId);
        List<PurchaseOrderItem> dispatchedItems = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, dispatch.getPurchaseOrderId());
        if (response.getReconfirmationOriginCommitmentId() == null) {
            validateEveryLineDecided(dispatchedItems, lines);
        } else if (lines.isEmpty()) {
            throw new BusinessException("Reconfirmation response requires the affected purchase order line");
        }

        response.setResponseStatus("SUBMITTED");
        response.setResponseType(summarizeResponseType(lines));
        response.setSubmittedAt(LocalDateTime.now());
        SupplierPoResponse saved = responseRepository.save(response);
        auditService.log(
            "PurchaseOrder", dispatch.getPurchaseOrderId(), "PO_SUPPLIER_RESPONSE_SUBMITTED",
            saved.getCode(), null, null
        );
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Supplier response received",
            "Supplier submitted a response for purchase order dispatch " + dispatch.getCode() + ".",
            "Supplier PO response " + saved.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            dispatch.getPurchaseOrderId()
        );
        return saved;
    }

    /** Creates the next DRAFT revision superseding a submitted response. Only allowed
     * while the dispatch is still current (not superseded/cancelled). */
    @Transactional
    public SupplierPoResponse replace(UUID currentResponseId, String supplierComment) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse current = requireResponse(currentResponseId, tenantId, companyId);
        if (!List.of("SUBMITTED", "REVISION_REQUESTED", "BUYER_REJECTED").contains(current.getResponseStatus())) {
            throw new BusinessException("Only a submitted response can be replaced, current status is " + current.getResponseStatus());
        }
        SupplierPoDispatch dispatch = requireDispatchable(current.getDispatchId(), tenantId, companyId);

        current.setResponseStatus("SUPERSEDED");
        current.setCurrent(false);
        // Release the partial-unique-index slot before inserting the next revision.
        responseRepository.saveAndFlush(current);

        SupplierPoResponse next = new SupplierPoResponse();
        next.setCode("PO-RESPONSE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        next.setName(BusinessNameGenerator.workflowDocumentName("SupplierPoResponse", shortNameSource(dispatch)));
        next.setDispatchId(dispatch.getId());
        next.setPurchaseOrderId(dispatch.getPurchaseOrderId());
        next.setSupplierId(dispatch.getSupplierId());
        next.setPortalAccountId(dispatch.getPortalAccountId());
        next.setResponseRevision(current.getResponseRevision() + 1);
        next.setResponseStatus("DRAFT");
        next.setSupplierComment(supplierComment);
        next.setSupersededResponseId(current.getId());
        next.setReconfirmationOriginCommitmentId(current.getReconfirmationOriginCommitmentId());
        next.setReconfirmationReason(current.getReconfirmationReason());
        next.setReconfirmationRequestedBy(current.getReconfirmationRequestedBy());
        next.setReconfirmationRequestedAt(current.getReconfirmationRequestedAt());
        next.setCurrent(true);
        SupplierPoResponse saved = responseRepository.saveAndFlush(next);
        auditService.log(
            "PurchaseOrder", dispatch.getPurchaseOrderId(), "PO_SUPPLIER_RESPONSE_REPLACED",
            saved.getCode(), null, null
        );
        return saved;
    }

    @Transactional
    public SupplierPoResponse withdraw(UUID responseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        SupplierPoResponse response = requireResponse(responseId, tenantId, companyId);
        if (!List.of("DRAFT", "SUBMITTED").contains(response.getResponseStatus())) {
            throw new BusinessException("Response cannot be withdrawn from status " + response.getResponseStatus());
        }
        response.setResponseStatus("WITHDRAWN");
        response.setWithdrawnAt(LocalDateTime.now());
        response.setCurrent(false);
        return responseRepository.save(response);
    }

    private void replaceLines(
        SupplierPoResponse response,
        SupplierPoDispatch dispatch,
        List<LineDecisionCommand> lines,
        String tenantId,
        UUID companyId
    ) {
        List<SupplierPoResponseLine> existingLines = responseLineRepository
            .findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(tenantId, companyId, response.getId());
        for (SupplierPoResponseLine existing : existingLines) {
            for (SupplierPoCommitment commitment : commitmentRepository
                .findByTenantIdAndCompanyIdAndResponseLineIdAndIsDeletedFalse(tenantId, companyId, existing.getId())) {
                commitment.softDelete();
                commitmentRepository.save(commitment);
            }
            existing.softDelete();
            responseLineRepository.save(existing);
        }

        Map<UUID, PurchaseOrderItem> itemsById = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, dispatch.getPurchaseOrderId())
            .stream()
            .collect(Collectors.toMap(PurchaseOrderItem::getId, item -> item));
        UUID reconfirmationOriginCommitmentId = response.getReconfirmationOriginCommitmentId();
        UUID reconfirmationItemId = null;
        if (reconfirmationOriginCommitmentId != null) {
            SupplierPoCommitment origin = commitmentRepository.findByIdAndIsDeletedFalse(reconfirmationOriginCommitmentId)
                .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
                .orElseThrow(() -> new BusinessException("Reconfirmation origin commitment not found"));
            reconfirmationItemId = origin.getPurchaseOrderItemId();
        }

        for (LineDecisionCommand command : lines) {
            PurchaseOrderItem item = itemsById.get(command.purchaseOrderItemId());
            if (item == null) {
                throw new BusinessException("Purchase order item does not belong to this dispatch: " + command.purchaseOrderItemId());
            }
            if (reconfirmationItemId != null && !Objects.equals(reconfirmationItemId, item.getId())) {
                throw new BusinessException("Reconfirmation response can only update the cancelled commitment line");
            }
            String decision = normalizeDecision(command.decision());
            BigDecimal openQuantity = openQuantity(item);
            List<CommitmentCommand> commitments = command.commitments() == null ? List.of() : command.commitments();
            BigDecimal proposedTotal = commitments.stream()
                .map(c -> nvl(c.proposedQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal acceptedQuantity = acceptedQuantity(command.acceptedQuantity(), proposedTotal);

            validateLineDecision(decision, command.reasonCode(), commitments, acceptedQuantity, proposedTotal, openQuantity, item.getCode());

            SupplierPoResponseLine line = new SupplierPoResponseLine();
            line.setCode("PO-RESPONSE-LINE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            line.setName("Response line - " + itemDisplayName(item));
            line.setResponseId(response.getId());
            line.setPurchaseOrderItemId(item.getId());
            line.setLineSnapshotJson(snapshotService.lineSnapshotJson(item));
            line.setDecision(decision);
            line.setOrderedQuantity(nvl(item.getQuantity()));
            line.setOpenQuantityAtDispatch(openQuantity);
            line.setAcceptedQuantity("REJECTED".equals(decision) ? BigDecimal.ZERO : acceptedQuantity);
            line.setUnconfirmedQuantity(openQuantity.subtract("REJECTED".equals(decision) ? BigDecimal.ZERO : acceptedQuantity).max(BigDecimal.ZERO));
            line.setReasonCode(command.reasonCode());
            line.setSupplierComment(command.supplierComment());
            SupplierPoResponseLine savedLine = responseLineRepository.saveAndFlush(line);

            for (CommitmentCommand commitmentCommand : commitments) {
                SupplierPoCommitment commitment = new SupplierPoCommitment();
                commitment.setCode("PO-COMMITMENT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
                commitment.setName("Commitment - " + itemDisplayName(item));
                commitment.setStatus("PROPOSED");
                commitment.setResponseLineId(savedLine.getId());
                commitment.setPurchaseOrderId(dispatch.getPurchaseOrderId());
                commitment.setPurchaseOrderItemId(item.getId());
                commitment.setSupplierId(dispatch.getSupplierId());
                commitment.setProposedQuantity(nvl(commitmentCommand.proposedQuantity()));
                commitment.setProposedDate(commitmentCommand.proposedDate());
                commitment.setSupersededCommitmentId(reconfirmationOriginCommitmentId);
                commitment.setCurrent(true);
                commitmentRepository.save(commitment);
            }
        }
    }

    private void validateLineDecision(
        String decision,
        String reasonCode,
        List<CommitmentCommand> commitments,
        BigDecimal acceptedQuantity,
        BigDecimal proposedTotal,
        BigDecimal openQuantity,
        String itemCode
    ) {
        boolean hasReason = reasonCode != null && !reasonCode.isBlank();
        switch (decision) {
            case "ACCEPTED" -> {
                if (acceptedQuantity.compareTo(openQuantity) != 0) {
                    throw new BusinessException("ACCEPTED line " + itemCode + " must propose the full open quantity (" + openQuantity + ")");
                }
            }
            case "PARTIALLY_ACCEPTED" -> {
                if (!hasReason) {
                    throw new BusinessException("PARTIALLY_ACCEPTED line " + itemCode + " requires a reason");
                }
                if (acceptedQuantity.signum() <= 0 || acceptedQuantity.compareTo(openQuantity) >= 0) {
                    throw new BusinessException("PARTIALLY_ACCEPTED line " + itemCode + " must propose a quantity between 0 and the open quantity");
                }
            }
            case "REJECTED" -> {
                if (!hasReason) {
                    throw new BusinessException("REJECTED line " + itemCode + " requires a reason");
                }
                if (acceptedQuantity.signum() != 0) {
                    throw new BusinessException("REJECTED line " + itemCode + " cannot have accepted quantity");
                }
                if (!commitments.isEmpty()) {
                    throw new BusinessException("REJECTED line " + itemCode + " cannot carry proposed commitments");
                }
            }
            case "CHANGE_REQUESTED" -> {
                if (!hasReason) {
                    throw new BusinessException("CHANGE_REQUESTED line " + itemCode + " requires a reason");
                }
                if (commitments.isEmpty() || proposedTotal.signum() <= 0) {
                    throw new BusinessException("CHANGE_REQUESTED line " + itemCode + " must propose at least one alternate commitment");
                }
            }
            default -> throw new BusinessException("Unsupported line decision: " + decision);
        }
    }

    private BigDecimal acceptedQuantity(BigDecimal requestedAcceptedQuantity, BigDecimal proposedTotal) {
        return requestedAcceptedQuantity == null ? nvl(proposedTotal) : requestedAcceptedQuantity;
    }

    private void validateEveryLineDecided(List<PurchaseOrderItem> dispatchedItems, List<SupplierPoResponseLine> lines) {
        var decidedItemIds = lines.stream().map(SupplierPoResponseLine::getPurchaseOrderItemId).collect(Collectors.toSet());
        List<String> missing = dispatchedItems.stream()
            .filter(item -> !decidedItemIds.contains(item.getId()))
            .map(PurchaseOrderItem::getCode)
            .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("Every dispatched line requires a decision before submit; missing: " + missing);
        }
    }

    private String summarizeResponseType(List<SupplierPoResponseLine> lines) {
        var decisions = lines.stream().map(SupplierPoResponseLine::getDecision).collect(Collectors.toSet());
        if (decisions.size() == 1) {
            String only = decisions.iterator().next();
            if ("ACCEPTED".equals(only)) {
                return "ACCEPT_ALL";
            }
            if ("REJECTED".equals(only)) {
                return "REJECT_ALL";
            }
        }
        if (decisions.contains("PARTIALLY_ACCEPTED") || decisions.contains("CHANGE_REQUESTED")) {
            return decisions.size() > 1 ? "MIXED" : "PARTIAL";
        }
        return "MIXED";
    }

    private String normalizeDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!VALID_DECISIONS.contains(normalized)) {
            throw new BusinessException("Invalid line decision: " + decision);
        }
        return normalized;
    }

    private BigDecimal openQuantity(PurchaseOrderItem item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        return nvl(item.getQuantity())
            .subtract(nvl(item.getReceivedQuantity()))
            .subtract(nvl(item.getRejectedQuantity()))
            .subtract(nvl(item.getDamagedQuantity()))
            .max(BigDecimal.ZERO);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String itemDisplayName(PurchaseOrderItem item) {
        return item.getName() == null || item.getName().isBlank() ? "Purchase order item" : item.getName().trim();
    }

    private static String shortNameSource(SupplierPoDispatch dispatch) {
        if (dispatch.getCode() != null && !dispatch.getCode().isBlank()) {
            return dispatch.getCode();
        }
        return dispatch.getId() == null ? "dispatch" : dispatch.getId().toString();
    }

    private SupplierPoDispatch requireDispatch(UUID dispatchId, String tenantId, UUID companyId) {
        SupplierPoDispatch dispatch = dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO dispatch not found: " + dispatchId));
        return dispatch;
    }

    private SupplierPoDispatch requireDispatchable(UUID dispatchId, String tenantId, UUID companyId) {
        SupplierPoDispatch dispatch = requireDispatch(dispatchId, tenantId, companyId);
        if (!dispatch.isCurrent() || "SUPERSEDED".equals(dispatch.getDispatchStatus()) || "CANCELLED".equals(dispatch.getDispatchStatus())) {
            throw new BusinessException("Dispatch is no longer current; the purchase order must be re-approved and re-sent");
        }
        return dispatch;
    }

    private SupplierPoResponse requireResponse(UUID responseId, String tenantId, UUID companyId) {
        return responseRepository.findByIdAndIsDeletedFalse(responseId)
            .filter(found -> tenantId.equals(found.getTenantId()) && Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO response not found: " + responseId));
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
}
