package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementCloseoutBlockerCode;
import com.enterprise.mm.enums.ProcurementCloseoutDimension;
import com.enterprise.mm.enums.ProcurementCloseoutLiabilityDisposition;
import com.enterprise.mm.enums.ProcurementCloseoutReadinessStatus;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.ProcurementCloseoutType;
import com.enterprise.mm.enums.ProcurementFinancialClosePolicy;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.QualityClaimStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceClaimStatus;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.enums.WorkInspectionResult;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.BudgetLedgerReconciliationResult;
import com.enterprise.mm.payload.ProcurementCloseoutDtos;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.BudgetTransactionRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementCloseoutRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.QualityClaimRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementCloseoutApprovalAdapter;
import com.enterprise.mm.service.approval.ProcurementCloseoutReopenApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementCloseoutReadinessService {
    private static final List<String> COMPLETE_PO_STATUSES = List.of(
        PurchaseOrderStatus.RECEIVED.name(),
        PurchaseOrderStatus.COMPLETED.name(),
        PurchaseOrderStatus.CLOSED.name()
    );

    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementAwardOutcomeRepository awardOutcomeRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final BudgetTransactionRepository budgetTransactionRepository;
    private final ProcurementCloseoutRepository closeoutRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final BudgetItemService budgetItemService;
    private final ApprovalEngineService approvalEngineService;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final QualityClaimRepository qualityClaimRepository;
    private final ServiceClaimRepository serviceClaimRepository;
    private final ProcurementCloseoutAuditHistoryService closeoutAuditHistoryService;
    private final AssetAcceptanceRepository assetAcceptanceRepository;
    private final AssetAcceptanceLineRepository assetAcceptanceLineRepository;
    private final AssetAcceptanceUnitRepository assetAcceptanceUnitRepository;

    @Transactional(readOnly = true)
    public ProcurementCloseoutDtos.Readiness readiness(UUID procedureId) {
        ProcurementProcedure procedure = procedure(procedureId);
        return calculate(procedure, activeCloseout(procedureId));
    }

    @Transactional(readOnly = true)
    public ProcurementCloseoutDtos.Summary summary(UUID procedureId) {
        ProcurementProcedure procedure = procedure(procedureId);
        ProcurementAwardOutcome award = award(procedure);
        PurchaseOrder purchaseOrder = purchaseOrder(procedure, award);
        ProcurementCloseout closeout = activeCloseout(procedureId);
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        if (purchaseOrder != null) {
            List<ContractInvoice> invoices = invoices(procedure, purchaseOrder);
            invoiced = invoices.stream().map(invoice -> nvl(invoice.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            paid = invoices.stream().map(invoice -> nvl(invoice.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return new ProcurementCloseoutDtos.Summary(
            procedure.getId(),
            firstNonBlank(procedure.getProcedureNumber(), procedure.getCode()),
            procedure.getPurchaseRequestId(),
            procedure.getPurchaseRequestId() == null ? null : procedure.getPurchaseRequestId().toString(),
            procedure.getMethod(),
            purchaseOrder == null ? List.of() : List.of(new ProcurementCloseoutDtos.SummarySupplier(
                purchaseOrder.getSupplierId(), null, null
            )),
            award == null ? procedure.getFinalizedAmount() : award.getAwardAmount(),
            purchaseOrder == null ? BigDecimal.ZERO : nvl(purchaseOrder.getTotalAmount()),
            acceptedAmount(purchaseOrder),
            invoiced,
            paid,
            purchaseOrder == null ? firstNonBlank(procedure.getFinalizedCurrencyCode(), "UZS") : purchaseOrder.getCurrencyCode(),
            procedure.getContractId() == null ? List.of() : List.of(new ProcurementCloseoutDtos.SummaryDocument(procedure.getContractId(), null, null)),
            purchaseOrder == null ? List.of() : List.of(new ProcurementCloseoutDtos.SummaryDocument(purchaseOrder.getId(), purchaseOrder.getCode(), purchaseOrder.getStatus())),
            null,
            closeout == null ? null : closeout.getCloseoutStatus(),
            closeout == null ? ProcurementFinancialClosePolicy.PAYMENT_REQUIRED : closeout.getFinancialClosePolicy()
        );
    }

    @Transactional
    public ProcurementCloseoutDtos.Response create(UUID procedureId, ProcurementCloseoutDtos.CreateRequest request) {
        ProcurementProcedure procedure = procedure(procedureId);
        ProcurementCloseout closeout = activeCloseout(procedureId);
        if (closeout == null) {
            closeout = new ProcurementCloseout();
            closeout.setTenantId(TenantContext.currentTenant());
            closeout.setCompanyId(CompanyContext.currentCompany());
            closeout.setProcurementProcedureId(procedureId);
            closeout.setPurchaseRequestId(procedure.getPurchaseRequestId());
            closeout.setCode("PROC-CLOSEOUT-" + procedureId.toString().substring(0, 8).toUpperCase());
            closeout.setName("Procurement closeout " + firstNonBlank(procedure.getProcedureNumber(), procedureId.toString()));
        }
        ProcurementAwardOutcome award = award(procedure);
        PurchaseOrder purchaseOrder = purchaseOrder(procedure, award);
        closeout.setPurchaseOrderId(purchaseOrder == null ? null : purchaseOrder.getId());
        closeout.setCloseoutType(request == null || request.closeoutType() == null ? ProcurementCloseoutType.NORMAL : request.closeoutType());
        closeout.setCloseReason(request == null ? null : request.closeReason());
        closeout.setEarlyCloseReason(request == null ? null : firstNonBlank(request.earlyCloseReason(), request.closeReason()));
        closeout.setLiabilityDisposition(request == null || request.liabilityDisposition() == null
            ? ProcurementCloseoutLiabilityDisposition.NONE : request.liabilityDisposition());
        closeout.setExpectedLiabilityAmount(request == null ? BigDecimal.ZERO : nvl(request.expectedLiabilityAmount()));
        closeout.setUnresolvedLiabilityAmount(request == null ? BigDecimal.ZERO : nvl(request.unresolvedLiabilityAmount()));
        ProcurementCloseoutDtos.Readiness readiness = calculate(procedure, closeout);
        applyReadiness(closeout, readiness);
        closeout.setCloseoutStatus(readiness.readyForNormalClose() ? ProcurementCloseoutStatus.READY : ProcurementCloseoutStatus.BLOCKED);
        closeout.setStatus(closeout.getCloseoutStatus().name());
        closeout = closeoutRepository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "CREATE_OR_UPDATE", "blockers=" + readiness.blockers().size(), false);
        return response(closeout, readiness);
    }

    @Transactional(readOnly = true)
    public ProcurementCloseoutDtos.Response get(UUID closeoutId) {
        ProcurementCloseout closeout = closeout(closeoutId);
        return response(closeout, calculate(procedure(closeout.getProcurementProcedureId()), closeout));
    }

    @Transactional
    public ProcurementCloseoutDtos.Response recheck(UUID closeoutId) {
        ProcurementCloseout closeout = closeout(closeoutId);
        ProcurementCloseoutDtos.Readiness readiness = calculate(procedure(closeout.getProcurementProcedureId()), closeout);
        applyReadiness(closeout, readiness);
        if (closeout.getCloseoutStatus() != ProcurementCloseoutStatus.CLOSED) {
            closeout.setCloseoutStatus(readiness.readyForNormalClose() ? ProcurementCloseoutStatus.READY : ProcurementCloseoutStatus.BLOCKED);
            closeout.setStatus(closeout.getCloseoutStatus().name());
        }
        closeout = closeoutRepository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "RECHECK", "blockers=" + readiness.blockers().size(), false);
        return response(closeout, readiness);
    }

    @Transactional
    public ProcurementCloseoutDtos.Response submit(UUID closeoutId) {
        ProcurementCloseout closeout = closeout(closeoutId);
        ProcurementCloseoutDtos.Readiness readiness = calculate(procedure(closeout.getProcurementProcedureId()), closeout);
        applyReadiness(closeout, readiness);
        boolean exceptional = closeout.getCloseoutType() != ProcurementCloseoutType.NORMAL
            || closeout.getLiabilityDisposition() != ProcurementCloseoutLiabilityDisposition.NONE
            || nvl(closeout.getUnresolvedLiabilityAmount()).signum() > 0;
        if (exceptional) {
            closeout = closeoutRepository.save(closeout);
            var approval = approvalEngineService.start(ProcurementCloseoutApprovalAdapter.MODULE_KEY, closeoutId);
            closeout = closeoutRepository.findById(closeoutId).orElse(closeout);
            closeout.setApprovalInstanceId(approval.id());
            closeout = closeoutRepository.save(closeout);
            closeoutAuditHistoryService.record(closeout, "APPROVAL_LINKED", "approvalInstanceId=" + approval.id(), true);
        } else {
            closeout.setSubmittedAt(LocalDateTime.now());
            closeout.setSubmittedBy(actor());
            closeout.setCloseoutStatus(ProcurementCloseoutStatus.APPROVED);
            closeout.setStatus(closeout.getCloseoutStatus().name());
            closeout = closeoutRepository.save(closeout);
            closeoutAuditHistoryService.record(closeout, "SUBMIT_NORMAL", "auto-approved normal closeout", true);
        }
        return response(closeout, readiness);
    }

    @Transactional
    public ProcurementCloseoutDtos.Response finalize(UUID closeoutId) {
        ProcurementCloseout closeout = closeoutForUpdate(closeoutId);
        ProcurementProcedure procedure = procedure(closeout.getProcurementProcedureId());
        ProcurementAwardOutcome award = award(procedure);
        PurchaseOrder purchaseOrder = purchaseOrderForUpdate(procedure, award);
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.CLOSED) {
            return response(closeout, calculate(procedure, closeout));
        }
        ProcurementCloseoutDtos.Readiness readiness = calculate(procedure, closeout);
        if (closeout.getCloseoutType() == ProcurementCloseoutType.NORMAL && hasNonBudgetBlockers(readiness)) {
            throw new IllegalStateException("Normal procurement closeout is blocked by live readiness blockers");
        }
        if (closeout.getCloseoutType() != ProcurementCloseoutType.NORMAL
            && closeout.getCloseoutStatus() != ProcurementCloseoutStatus.APPROVED) {
            throw new IllegalStateException("Exceptional closeout must be submitted through approval before finalization");
        }
        FinancialSettlement settlement = settleFinancialBalances(closeout, procedure, purchaseOrder);
        reconcileInScopeBudgetLedgers(procedure, purchaseOrder);
        readiness = calculate(procedure, closeout);
        if (!readiness.readyForNormalClose() && closeout.getCloseoutType() == ProcurementCloseoutType.NORMAL) {
            throw new IllegalStateException("Normal procurement closeout is blocked by live readiness blockers after financial settlement");
        }
        applyReadiness(closeout, readiness);
        closeout.setReadinessSnapshot(closeout.getReadinessSnapshot()
            + ";settlement=" + settlement.snapshot()
            + ";finalizeKey=" + finalizeActionKey(closeout));
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.CLOSED);
        closeout.setStatus(ProcurementCloseoutStatus.CLOSED.name());
        closeout.setClosedAt(LocalDateTime.now());
        closeout.setClosedBy(actor());
        closeout = closeoutRepository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "FINALIZE", "closedBy=" + closeout.getClosedBy(), true);
        return response(closeout, readiness);
    }

    @Transactional
    public ProcurementCloseoutDtos.Response cancel(UUID closeoutId, String reason) {
        ProcurementCloseout closeout = closeout(closeoutId);
        closeout.setCancellationReason(required(reason, "Cancellation reason is required"));
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.CANCELLED);
        closeout.setStatus(ProcurementCloseoutStatus.CANCELLED.name());
        closeout = closeoutRepository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "CANCEL", reason, true);
        return response(closeout, calculate(procedure(closeout.getProcurementProcedureId()), closeout));
    }

    @Transactional
    public ProcurementCloseoutDtos.Response requestReopen(UUID closeoutId, ProcurementCloseoutDtos.CommandRequest request) {
        ProcurementCloseout closeout = closeout(closeoutId);
        if (closeout.getCloseoutStatus() != ProcurementCloseoutStatus.CLOSED) {
            throw new IllegalStateException("Only closed procurement closeouts can be reopened");
        }
        closeout.setReopenReason(required(request == null ? null : request.reason(), "Reopen reason is required"));
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.REOPEN_REQUESTED);
        closeout.setStatus(ProcurementCloseoutStatus.REOPEN_REQUESTED.name());
        closeout = closeoutRepository.save(closeout);
        var approval = approvalEngineService.start(ProcurementCloseoutReopenApprovalAdapter.MODULE_KEY, closeoutId);
        closeout = closeoutRepository.findById(closeoutId).orElse(closeout);
        closeout.setApprovalInstanceId(approval.id());
        closeout = closeoutRepository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "REOPEN_APPROVAL_LINKED", "approvalInstanceId=" + approval.id(), true);
        return response(closeout, calculate(procedure(closeout.getProcurementProcedureId()), closeout));
    }

    @Transactional
    public ProcurementCloseoutDtos.Response applyReopen(UUID closeoutId) {
        ProcurementCloseout closeout = closeout(closeoutId);
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.REOPENED) {
            return response(closeout, calculate(procedure(closeout.getProcurementProcedureId()), closeout));
        }
        throw new IllegalStateException("Reopen must be approved through the procurement closeout reopen approval workflow");
    }

    @Transactional
    public Map<String, Object> cancelPoRemainder(UUID purchaseOrderId, ProcurementCloseoutDtos.CancelPoRemainderRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(this::isCurrentCompany)
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        required(request == null ? null : request.reason(), "Cancel-remainder reason is required");
        if (request.quantity() != null && request.quantity().signum() < 0) {
            throw new IllegalArgumentException("Cancellation quantity cannot be negative");
        }
        return Map.of(
            "purchaseOrderId", purchaseOrder.getId(),
            "status", purchaseOrder.getStatus(),
            "reason", request.reason(),
            "idempotencyKey", firstNonBlank(request.idempotencyKey(), "CLOSEOUT:PO:" + purchaseOrderId + ":CANCEL_REMAINDER")
        );
    }

    @Transactional
    public Map<String, Object> resolveRemainingObligation(UUID contractId, ProcurementCloseoutDtos.ResolveRemainingObligationRequest request) {
        required(request == null ? null : request.reason(), "Remaining obligation reason is required");
        if (request.disposition() == null) {
            throw new IllegalArgumentException("Liability disposition is required");
        }
        return Map.of(
            "contractId", contractId,
            "disposition", request.disposition().name(),
            "amount", nvl(request.amount()),
            "currency", firstNonBlank(request.currency(), "UZS"),
            "idempotencyKey", firstNonBlank(request.idempotencyKey(), "CLOSEOUT:CONTRACT:" + contractId + ":RESOLVE_REMAINING_OBLIGATION")
        );
    }

    @Transactional(readOnly = true)
    public ProcurementCloseoutDtos.HistoricalReport historical(boolean dryRun, int page, int size) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<ProcurementCloseoutDtos.HistoricalRow> rows = closeoutRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .skip((long) page * size)
            .limit(size)
            .map(closeout -> {
                ProcurementProcedure procedure = procedureRepository.findByIdAndIsDeletedFalse(closeout.getProcurementProcedureId()).orElse(null);
                ProcurementCloseoutDtos.Readiness readiness = procedure == null ? null : calculate(procedure, closeout);
                boolean blocked = readiness != null && !readiness.blockers().isEmpty();
                return new ProcurementCloseoutDtos.HistoricalRow(
                    closeout.getProcurementProcedureId(),
                    procedure == null ? closeout.getProcurementProcedureId().toString() : firstNonBlank(procedure.getProcedureNumber(), procedure.getCode()),
                    closeout.getPurchaseRequestId() == null ? null : closeout.getPurchaseRequestId().toString(),
                    procedure == null ? null : procedure.getMethod(),
                    null,
                    closeout.getCloseoutStatus() == ProcurementCloseoutStatus.CLOSED && !blocked ? "ALREADY_FULLY_CLOSED" : blocked ? "REQUIRES_MANUAL_REVIEW" : "AUTO_CLOSABLE",
                    blocked ? readiness.blockers().get(0).dimension().name() : null,
                    closeout.getUnresolvedLiabilityAmount(),
                    "UZS",
                    blocked ? readiness.blockers().get(0).recommendedAction() : "Create or finalize closeout"
                );
            })
            .toList();
        long total = closeoutRepository.countByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId);
        return new ProcurementCloseoutDtos.HistoricalReport(rows, page, size, total, size == 0 ? 0 : (int) Math.ceil((double) total / size), dryRun, LocalDateTime.now());
    }

    private ProcurementCloseoutDtos.Readiness calculate(ProcurementProcedure procedure, ProcurementCloseout closeout) {
        ProcurementAwardOutcome award = award(procedure);
        PurchaseOrder purchaseOrder = purchaseOrder(procedure, award);
        Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers = new EnumMap<>(ProcurementCloseoutDimension.class);
        Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> warnings = new EnumMap<>(ProcurementCloseoutDimension.class);
        for (ProcurementCloseoutDimension dimension : ProcurementCloseoutDimension.values()) {
            blockers.put(dimension, new ArrayList<>());
            warnings.put(dimension, new ArrayList<>());
        }

        if (!ProcurementProcedureStatus.COMPLETED.name().equals(procedure.getStatus())) {
            blockers.get(ProcurementCloseoutDimension.SOURCING).add(blocker(
                ProcurementCloseoutDimension.SOURCING,
                ProcurementCloseoutBlockerCode.OPEN_PROCUREMENT_PROCEDURE,
                "PROCUREMENT_PROCEDURE",
                procedure.getId(),
                "Procurement procedure is not completed.",
                "Complete, cancel, or fail the sourcing procedure before closeout."
            ));
        }
        if (award == null && procedure.getPurchaseOrderId() == null && procedure.getContractId() == null) {
            blockers.get(ProcurementCloseoutDimension.SOURCING).add(blocker(
                ProcurementCloseoutDimension.SOURCING,
                ProcurementCloseoutBlockerCode.MISSING_AWARD_OUTCOME,
                "PROCUREMENT_PROCEDURE",
                procedure.getId(),
                "No final award, no-award, cancellation, or commercial output evidence is linked to this procedure.",
                "Finalize an award outcome or record a failed/no-award/cancelled procurement outcome."
            ));
        }
        if (purchaseOrder == null && procedure.getContractId() == null) {
            blockers.get(ProcurementCloseoutDimension.COMMERCIAL_OUTPUTS).add(blocker(
                ProcurementCloseoutDimension.COMMERCIAL_OUTPUTS,
                ProcurementCloseoutBlockerCode.MISSING_PO_OR_CONTRACT,
                "PROCUREMENT_PROCEDURE",
                procedure.getId(),
                "Required PO or contract output is missing.",
                "Create the PO/contract from the approved award or classify the procedure as failed/no-award."
            ));
        }
        if (purchaseOrder != null && !COMPLETE_PO_STATUSES.contains(purchaseOrder.getStatus())) {
            blockers.get(fulfillmentDimension(purchaseOrder)).add(blocker(
                fulfillmentDimension(purchaseOrder),
                purchaseOrder.getRequestType() == RequestType.SERVICE
                    ? ProcurementCloseoutBlockerCode.SERVICE_EXECUTION_INCOMPLETE
                    : ProcurementCloseoutBlockerCode.RECEIPT_PENDING,
                "PURCHASE_ORDER",
                purchaseOrder.getId(),
                "Purchase order is not operationally complete.",
                purchaseOrder.getRequestType() == RequestType.GOODS
                    ? "Complete receipt/quality or cancel the remaining PO quantity."
                    : "Complete acceptance evidence or resolve remaining obligation."
            ));
        }
        if (purchaseOrder != null && purchaseOrder.getRequestType() == RequestType.GOODS) {
            if (isFixedAssetPurchaseOrder(procedure, purchaseOrder)) {
                fixedAssetChecks(procedure, purchaseOrder, blockers);
            } else {
                goodsChecks(procedure, purchaseOrder, blockers);
            }
        }
        if (purchaseOrder != null && purchaseOrder.getRequestType() == RequestType.SERVICE) {
            serviceChecks(procedure, purchaseOrder, blockers);
        }

        if (purchaseOrder != null) {
            invoiceAndPaymentChecks(procedure, purchaseOrder, blockers);
            budgetChecks(procedure, purchaseOrder, blockers);
        }

        List<ProcurementCloseoutDtos.Dimension> dimensions = new ArrayList<>();
        for (ProcurementCloseoutDimension dimension : ProcurementCloseoutDimension.values()) {
            List<ProcurementCloseoutDtos.Blocker> dimensionBlockers = blockers.get(dimension);
            List<ProcurementCloseoutDtos.Blocker> dimensionWarnings = warnings.get(dimension);
            ProcurementCloseoutReadinessStatus status = dimensionBlockers.isEmpty()
                ? dimensionWarnings.isEmpty() ? ProcurementCloseoutReadinessStatus.COMPLETE : ProcurementCloseoutReadinessStatus.WARNING
                : ProcurementCloseoutReadinessStatus.BLOCKED;
            if (notApplicable(dimension, purchaseOrder)) {
                status = ProcurementCloseoutReadinessStatus.NOT_APPLICABLE;
            }
            dimensions.add(new ProcurementCloseoutDtos.Dimension(dimension, status, BigDecimal.ZERO, BigDecimal.ZERO,
                purchaseOrder == null ? firstNonBlank(procedure.getFinalizedCurrencyCode(), "UZS") : purchaseOrder.getCurrencyCode(),
                List.copyOf(dimensionBlockers), List.copyOf(dimensionWarnings), recommendedActions(dimensionBlockers)));
        }
        List<ProcurementCloseoutDtos.Blocker> allBlockers = dimensions.stream().flatMap(d -> d.blockers().stream()).toList();
        boolean ready = allBlockers.isEmpty();
        boolean sourcingCompleted = blockers.get(ProcurementCloseoutDimension.SOURCING).isEmpty();
        boolean commercialCompleted = blockers.get(ProcurementCloseoutDimension.COMMERCIAL_OUTPUTS).isEmpty();
        boolean fulfillmentCompleted = blockers.get(ProcurementCloseoutDimension.GOODS_FULFILLMENT).isEmpty()
            && blockers.get(ProcurementCloseoutDimension.SERVICE_FULFILLMENT).isEmpty();

        boolean financiallySettled = blockers.get(ProcurementCloseoutDimension.INVOICING).isEmpty()
            && blockers.get(ProcurementCloseoutDimension.PAYMENT).isEmpty();
        boolean budgetSettled = blockers.get(ProcurementCloseoutDimension.BUDGET_AND_COMMITMENT).isEmpty();
        return new ProcurementCloseoutDtos.Readiness(
            procedure.getId(),
            procedure.getPurchaseRequestId(),
            LocalDateTime.now(),
            ready,
            !sourcingCompleted || !commercialCompleted ? false : closeout != null && closeout.getCloseoutType() != ProcurementCloseoutType.NORMAL,
            sourcingCompleted,
            commercialCompleted,
            fulfillmentCompleted,
            blockers.get(ProcurementCloseoutDimension.QUALITY).isEmpty() && blockers.get(ProcurementCloseoutDimension.RETURNS_AND_CLAIMS).isEmpty(),
            financiallySettled,
            budgetSettled,
            blockers.get(ProcurementCloseoutDimension.DOCUMENTATION).isEmpty(),
            closeout == null ? ProcurementFinancialClosePolicy.PAYMENT_REQUIRED : closeout.getFinancialClosePolicy(),
            closeout == null ? null : closeout.getCloseoutStatus(),
            allBlockers,
            dimensions.stream().flatMap(d -> d.warnings().stream()).toList(),
            List.copyOf(dimensions),
            availableActions(closeout, ready, !allBlockers.isEmpty()),
            dimensions.stream().flatMap(d -> d.blockers().stream()).map(b -> new ProcurementCloseoutDtos.Check(b.blockerCode(), false, b.message())).toList()
        );
    }

    private void goodsChecks(ProcurementProcedure procedure, PurchaseOrder purchaseOrder, Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers) {
        List<PurchaseOrderItem> items = purchaseOrderItems(procedure, purchaseOrder);
        for (PurchaseOrderItem item : items) {
            BigDecimal received = nvl(item.getReceivedQuantity());
            BigDecimal rejected = nvl(item.getRejectedQuantity());
            BigDecimal damaged = nvl(item.getDamagedQuantity());
            BigDecimal remainingAcceptedFulfillment = FulfillmentQuantities.acceptedFulfillmentRemaining(item);
            if (remainingAcceptedFulfillment.signum() > 0) {
                blockers.get(ProcurementCloseoutDimension.GOODS_FULFILLMENT).add(blocker(
                    ProcurementCloseoutDimension.GOODS_FULFILLMENT,
                    ProcurementCloseoutBlockerCode.RECEIPT_PENDING,
                    "PURCHASE_ORDER_ITEM",
                    item.getId(),
                    "Purchase order line still has unaccepted fulfillment quantity " + remainingAcceptedFulfillment + ".",
                    "Accept, reject, damage-record, or cancel the remaining line quantity."
                ));
            }
            if (received.signum() > 0 && !isTerminalLineQualityStatus(item.getQualityStatus())) {
                blockers.get(ProcurementCloseoutDimension.QUALITY).add(blocker(
                    ProcurementCloseoutDimension.QUALITY,
                    ProcurementCloseoutBlockerCode.QUALITY_INSPECTION_PENDING,
                    "PURCHASE_ORDER_ITEM",
                    item.getId(),
                    "Received goods line does not have terminal quality evidence.",
                    "Complete quality inspection and release/reject the received quantity."
                ));
            }
            if (rejected.add(damaged).signum() > 0 && !hasClosedQualityClaim(procedure, purchaseOrder)) {
                blockers.get(ProcurementCloseoutDimension.RETURNS_AND_CLAIMS).add(blocker(
                    ProcurementCloseoutDimension.RETURNS_AND_CLAIMS,
                    ProcurementCloseoutBlockerCode.QUALITY_REJECTION_UNRESOLVED,
                    "PURCHASE_ORDER_ITEM",
                    item.getId(),
                    "Rejected or damaged goods quantity has no closed claim/disposition evidence.",
                    "Close the supplier claim, return, replacement, or write-off disposition before closeout."
                ));
            }
        }
        for (QualityInspection inspection : safeList(qualityInspectionRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
            procedure.getTenantId(), procedure.getCompanyId(), purchaseOrder.getId()))) {
            if (!isTerminalInspectionStatus(inspection.getStatus())) {
                blockers.get(ProcurementCloseoutDimension.QUALITY).add(blocker(
                    ProcurementCloseoutDimension.QUALITY,
                    ProcurementCloseoutBlockerCode.QUALITY_INSPECTION_PENDING,
                    "QUALITY_INSPECTION",
                    inspection.getId(),
                    "Quality inspection is not terminal.",
                    "Complete, release/reject, or cancel the quality inspection before closeout."
                ));
            }
        }
        for (QualityClaim claim : safeList(qualityClaimRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            procedure.getTenantId(), procedure.getCompanyId(), purchaseOrder.getId()))) {
            if (!isTerminalQualityClaimStatus(claim.getStatus())) {
                blockers.get(ProcurementCloseoutDimension.RETURNS_AND_CLAIMS).add(blocker(
                    ProcurementCloseoutDimension.RETURNS_AND_CLAIMS,
                    ProcurementCloseoutBlockerCode.OPEN_CLAIM,
                    "QUALITY_CLAIM",
                    claim.getId(),
                    "Supplier quality claim is still open.",
                    "Resolve, close, cancel, or reject the supplier quality claim before closeout."
                ));
            }
        }
    }

    private void fixedAssetChecks(
        ProcurementProcedure procedure,
        PurchaseOrder purchaseOrder,
        Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers
    ) {
        List<PurchaseOrderItem> fixedAssetItems = fixedAssetPurchaseOrderItems(procedure, purchaseOrder);
        Map<UUID, PurchaseOrderItem> itemsById = fixedAssetItems.stream()
            .collect(java.util.stream.Collectors.toMap(PurchaseOrderItem::getId, item -> item, (first, second) -> first));
        Map<UUID, Long> acceptedPostedUnitsByItemId = new java.util.HashMap<>();
        boolean hasHeldUnits = false;
        boolean hasRejectedUnits = false;
        for (AssetAcceptance acceptance : safeList(assetAcceptanceRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
                procedure.getTenantId(),
                procedure.getCompanyId(),
                purchaseOrder.getId(),
                AssetAcceptanceApprovalStatus.APPROVED,
                AssetAcceptancePostingStatus.POSTED
            ))) {
            for (AssetAcceptanceLine line : safeList(assetAcceptanceLineRepository
                .findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
                    procedure.getTenantId(), procedure.getCompanyId(), acceptance.getId()))) {
                if (line.getMaterialTypeSnapshot() != MaterialType.FIXED_ASSET
                    || !itemsById.containsKey(line.getPurchaseOrderItemId())) {
                    continue;
                }
                List<AssetAcceptanceUnit> units = safeList(assetAcceptanceUnitRepository
                    .findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
                        procedure.getTenantId(), procedure.getCompanyId(), line.getId()));
                long acceptedPostedUnits = units.stream()
                    .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED)
                    .filter(unit -> unit.getPostedFixedAssetId() != null)
                    .count();
                acceptedPostedUnitsByItemId.merge(line.getPurchaseOrderItemId(), acceptedPostedUnits, Long::sum);
                hasHeldUnits = hasHeldUnits
                    || nvl(line.getHoldQuantity()).signum() > 0
                    || units.stream().anyMatch(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.HOLD);
                hasRejectedUnits = hasRejectedUnits
                    || nvl(line.getRejectedQuantity()).signum() > 0
                    || units.stream().anyMatch(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.REJECTED);
            }
        }
        for (PurchaseOrderItem item : fixedAssetItems) {
            BigDecimal requiredQuantity = nvl(item.getQuantity());
            BigDecimal acceptedPostedQuantity = BigDecimal.valueOf(acceptedPostedUnitsByItemId.getOrDefault(item.getId(), 0L));
            if (acceptedPostedQuantity.compareTo(requiredQuantity) < 0) {
                blockers.get(ProcurementCloseoutDimension.GOODS_FULFILLMENT).add(blocker(
                    ProcurementCloseoutDimension.GOODS_FULFILLMENT,
                    ProcurementCloseoutBlockerCode.RECEIPT_PENDING,
                    "ASSET_ACCEPTANCE",
                    item.getId(),
                    "Fixed asset purchase order line is not fully fulfilled by posted asset acceptance evidence.",
                    "Post accepted asset acceptance units or resolve/cancel the remaining supplier obligation."
                ));
            }
        }
        if (hasHeldUnits) {
            blockers.get(ProcurementCloseoutDimension.QUALITY).add(blocker(
                ProcurementCloseoutDimension.QUALITY,
                ProcurementCloseoutBlockerCode.QUALITY_INSPECTION_PENDING,
                "ASSET_ACCEPTANCE",
                purchaseOrder.getId(),
                "Fixed asset acceptance has held units without terminal quality decision.",
                "Accept, reject, or resolve held asset units before procurement closeout."
            ));
        }
        if (hasRejectedUnits && !hasClosedQualityClaim(procedure, purchaseOrder)) {
            blockers.get(ProcurementCloseoutDimension.RETURNS_AND_CLAIMS).add(blocker(
                ProcurementCloseoutDimension.RETURNS_AND_CLAIMS,
                ProcurementCloseoutBlockerCode.QUALITY_REJECTION_UNRESOLVED,
                "ASSET_ACCEPTANCE",
                purchaseOrder.getId(),
                "Rejected fixed asset units have no closed supplier disposition evidence.",
                "Close the supplier return, replacement, claim, or write-off disposition before closeout."
            ));
        }
    }

    private void serviceChecks(ProcurementProcedure procedure, PurchaseOrder purchaseOrder, Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers) {
        List<ServiceAcceptanceAct> acts = serviceAcceptanceActRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(procedure.getTenantId(), procedure.getCompanyId(), purchaseOrder.getId());
        boolean approved = acts.stream().anyMatch(act -> List.of(ServiceAcceptanceActStatus.APPROVED.name(), ServiceAcceptanceActStatus.PAID.name()).contains(act.getStatus()));
        if (!approved) {
            blockers.get(ProcurementCloseoutDimension.SERVICE_FULFILLMENT).add(blocker(
                ProcurementCloseoutDimension.SERVICE_FULFILLMENT,
                ProcurementCloseoutBlockerCode.SERVICE_ACT_PENDING,
                "SERVICE_ACCEPTANCE_ACT",
                acts.isEmpty() ? purchaseOrder.getId() : acts.get(0).getId(),
                "Approved service acceptance act is missing.",
                "Approve a service acceptance act or cancel the remaining service scope."
            ));
        }
        serviceClaimChecks(procedure, purchaseOrder, blockers, ProcurementCloseoutDimension.SERVICE_FULFILLMENT);
    }

    private void serviceClaimChecks(
        ProcurementProcedure procedure,
        PurchaseOrder purchaseOrder,
        Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers,
        ProcurementCloseoutDimension fulfillmentDimension
    ) {
        for (PurchaseOrderItem item : purchaseOrderItems(procedure, purchaseOrder)) {
            for (ServiceClaim claim : safeList(serviceClaimRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
                procedure.getTenantId(), procedure.getCompanyId(), item.getId()))) {
                if (!isTerminalServiceClaimStatus(claim.getStatus())) {
                    blockers.get(ProcurementCloseoutDimension.RETURNS_AND_CLAIMS).add(blocker(
                        ProcurementCloseoutDimension.RETURNS_AND_CLAIMS,
                        ProcurementCloseoutBlockerCode.OPEN_CLAIM,
                        "SERVICE_CLAIM",
                        claim.getId(),
                        "Service/work claim remains unresolved for a procurement line.",
                        "Resolve or close the service/work claim before closeout."
                    ));
                    blockers.get(fulfillmentDimension).add(blocker(
                        fulfillmentDimension,
                        ProcurementCloseoutBlockerCode.SERVICE_EXECUTION_INCOMPLETE,
                        "PURCHASE_ORDER_ITEM",
                        item.getId(),
                        "Line has an unresolved service/work claim.",
                        "Resolve the linked claim and recheck closeout readiness."
                    ));
                }
            }
        }
    }


    private void invoiceAndPaymentChecks(ProcurementProcedure procedure, PurchaseOrder purchaseOrder, Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers) {
        List<ContractInvoice> invoices = invoices(procedure, purchaseOrder);
        if (nvl(purchaseOrder.getTotalAmount()).signum() > 0 && invoices.isEmpty()) {
            blockers.get(ProcurementCloseoutDimension.INVOICING).add(blocker(
                ProcurementCloseoutDimension.INVOICING,
                ProcurementCloseoutBlockerCode.UNINVOICED_ACCEPTED_EVIDENCE,
                "PURCHASE_ORDER",
                purchaseOrder.getId(),
                "No invoice evidence is linked to accepted procurement evidence.",
                "Create/issue an invoice, transfer liability to AP, or record an approved no-invoice disposition."
            ));
        }
        for (ContractInvoice invoice : invoices) {
            if (!List.of(ContractInvoiceStatus.ISSUED.name(), ContractInvoiceStatus.PAID.name(), ContractInvoiceStatus.CANCELLED.name()).contains(invoice.getStatus())) {
                blockers.get(ProcurementCloseoutDimension.INVOICING).add(blocker(ProcurementCloseoutDimension.INVOICING,
                    ProcurementCloseoutBlockerCode.DRAFT_INVOICE, "CONTRACT_INVOICE", invoice.getId(),
                    "Draft or unissued invoice exists.", "Issue, cancel, or correct the invoice."));
            }
            BigDecimal invoiceAmount = nvl(invoice.getAmount());
            BigDecimal settledAmount = contractPaymentRepository
                .findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(procedure.getTenantId(), procedure.getCompanyId(), invoice.getId())
                .stream()
                .filter(payment -> ContractPaymentStatus.PAID.name().equals(payment.getStatus()))
                .map(ContractPayment::getAmount)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (settledAmount.compareTo(invoiceAmount) < 0 && !ContractInvoiceStatus.CANCELLED.name().equals(invoice.getStatus())) {
                blockers.get(ProcurementCloseoutDimension.PAYMENT).add(blocker(ProcurementCloseoutDimension.PAYMENT,
                    ProcurementCloseoutBlockerCode.UNPAID_INVOICE, "CONTRACT_INVOICE", invoice.getId(),
                    "Invoice is not fully paid under PAYMENT_REQUIRED policy.", "Post payment, cancel/reverse invoice, or configure approved AP/liability handoff policy."));
            }
        }
    }

    private void budgetChecks(ProcurementProcedure procedure, PurchaseOrder purchaseOrder, Map<ProcurementCloseoutDimension, List<ProcurementCloseoutDtos.Blocker>> blockers) {
        BigDecimal openEncumbrance = budgetTransactionRepository.sumOpenEncumbranceBySourceId(procedure.getTenantId(), procedure.getCompanyId(), purchaseOrder.getId());
        if (nvl(openEncumbrance).signum() > 0) {
            blockers.get(ProcurementCloseoutDimension.BUDGET_AND_COMMITMENT).add(blocker(ProcurementCloseoutDimension.BUDGET_AND_COMMITMENT,
                ProcurementCloseoutBlockerCode.PO_COMMITMENT_REMAINS, "PURCHASE_ORDER", purchaseOrder.getId(),
                "Open budget reservation or PO commitment remains.", "Release unused PR reservation and reverse unused PO commitment using deterministic ledger keys."));
        }
        List<UUID> purchaseOrderItemIds = purchaseOrderItems(procedure, purchaseOrder).stream()
            .map(PurchaseOrderItem::getId)
            .filter(Objects::nonNull)
            .toList();
        if (purchaseOrderItemIds.isEmpty()) {
            return;
        }
        List<PurchaseOrderSourceAllocation> allocations = safeList(purchaseOrderSourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                procedure.getTenantId(), procedure.getCompanyId(), purchaseOrderItemIds));
        for (UUID purchaseRequestItemId : allocations.stream().map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId).filter(Objects::nonNull).distinct().toList()) {
            BigDecimal openReservation = budgetTransactionRepository.sumOpenEncumbranceBySourceKey(
                procedure.getTenantId(), procedure.getCompanyId(), "PurchaseRequestItem:" + purchaseRequestItemId);
            if (nvl(openReservation).signum() > 0) {
                blockers.get(ProcurementCloseoutDimension.BUDGET_AND_COMMITMENT).add(blocker(ProcurementCloseoutDimension.BUDGET_AND_COMMITMENT,
                    ProcurementCloseoutBlockerCode.PO_COMMITMENT_REMAINS, "PURCHASE_REQUEST_ITEM", purchaseRequestItemId,
                    "Open source purchase-request reservation remains.", "Release the unused PR reservation using the deterministic closeout release key."));
            }
        }
    }

    private FinancialSettlement settleFinancialBalances(ProcurementCloseout closeout, ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null) {
            return new FinancialSettlement(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
        List<PurchaseOrderItem> items = purchaseOrderItems(procedure, purchaseOrder);
        List<UUID> itemIds = items.stream().map(PurchaseOrderItem::getId).filter(Objects::nonNull).toList();
        List<PurchaseOrderSourceAllocation> allocations = itemIds.isEmpty() ? List.of() : safeList(
            purchaseOrderSourceAllocationRepository.findByPurchaseOrderItemIdsForUpdate(
                procedure.getTenantId(), procedure.getCompanyId(), itemIds));
        Set<UUID> sourceItemIds = allocations.stream()
            .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, PurchaseRequestItem> sourceItemsById = sourceItemIds.isEmpty() ? Map.of()
            : purchaseRequestItemRepository.findAllByIdInForUpdate(sourceItemIds).stream()
                .filter(source -> !source.isDeleted())
                .filter(source -> procedure.getTenantId().equals(source.getTenantId()))
                .filter(source -> Objects.equals(procedure.getCompanyId(), source.getCompanyId()))
                .collect(java.util.stream.Collectors.toMap(PurchaseRequestItem::getId, source -> source, (left, right) -> left));

        BigDecimal released = BigDecimal.ZERO;
        int releaseCount = 0;
        for (PurchaseOrderSourceAllocation allocation : allocations) {
            PurchaseRequestItem source = sourceItemsById.get(allocation.getPurchaseRequestItemId());
            if (source == null || source.getBudgetItemId() == null) {
                continue;
            }
            BigDecimal requestedRelease = nvl(allocation.getOrderedQuantity()).multiply(nvl(source.getEstimatedPrice()));
            BigDecimal openReservation = nvl(budgetTransactionRepository.sumOpenEncumbranceBySourceKey(
                procedure.getTenantId(), procedure.getCompanyId(), "PurchaseRequestItem:" + source.getId()));
            BigDecimal releaseAmount = requestedRelease.min(openReservation).max(BigDecimal.ZERO);
            if (releaseAmount.signum() == 0) {
                continue;
            }
            budgetItemService.releaseIdempotent(
                source.getBudgetItemId(),
                releaseAmount,
                "ProcurementCloseout " + closeout.getCode(),
                "PurchaseRequest",
                source.getPurchaseRequestId(),
                source.getId(),
                "PurchaseRequestItem:" + source.getId(),
                releasePrActionKey(closeout, source.getPurchaseRequestId())
            );
            released = released.add(releaseAmount);
            releaseCount++;
        }

        BigDecimal reversed = BigDecimal.ZERO;
        int reversalCount = 0;
        for (PurchaseOrderItem item : items) {
            if (item.getBudgetItemId() == null || item.getId() == null) {
                continue;
            }
            BigDecimal openCommitment = nvl(budgetTransactionRepository.sumOpenEncumbranceBySourceKey(
                procedure.getTenantId(), procedure.getCompanyId(), "PurchaseOrderItem:" + item.getId()));
            BigDecimal requestedReversal = openLineAmount(item);
            BigDecimal reversalAmount = (requestedReversal.signum() > 0 ? requestedReversal.min(openCommitment) : openCommitment).max(BigDecimal.ZERO);
            if (reversalAmount.signum() == 0) {
                continue;
            }
            budgetItemService.reverseCommitmentIdempotent(
                item.getBudgetItemId(),
                reversalAmount,
                "ProcurementCloseout " + closeout.getCode(),
                "PurchaseOrder",
                purchaseOrder.getId(),
                item.getId(),
                "PurchaseOrderItem:" + item.getId(),
                reversePoActionKey(closeout, purchaseOrder.getId())
            );
            reversed = reversed.add(reversalAmount);
            reversalCount++;
        }
        return new FinancialSettlement(released, reversed, releaseCount, reversalCount);
    }

    private void reconcileInScopeBudgetLedgers(ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null) {
            return;
        }
        Set<UUID> budgetItemIds = purchaseOrderItems(procedure, purchaseOrder).stream()
            .map(PurchaseOrderItem::getBudgetItemId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<UUID> itemIds = purchaseOrderItems(procedure, purchaseOrder).stream()
            .map(PurchaseOrderItem::getId)
            .filter(Objects::nonNull)
            .toList();
        if (!itemIds.isEmpty()) {
            safeList(purchaseOrderSourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                    procedure.getTenantId(), procedure.getCompanyId(), itemIds)).stream()
                .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(sourceItemId -> purchaseRequestItemRepository.findByIdAndIsDeletedFalse(sourceItemId)
                    .filter(source -> procedure.getTenantId().equals(source.getTenantId()))
                    .filter(source -> Objects.equals(procedure.getCompanyId(), source.getCompanyId()))
                    .map(PurchaseRequestItem::getBudgetItemId)
                    .filter(Objects::nonNull)
                    .ifPresent(budgetItemIds::add));
        }
        for (UUID budgetItemId : budgetItemIds) {
            BudgetLedgerReconciliationResult reconciliation = budgetItemService.reconcileLedger(budgetItemId);
            if (reconciliation != null && !reconciliation.matched()) {
                throw new IllegalStateException("In-scope budget ledger discrepancy prevents procurement closeout finalization");
            }
        }
    }

    private ProcurementCloseoutDtos.Blocker blocker(ProcurementCloseoutDimension dimension, ProcurementCloseoutBlockerCode code, String entityType,
                                                   UUID entityId, String message, String action) {
        return new ProcurementCloseoutDtos.Blocker(UUID.randomUUID(), dimension, code.name(), "BLOCKER", entityType, entityId, null,
            null, null, null, message, action, route(entityType, entityId), false, null);
    }

    private ProcurementCloseoutDtos.RouteHint route(String entityType, UUID entityId) {
        String moduleKey = switch (entityType) {
            case "PROCUREMENT_PROCEDURE" -> "procurement-procedures";
            case "PURCHASE_ORDER" -> "purchase-orders";
            case "CONTRACT_INVOICE" -> "contract-invoices";
            case "SERVICE_ACCEPTANCE_ACT" -> "service-acceptance-acts";
            case "WORK_COMPLETION_ACT" -> "work-completion-acts";
            default -> null;
        };
        return new ProcurementCloseoutDtos.RouteHint(moduleKey, entityId, moduleKey == null || entityId == null ? null : "/app/" + moduleKey + "/" + entityId);
    }

    private List<ProcurementCloseoutDtos.Action> availableActions(ProcurementCloseout closeout, boolean ready, boolean blocked) {
        if (closeout == null) {
            return List.of(action("CREATE_CLOSEOUT", "Create closeout", true), action("VIEW_HISTORICAL_RECONCILIATION", "Historical reconciliation", true));
        }
        List<ProcurementCloseoutDtos.Action> actions = new ArrayList<>();
        actions.add(action("RECHECK_READINESS", "Recheck readiness", true));
        actions.add(action("VIEW_HISTORICAL_RECONCILIATION", "Historical reconciliation", true));
        actions.add(action("CANCEL_PO_REMAINDER", "Cancel PO remainder", blocked));
        actions.add(action("RESOLVE_REMAINING_OBLIGATION", "Resolve remaining obligation", blocked));
        if (List.of(
            ProcurementCloseoutStatus.DRAFT,
            ProcurementCloseoutStatus.BLOCKED,
            ProcurementCloseoutStatus.READY,
            ProcurementCloseoutStatus.RETURNED,
            ProcurementCloseoutStatus.REJECTED
        ).contains(closeout.getCloseoutStatus())) {
            actions.add(action("SUBMIT_CLOSEOUT", "Submit closeout", true));
        }
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.APPROVED) {
            actions.add(action("FINALIZE_CLOSEOUT", "Finalize closeout", ready || closeout.getCloseoutType() != ProcurementCloseoutType.NORMAL));
        }
        if (closeout.getApprovalInstanceId() != null
            && (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.APPROVAL_PENDING
                || closeout.getCloseoutStatus() == ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING)) {
            actions.add(action("VIEW_APPROVAL_TASK", "View approval task", true));
        }
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.CLOSED) {
            actions.add(action("REQUEST_REOPEN", "Request reopen", true));
        }
        actions.add(action("INITIATE_EARLY_CLOSE", "Initiate early close", blocked));
        return List.copyOf(actions);
    }

    private ProcurementCloseoutDtos.Action action(String key, String label, boolean enabled) {
        return new ProcurementCloseoutDtos.Action(key, label, enabled, enabled ? null : "ACTION_BLOCKED", enabled ? null : "Action is not currently available",
            null, null, key.contains("EARLY") || key.contains("REOPEN") || key.contains("CANCEL"), key.contains("EARLY") || key.contains("REOPEN"));
    }

    private void applyReadiness(ProcurementCloseout closeout, ProcurementCloseoutDtos.Readiness readiness) {
        closeout.setReadinessStatus(readiness.readyForNormalClose() ? ProcurementCloseoutReadinessStatus.READY : ProcurementCloseoutReadinessStatus.BLOCKED);
        closeout.setReadinessCheckedAt(LocalDateTime.now());
        closeout.setReadinessCheckedBy(actor());
        closeout.setBlockingReasonCount(readiness.blockers().size());
        closeout.setReadinessSnapshot("calculatedAt=" + readiness.calculatedAt() + ";blockers=" + readiness.blockers().size());
    }

    private ProcurementCloseoutDtos.Response response(ProcurementCloseout closeout, ProcurementCloseoutDtos.Readiness readiness) {
        return new ProcurementCloseoutDtos.Response(closeout.getId(), closeout.getCompanyId(), closeout.getProcurementProcedureId(), closeout.getPurchaseRequestId(),
            closeout.getCloseoutStatus(), closeout.getCloseoutType(), closeout.getFinancialClosePolicy(), closeout.getApprovalInstanceId(),
            pendingApprovalTaskId(closeout.getApprovalInstanceId()),
            closeout.getCloseReason(), closeout.getEarlyCloseReason(), closeout.getCancellationReason(), closeout.getReopenReason(),
            closeout.getLiabilityDisposition(), nvl(closeout.getExpectedLiabilityAmount()), nvl(closeout.getUnresolvedLiabilityAmount()),
            readiness, closeout.getCreatedAt(), closeout.getSubmittedAt(), closeout.getApprovedAt(), closeout.getClosedAt(), closeout.getReopenedAt(),
            closeout.getVersion(), readiness.availableActions());
    }

    private UUID pendingApprovalTaskId(UUID approvalInstanceId) {
        if (approvalInstanceId == null) {
            return null;
        }
        return approvalTaskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), approvalInstanceId, ApprovalTaskStatus.PENDING
        ).map(task -> task.getId()).orElse(null);
    }

    private ProcurementProcedure procedure(UUID procedureId) {
        return procedureRepository.findByIdAndIsDeletedFalse(procedureId)
            .filter(this::isCurrentCompany)
            .orElseThrow(() -> new NotFoundException("Procurement procedure not found: " + procedureId));
    }

    private ProcurementCloseout closeout(UUID closeoutId) {
        return closeoutRepository.findById(closeoutId)
            .filter(closeout -> !closeout.isDeleted())
            .filter(this::isCurrentCompany)
            .orElseThrow(() -> new NotFoundException("Procurement closeout not found: " + closeoutId));
    }

    private ProcurementCloseout closeoutForUpdate(UUID closeoutId) {
        return closeoutRepository.findByIdForUpdate(closeoutId)
            .filter(this::isCurrentCompany)
            .orElseThrow(() -> new NotFoundException("Procurement closeout not found: " + closeoutId));
    }

    private ProcurementCloseout activeCloseout(UUID procedureId) {
        return closeoutRepository.findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId)
            .orElse(null);
    }

    private ProcurementAwardOutcome award(ProcurementProcedure procedure) {
        return awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            procedure.getTenantId(), procedure.getCompanyId(), procedure.getId()).orElse(null);
    }

    private PurchaseOrder purchaseOrder(ProcurementProcedure procedure, ProcurementAwardOutcome award) {
        UUID purchaseOrderId = procedure.getPurchaseOrderId() != null ? procedure.getPurchaseOrderId() : award == null ? null : award.getPurchaseOrderId();
        return purchaseOrderId == null ? null : purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId).filter(this::isCurrentCompany).orElse(null);
    }

    private PurchaseOrder purchaseOrderForUpdate(ProcurementProcedure procedure, ProcurementAwardOutcome award) {
        UUID purchaseOrderId = procedure.getPurchaseOrderId() != null ? procedure.getPurchaseOrderId() : award == null ? null : award.getPurchaseOrderId();
        return purchaseOrderId == null ? null : purchaseOrderRepository.findByIdForUpdate(purchaseOrderId).filter(this::isCurrentCompany).orElse(null);
    }

    private List<PurchaseOrderItem> purchaseOrderItems(ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null) {
            return List.of();
        }
        return safeList(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            procedure.getTenantId(), purchaseOrder.getId()));
    }

    private boolean isFixedAssetPurchaseOrder(ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        return !fixedAssetPurchaseOrderItems(procedure, purchaseOrder).isEmpty();
    }

    private List<PurchaseOrderItem> fixedAssetPurchaseOrderItems(ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        return purchaseOrderItems(procedure, purchaseOrder).stream()
            .filter(item -> item.getLineType() == ProcurementLineType.MATERIAL)
            .filter(item -> item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET)
            .toList();
    }

    private List<ContractInvoice> invoices(ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        return contractInvoiceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            procedure.getTenantId(), procedure.getCompanyId(), purchaseOrder.getId());
    }

    private BigDecimal acceptedAmount(PurchaseOrder purchaseOrder) {
        return purchaseOrder == null || !COMPLETE_PO_STATUSES.contains(purchaseOrder.getStatus()) ? BigDecimal.ZERO : nvl(purchaseOrder.getTotalAmount());
    }

    private BigDecimal lineAmount(PurchaseOrderItem item) {
        return nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice()));
    }

    private BigDecimal openLineAmount(PurchaseOrderItem item) {
        BigDecimal openQuantity = nvl(item.getQuantity()).subtract(nvl(item.getReceivedQuantity())).max(BigDecimal.ZERO);
        return openQuantity.multiply(nvl(item.getUnitPrice()));
    }

    private ProcurementCloseoutDimension fulfillmentDimension(PurchaseOrder purchaseOrder) {
        if (purchaseOrder.getRequestType() == RequestType.SERVICE) return ProcurementCloseoutDimension.SERVICE_FULFILLMENT;
        return ProcurementCloseoutDimension.GOODS_FULFILLMENT;
    }

    private boolean notApplicable(ProcurementCloseoutDimension dimension, PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null) {
            return List.of(ProcurementCloseoutDimension.GOODS_FULFILLMENT, ProcurementCloseoutDimension.SERVICE_FULFILLMENT).contains(dimension);
        }
        return switch (dimension) {
            case GOODS_FULFILLMENT -> purchaseOrder.getRequestType() != RequestType.GOODS;
            case SERVICE_FULFILLMENT -> purchaseOrder.getRequestType() != RequestType.SERVICE;
            default -> false;
        };
    }


    private List<ProcurementCloseoutDtos.Action> recommendedActions(List<ProcurementCloseoutDtos.Blocker> blockers) {
        if (blockers.isEmpty()) return List.of();
        return blockers.stream().map(blocker -> action(switch (blocker.blockerCode()) {
            case "PO_COMMITMENT_REMAINS", "RECEIPT_PENDING" -> "CANCEL_PO_REMAINDER";
            case "UNPAID_INVOICE", "UNINVOICED_ACCEPTED_EVIDENCE" -> "RESOLVE_REMAINING_OBLIGATION";
            default -> "RECHECK_READINESS";
        }, blocker.recommendedAction(), true)).toList();
    }

    private boolean isTerminalLineQualityStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return List.of(
            QualityInspectionStatus.RELEASED.name(),
            QualityInspectionStatus.REJECTED.name(),
            QualityInspectionStatus.CANCELLED.name(),
            QualityInspectionStatus.PASSED.name()
        ).contains(status);
    }

    private boolean isTerminalInspectionStatus(String status) {
        return isTerminalLineQualityStatus(status);
    }

    private boolean isTerminalQualityClaimStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return List.of(
            QualityClaimStatus.CLOSED.name(),
            QualityClaimStatus.CANCELLED.name(),
            QualityClaimStatus.REJECTED.name()
        ).contains(status);
    }

    private boolean hasClosedQualityClaim(ProcurementProcedure procedure, PurchaseOrder purchaseOrder) {
        return safeList(qualityClaimRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            procedure.getTenantId(), procedure.getCompanyId(), purchaseOrder.getId())).stream()
            .anyMatch(claim -> isTerminalQualityClaimStatus(claim.getStatus()));
    }

    private boolean isTerminalServiceClaimStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return List.of(
            ServiceClaimStatus.RESOLVED.name(),
            ServiceClaimStatus.REJECTED.name(),
            ServiceClaimStatus.CLOSED.name()
        ).contains(status);
    }

    private boolean hasNonBudgetBlockers(ProcurementCloseoutDtos.Readiness readiness) {
        return readiness.blockers().stream()
            .anyMatch(blocker -> blocker.dimension() != ProcurementCloseoutDimension.BUDGET_AND_COMMITMENT);
    }

    private String releasePrActionKey(ProcurementCloseout closeout, UUID purchaseRequestId) {
        return "CLOSEOUT:" + closeout.getId() + ":RELEASE_PR:" + purchaseRequestId + ":" + closeoutVersion(closeout);
    }

    private String reversePoActionKey(ProcurementCloseout closeout, UUID purchaseOrderId) {
        return "CLOSEOUT:" + closeout.getId() + ":REVERSE_PO:" + purchaseOrderId + ":" + closeoutVersion(closeout);
    }

    private String finalizeActionKey(ProcurementCloseout closeout) {
        return "CLOSEOUT:" + closeout.getId() + ":FINALIZE:" + closeoutVersion(closeout);
    }

    private long closeoutVersion(ProcurementCloseout closeout) {
        return closeout.getVersion() == null ? 0L : closeout.getVersion();
    }

    private boolean isCurrentCompany(ProcurementProcedure procedure) {
        return TenantContext.currentTenant().equals(procedure.getTenantId()) && CompanyContext.currentCompany().equals(procedure.getCompanyId());
    }

    private boolean isCurrentCompany(PurchaseOrder purchaseOrder) {
        return TenantContext.currentTenant().equals(purchaseOrder.getTenantId()) && CompanyContext.currentCompany().equals(purchaseOrder.getCompanyId());
    }

    private boolean isCurrentCompany(ProcurementCloseout closeout) {
        return TenantContext.currentTenant().equals(closeout.getTenantId()) && CompanyContext.currentCompany().equals(closeout.getCompanyId());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() || authentication.getName() == null ? "system" : authentication.getName();
    }

    private record FinancialSettlement(BigDecimal releasedReservations, BigDecimal reversedCommitments, int releaseCount, int reversalCount) {
        private String snapshot() {
            return "releasedReservations=" + releasedReservations
                + ",reversedCommitments=" + reversedCommitments
                + ",releaseCount=" + releaseCount
                + ",reversalCount=" + reversalCount;
        }
    }
}
