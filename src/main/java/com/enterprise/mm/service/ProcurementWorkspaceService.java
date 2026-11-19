package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ProcurementCloseoutDtos;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PayablesReadinessResponse;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.FlowDocument;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.FlowStep;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.ProcurementFlowResponse;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.QualitySummary;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementCloseoutRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementMethodType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementWorkspaceService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final AdvanceShippingNoticeRepository advanceShippingNoticeRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final ProcurementPayablesService payablesService;
    private final ProcurementCloseoutReadinessService closeoutReadinessService;
    private final ProcurementProcedureRepository procurementProcedureRepository;
    private final ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    private final ProcurementCloseoutRepository procurementCloseoutRepository;
    private final ServiceOrderRepository serviceOrderRepository;
    private final ServiceExecutionRepository serviceExecutionRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;

    @Transactional(readOnly = true)
    public ProcurementFlowResponse byPurchaseOrder(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = loadPurchaseOrder(purchaseOrderId);
        return response(purchaseOrder);
    }

    @Transactional(readOnly = true)
    public List<ProcurementFlowResponse> byDemandPlan(UUID demandPlanId) {
        DemandPlan demandPlan = demandPlanRepository.findByIdAndIsDeletedFalse(demandPlanId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Demand plan not found: " + demandPlanId));
        List<PurchaseRequest> requests = purchaseRequestRepository.findByTenantIdAndCompanyIdAndDemandPlanIdAndIsDeletedFalse(
            TenantContext.currentTenant(), demandPlan.getCompanyId(), demandPlan.getId());
        if (requests.isEmpty()) {
            return List.of(new ProcurementFlowResponse(
                null,
                "DEMAND_PLAN",
                "CREATE_PURCHASE_REQUEST",
                List.of(step("DEMAND_PLAN", "Demand plan", demandPlan.getStatus(), true, true, "CREATE_PURCHASE_REQUEST")),
                document("DEMAND_PLAN", demandPlan),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new QualitySummary(false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()),
                null,
                null,
                null,
                List.of(),
                List.of()
            ));
        }
        List<UUID> requestIds = requests.stream().map(PurchaseRequest::getId).toList();
        List<PurchaseOrder> purchaseOrders = purchaseOrderRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), demandPlan.getCompanyId(), requestIds);
        if (purchaseOrders.isEmpty()) {
            return requests.stream()
                .map(request -> requestOnlyResponse(demandPlan, request))
                .toList();
        }
        return purchaseOrders.stream().map(this::response).toList();
    }

    private ProcurementFlowResponse requestOnlyResponse(DemandPlan demandPlan, PurchaseRequest request) {
        List<FlowStep> steps = List.of(
            step("DEMAND_PLAN", "Demand plan", demandPlan.getStatus(), true, false, null),
            step("PURCHASE_REQUEST", "Purchase request", request.getStatus(), true, true, "CREATE_PURCHASE_ORDER"),
            step("PURCHASE_ORDER", "Purchase order", null, false, false, "CREATE_PURCHASE_ORDER"),
            step("DELIVERY_CONFIRMATION", "Delivery confirmed", null, false, false, "CONFIRM_DELIVERY"),
            step("GOODS_RECEIPT", "Goods receipt", null, false, false, "POST_GOODS_RECEIPT"),
            step("QUALITY", "Quality", "NOT_REQUIRED", true, false, null),
            step("INVOICE", "Invoice", null, false, false, "CREATE_INVOICE"),
            step("PAYMENT", "Payment", null, false, false, "CREATE_PAYMENT")
        );
        return new ProcurementFlowResponse(
            null,
            "PURCHASE_REQUEST",
            "CREATE_PURCHASE_ORDER",
            steps,
            document("DEMAND_PLAN", demandPlan),
            document("PURCHASE_REQUEST", request),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new QualitySummary(false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()),
            null,
            null,
            null,
            List.of(),
            List.of()
        );
    }

    private ProcurementFlowResponse response(PurchaseOrder purchaseOrder) {
        if (purchaseOrder.getRequestType() != null && purchaseOrder.getRequestType().isServiceStyle()) {
            return serviceResponse(purchaseOrder);
        }
        PurchaseRequest request = purchaseOrder.getPurchaseRequestId() == null ? null
            : purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseOrder.getPurchaseRequestId()).orElse(null);
        DemandPlan demandPlan = request == null || request.getDemandPlanId() == null ? null
            : demandPlanRepository.findByIdAndIsDeletedFalse(request.getDemandPlanId()).orElse(null);
        List<AdvanceShippingNotice> asns = advanceShippingNoticeRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId());
        List<GoodsReceipt> receipts = goodsReceiptRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId());
        List<QualityInspection> inspections = qualityInspectionRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId());
        List<ContractInvoice> invoices = contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getId());
        List<ContractPayment> payments = paymentsForOrder(purchaseOrder, invoices);
        PayablesReadinessResponse readiness = payablesService.purchaseOrderReadiness(purchaseOrder.getId());
        ProcurementProcedure procedure = procedureFor(purchaseOrder);
        ProcurementCloseout closeout = closeoutFor(procedure);
        ProcurementCloseoutDtos.Readiness closeoutReadiness = closeoutReadinessFor(procedure);
        QualitySummary quality = qualitySummary(inspections);
        String nextAction = nextAction(purchaseOrder, asns, receipts, quality, readiness, invoices, payments, closeoutReadiness);
        String currentStage = stageFor(nextAction, payments, invoices, readiness, quality, receipts, asns, purchaseOrder, request, demandPlan);
        return new ProcurementFlowResponse(
            purchaseOrder.getId(),
            currentStage,
            nextAction,
            steps(demandPlan, request, purchaseOrder, asns, receipts, quality, readiness, invoices, payments, currentStage, closeoutReadiness),
            document("DEMAND_PLAN", demandPlan),
            document("PURCHASE_REQUEST", request),
            document("PURCHASE_ORDER", purchaseOrder),
            asns.stream().map(item -> document("ASN", item)).toList(),
            receipts.stream().map(item -> document("GOODS_RECEIPT", item)).toList(),
            List.of(),
            List.of(),
            quality,
            readiness,
            closeoutReadiness,
            document("PROCUREMENT_CLOSEOUT", closeout),
            invoices.stream().map(item -> document("CONTRACT_INVOICE", item)).toList(),
            payments.stream().map(item -> document("CONTRACT_PAYMENT", item)).toList()
        );
    }

    private ProcurementFlowResponse serviceResponse(PurchaseOrder purchaseOrder) {
        PurchaseRequest request = purchaseOrder.getPurchaseRequestId() == null ? null
            : purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseOrder.getPurchaseRequestId()).orElse(null);
        DemandPlan demandPlan = request == null || request.getDemandPlanId() == null ? null
            : demandPlanRepository.findByIdAndIsDeletedFalse(request.getDemandPlanId()).orElse(null);
        List<ServiceOrder> orders = serviceOrderRepository.findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), purchaseOrder.getCompanyId()).stream()
            .filter(item -> purchaseOrder.getId().equals(item.getPurchaseOrderId()))
            .toList();
        List<ServiceExecution> executions = orders.stream()
            .flatMap(order -> serviceExecutionRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), order.getId()).stream())
            .toList();
        boolean works = false;

        List<ServiceAcceptanceAct> acts = orders.stream()
            .flatMap(order -> serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), order.getId()).stream())
            .toList();
        if (!works && acts.isEmpty()) {
            acts = serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId());
        }
        List<WorkCompletionAct> workActs = works
            ? workCompletionActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), purchaseOrder.getId())
            : List.of();
        List<ContractInvoice> invoices = contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrder.getId());
        List<ContractPayment> payments = paymentsForOrder(purchaseOrder, invoices);
        PayablesReadinessResponse readiness = payablesService.purchaseOrderReadiness(purchaseOrder.getId());
        ProcurementProcedure procedure = procedureFor(purchaseOrder);
        ProcurementCloseout closeout = closeoutFor(procedure);
        ProcurementCloseoutDtos.Readiness closeoutReadiness = closeoutReadinessFor(procedure);
        boolean hasDraftInvoice = hasDraftInvoice(invoices);
        boolean hasPaidPayment = payments.stream().anyMatch(item -> ContractPaymentStatus.PAID.name().equals(normalized(item.getStatus())));
        boolean hasIssuedInvoice = invoices.stream().anyMatch(item -> isIssuedInvoice(item.getStatus()));
        boolean hasServiceAct = !acts.isEmpty();
        boolean hasWorkAct = !workActs.isEmpty();
        boolean serviceExecutionStarted = works || hasServiceAct || !executions.isEmpty();
        boolean serviceExecutionComplete = works || hasServiceAct
            || executions.stream().anyMatch(item -> List.of("COMPLETED_BY_CONTRACTOR", "ACCEPTED", "ACCEPTED_WITH_REMARKS").contains(item.getStatus()));
        boolean serviceExecutionAccepted = works || hasServiceAct
            || executions.stream().anyMatch(item -> List.of("ACCEPTED", "ACCEPTED_WITH_REMARKS").contains(item.getStatus()));
        boolean completionAccepted = works
            ? workActs.stream().anyMatch(item -> List.of("APPROVED", "SENT_TO_PAYMENT", "PAID").contains(item.getStatus()))
            : acts.stream().anyMatch(item -> List.of("APPROVED", "SENT_TO_PAYMENT", "PAID").contains(item.getStatus()));
        boolean completionStarted = works ? hasWorkAct : hasServiceAct;
        String nextAction = hasPaidPayment ? closeoutNextAction(closeoutReadiness)
            : hasIssuedInvoice ? paymentAction(payments)
            : hasDraftInvoice && completionAccepted ? "ISSUE_INVOICE"
            : completionAccepted && readiness != null && readiness.remainingInvoiceableAmount() != null && readiness.remainingInvoiceableAmount().signum() > 0
                ? invoiceAction(hasDraftInvoice)
                : !works && !serviceExecutionStarted ? "CREATE_SERVICE_EXECUTION"
                    : !serviceExecutionAccepted ? "WAIT_FOR_SERVICE_ACCEPTANCE"
                        : !completionStarted ? acceptanceAction(works)
                            : !completionAccepted ? "WAIT_FOR_SERVICE_ACCEPTANCE"
                                : closeoutNextAction(closeoutReadiness);
        String currentStage = switch (nextAction) {
            case "CREATE_SERVICE_EXECUTION" -> "SERVICE_EXECUTION";
            case "WAIT_FOR_SERVICE_ACCEPTANCE" -> completionStarted ? "SERVICE_ACCEPTANCE" : "SERVICE_EXECUTION";
            case "CREATE_SERVICE_ACT", "CREATE_WORK_ACT" -> "SERVICE_ACCEPTANCE";
            case "CREATE_INVOICE", "ISSUE_INVOICE" -> "INVOICE";
            case "CREATE_PAYMENT" -> "PAYMENT";
            case "CREATE_CLOSEOUT", "SUBMIT_CLOSEOUT", "WAIT_FOR_CLOSEOUT_APPROVAL", "FINALIZE_CLOSEOUT", "PROCUREMENT_CLOSED" -> "CLOSEOUT";
            default -> "PURCHASE_ORDER";
        };
        List<FlowStep> steps = List.of(
            step("DEMAND_PLAN", "Demand plan", status(demandPlan), demandPlan != null, false, null),
            step("PURCHASE_REQUEST", "Purchase request", status(request), request != null, false, null),
            step("PURCHASE_ORDER", "Purchase order", status(purchaseOrder), true, "PURCHASE_ORDER".equals(currentStage), null),
            step("SERVICE_EXECUTION", "Service execution", serviceExecutionStatus(works, executions, acts), serviceExecutionComplete, "SERVICE_EXECUTION".equals(currentStage), !works && !serviceExecutionStarted ? "CREATE_SERVICE_EXECUTION" : null),
            step("SERVICE_ACCEPTANCE", works ? "Works acceptance" : "Service acceptance", works ? latestStatus(workActs) : acts.isEmpty() ? null : acts.get(0).getStatus(), completionAccepted, "SERVICE_ACCEPTANCE".equals(currentStage), completionStarted ? null : acceptanceAction(works)),
            step("INVOICE", "Invoice", latestStatus(invoices), invoices.stream().anyMatch(item -> isIssuedInvoice(item.getStatus())), "INVOICE".equals(currentStage), invoiceStepAction(invoices, readiness)),
            step("PAYMENT", "Payment", latestStatus(payments), hasPaidPayment, "PAYMENT".equals(currentStage), hasPaidPayment ? null : paymentAction(payments)),
            step("CLOSEOUT", "Closeout", closeoutReadiness == null || closeoutReadiness.closeoutStatus() == null ? null : closeoutReadiness.closeoutStatus().name(),
                closeoutReadiness != null && closeoutReadiness.closeoutStatus() == ProcurementCloseoutStatus.CLOSED,
                "CLOSEOUT".equals(currentStage), nextAction)
        );
        return new ProcurementFlowResponse(
            purchaseOrder.getId(), currentStage, nextAction, steps,
            document("DEMAND_PLAN", demandPlan), document("PURCHASE_REQUEST", request), document("PURCHASE_ORDER", purchaseOrder),
            List.of(), List.of(),
            serviceWorkCompletionDocuments(executions, workActs),
            acts.stream().map(item -> document("SERVICE_ACCEPTANCE_ACT", item)).toList(),
            new QualitySummary(false, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()), readiness,
            closeoutReadiness,
            document("PROCUREMENT_CLOSEOUT", closeout),
            invoices.stream().map(item -> document("CONTRACT_INVOICE", item)).toList(),
            payments.stream().map(item -> document("CONTRACT_PAYMENT", item)).toList()
        );
    }

    private List<FlowStep> steps(
        DemandPlan demandPlan,
        PurchaseRequest request,
        PurchaseOrder purchaseOrder,
        List<AdvanceShippingNotice> asns,
        List<GoodsReceipt> receipts,
        QualitySummary quality,
        PayablesReadinessResponse readiness,
        List<ContractInvoice> invoices,
        List<ContractPayment> payments,
        String currentStage,
        ProcurementCloseoutDtos.Readiness closeoutReadiness
    ) {
        List<FlowStep> steps = new ArrayList<>();
        steps.add(step("DEMAND_PLAN", "Demand plan", status(demandPlan), demandPlan != null, "DEMAND_PLAN".equals(currentStage), demandPlan == null ? "CREATE_DEMAND_PLAN" : null));
        steps.add(step("PURCHASE_REQUEST", "Purchase request", status(request), request != null, "PURCHASE_REQUEST".equals(currentStage), request == null ? "CREATE_PURCHASE_REQUEST" : null));
        steps.add(step("PURCHASE_ORDER", "Purchase order", status(purchaseOrder), purchaseOrder != null, "PURCHASE_ORDER".equals(currentStage), purchaseOrder == null ? "CREATE_PURCHASE_ORDER" : null));
        steps.add(step("DELIVERY_CONFIRMATION", "Delivery confirmed", latestStatus(asns), asns.stream().anyMatch(item -> "CONFIRMED".equals(normalized(item.getStatus())) || "RECEIVED".equals(normalized(item.getStatus()))), "DELIVERY_CONFIRMATION".equals(currentStage), "CONFIRM_DELIVERY"));
        steps.add(step("GOODS_RECEIPT", "Goods receipt", latestStatus(receipts), !receipts.isEmpty(), "GOODS_RECEIPT".equals(currentStage), "POST_GOODS_RECEIPT"));
        steps.add(step("QUALITY", "Quality", quality.released() ? "RELEASED" : quality.required() ? "PENDING" : "NOT_REQUIRED", !quality.required() || quality.released(), "QUALITY".equals(currentStage), "RELEASE_QUALITY"));
        steps.add(step("INVOICE", "Invoice", latestStatus(invoices), invoices.stream().anyMatch(item -> isIssuedInvoice(item.getStatus())), "INVOICE".equals(currentStage), invoiceAction(invoices)));
        steps.add(step("PAYMENT", "Payment", latestStatus(payments), payments.stream().anyMatch(item -> ContractPaymentStatus.PAID.name().equals(normalized(item.getStatus()))), "PAYMENT".equals(currentStage), "CREATE_OR_PAY_PAYMENT"));
        steps.add(step("CLOSEOUT", "Closeout", closeoutReadiness == null || closeoutReadiness.closeoutStatus() == null ? null : closeoutReadiness.closeoutStatus().name(),
            closeoutReadiness != null && closeoutReadiness.closeoutStatus() == ProcurementCloseoutStatus.CLOSED,
            "CLOSEOUT".equals(currentStage), closeoutNextAction(closeoutReadiness)));
        return steps;
    }

    private List<ContractPayment> paymentsForOrder(PurchaseOrder purchaseOrder, List<ContractInvoice> invoices) {
        Map<UUID, ContractPayment> byId = new LinkedHashMap<>();
        contractPaymentRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getId())
            .forEach(payment -> byId.put(payment.getId(), payment));
        invoices.stream()
            .map(ContractInvoice::getId)
            .filter(id -> id != null)
            .forEach(invoiceId -> contractPaymentRepository
                .findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(), invoiceId)
                .forEach(payment -> byId.putIfAbsent(payment.getId(), payment)));
        return new ArrayList<>(byId.values());
    }

    private String nextAction(
        PurchaseOrder purchaseOrder,
        List<AdvanceShippingNotice> asns,
        List<GoodsReceipt> receipts,
        QualitySummary quality,
        PayablesReadinessResponse readiness,
        List<ContractInvoice> invoices,
        List<ContractPayment> payments,
        ProcurementCloseoutDtos.Readiness closeoutReadiness
    ) {
        if (!PurchaseOrderStatus.SENT.name().equals(normalized(purchaseOrder.getStatus()))
            && !PurchaseOrderStatus.PARTIALLY_RECEIVED.name().equals(normalized(purchaseOrder.getStatus()))
            && receipts.isEmpty()) {
            return "SEND_PURCHASE_ORDER";
        }
        boolean deliveryConfirmed = asns.stream().anyMatch(item ->
            "CONFIRMED".equals(normalized(item.getStatus()))
                || "RECEIVED".equals(normalized(item.getStatus()))
                || "PARTIALLY_RECEIVED".equals(normalized(item.getStatus())));
        if (requiresConfirmedDelivery(purchaseOrder) && !deliveryConfirmed) {
            return "CONFIRM_DELIVERY";
        }
        if (receipts.isEmpty()) {
            return "POST_GOODS_RECEIPT";
        }
        if (quality.required() && !quality.released()) {
            return "RELEASE_QUALITY";
        }
        if (readiness != null && !readiness.evidenceAvailable()) {
            return "WAIT_FOR_INVOICE_EVIDENCE";
        }
        if (hasDraftInvoice(invoices)) {
            return "ISSUE_INVOICE";
        }
        if (readiness != null && readiness.remainingInvoiceableAmount() != null && readiness.remainingInvoiceableAmount().signum() > 0) {
            return "CREATE_INVOICE";
        }
        if (invoices.stream().anyMatch(item -> isIssuedInvoice(item.getStatus()))
            && payments.stream().noneMatch(item -> ContractPaymentStatus.PAID.name().equals(normalized(item.getStatus())))) {
            return payments.stream().anyMatch(item -> ContractPaymentStatus.SENT_TO_TREASURY.name().equals(normalized(item.getStatus())))
                ? "MARK_PAYMENT_PAID"
                : payments.stream().anyMatch(item -> ContractPaymentStatus.DRAFT.name().equals(normalized(item.getStatus())))
                    ? "SUBMIT_PAYMENT_TO_TREASURY"
                    : "CREATE_PAYMENT";
        }
        return closeoutNextAction(closeoutReadiness);
    }

    private String stageFor(
        String nextAction,
        List<ContractPayment> payments,
        List<ContractInvoice> invoices,
        PayablesReadinessResponse readiness,
        QualitySummary quality,
        List<GoodsReceipt> receipts,
        List<AdvanceShippingNotice> asns,
        PurchaseOrder purchaseOrder,
        PurchaseRequest request,
        DemandPlan demandPlan
    ) {
        if (payments.stream().anyMatch(item -> ContractPaymentStatus.PAID.name().equals(normalized(item.getStatus())))) {
            if (List.of("CREATE_CLOSEOUT", "SUBMIT_CLOSEOUT", "WAIT_FOR_CLOSEOUT_APPROVAL", "FINALIZE_CLOSEOUT", "PROCUREMENT_CLOSED").contains(nextAction)) {
                return "CLOSEOUT";
            }
            return "PAYMENT";
        }
        if (List.of("CREATE_CLOSEOUT", "SUBMIT_CLOSEOUT", "WAIT_FOR_CLOSEOUT_APPROVAL", "FINALIZE_CLOSEOUT", "PROCUREMENT_CLOSED").contains(nextAction)) {
            return "CLOSEOUT";
        }
        if (List.of("CREATE_PAYMENT", "SUBMIT_PAYMENT_TO_TREASURY", "MARK_PAYMENT_PAID").contains(nextAction)) {
            return "PAYMENT";
        }
        if (!invoices.isEmpty() || (readiness != null && readiness.remainingInvoiceableAmount() != null && readiness.remainingInvoiceableAmount().signum() > 0)) {
            return "INVOICE";
        }
        if (quality.required() && !quality.released()) {
            return "QUALITY";
        }
        if (!receipts.isEmpty()) {
            return "GOODS_RECEIPT";
        }
        if ("CONFIRM_DELIVERY".equals(nextAction) || !asns.isEmpty()) {
            return "DELIVERY_CONFIRMATION";
        }
        if (purchaseOrder != null) {
            return "PURCHASE_ORDER";
        }
        if (request != null) {
            return "PURCHASE_REQUEST";
        }
        if (demandPlan != null) {
            return "DEMAND_PLAN";
        }
        return "DEMAND_PLAN";
    }

    private QualitySummary qualitySummary(List<QualityInspection> inspections) {
        BigDecimal received = inspections.stream()
            .map(QualityInspection::getReceivedQuantity)
            .filter(value -> value != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accepted = inspections.stream()
            .map(QualityInspection::getAcceptedQuantity)
            .filter(value -> value != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejected = inspections.stream()
            .map(QualityInspection::getRejectedQuantity)
            .filter(value -> value != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean released = !inspections.isEmpty() && inspections.stream().allMatch(item ->
            QualityInspectionStatus.RELEASED.name().equals(normalized(item.getStatus()))
                || QualityInspectionStatus.PASSED.name().equals(normalized(item.getStatus()))
                || QualityInspectionStatus.REJECTED.name().equals(normalized(item.getStatus())));
        return new QualitySummary(
            !inspections.isEmpty(),
            released,
            received,
            accepted,
            rejected,
            inspections.stream().map(item -> document("QUALITY_INSPECTION", item)).toList()
        );
    }

    private PurchaseOrder loadPurchaseOrder(UUID purchaseOrderId) {
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
    }

    private FlowDocument document(String type, com.enterprise.mm.common.BusinessEntity entity) {
        if (entity == null) {
            return null;
        }
        BigDecimal amount = null;
        String currency = null;
        LocalDate documentDate = null;
        LocalDateTime eventAt = entity.getUpdatedAt() == null ? entity.getCreatedAt() : entity.getUpdatedAt();
        if (entity instanceof DemandPlan item) {
            amount = item.getTotalApprovedAmount();
            currency = item.getCurrencyCode();
            documentDate = item.getPeriodStart();
        } else if (entity instanceof PurchaseRequest item) {
            amount = item.getTotalAmount();
            currency = item.getCurrencyCode();
            documentDate = item.getRequiredDate();
        } else if (entity instanceof PurchaseOrder item) {
            amount = item.getTotalAmount();
            currency = item.getCurrencyCode();
            documentDate = item.getOrderDate();
        } else if (entity instanceof AdvanceShippingNotice item) {
            amount = item.getTotalAmount();
            currency = item.getCurrencyCode();
            documentDate = item.getExpectedArrivalDate();
            eventAt = firstNonNull(item.getReceivedAt(), item.getConfirmedAt(), item.getSentAt(), eventAt);
        } else if (entity instanceof GoodsReceipt item) {
            amount = item.getAcceptedAmount();
            currency = item.getCurrencyCode();
            eventAt = firstNonNull(item.getPostedAt(), eventAt);
        } else if (entity instanceof QualityInspection item) {
            amount = item.getAcceptedQuantity();
            eventAt = firstNonNull(item.getCompletedAt(), eventAt);
        } else if (entity instanceof WorkCompletionAct item) {
            amount = item.getAmount();
            documentDate = item.getPeriodEnd();
        } else if (entity instanceof ContractInvoice item) {
            amount = item.getAmount();
            currency = item.getCurrencyCode();
            documentDate = item.getInvoiceDate();
        } else if (entity instanceof ContractPayment item) {
            amount = item.getAmount();
            currency = item.getCurrencyCode();
            documentDate = item.getPaymentDate();
        } else if (entity instanceof ProcurementCloseout item) {
            amount = item.getUnresolvedLiabilityAmount();
            documentDate = item.getClosedAt() == null ? item.getReopenedAt() == null ? null : item.getReopenedAt().toLocalDate() : item.getClosedAt().toLocalDate();
            eventAt = firstNonNull(item.getClosedAt(), item.getApprovedAt(), item.getSubmittedAt(), eventAt);
        }
        return new FlowDocument(
            entity.getId(),
            type,
            entity.getCode(),
            entity.getName(),
            entity.getStatus(),
            amount,
            currency,
            documentDate,
            eventAt
        );
    }

    private FlowStep step(String key, String label, String status, boolean complete, boolean active, String action) {
        return new FlowStep(key, label, status, complete, active, action);
    }

    private String status(com.enterprise.mm.common.BusinessEntity entity) {
        return entity == null ? null : entity.getStatus();
    }

    private String latestStatus(List<? extends com.enterprise.mm.common.BusinessEntity> items) {
        return items.isEmpty() ? null : items.get(0).getStatus();
    }

    private String serviceExecutionStatus(
        boolean works,
        List<ServiceExecution> executions,
        List<ServiceAcceptanceAct> acts
    ) {
        if (works) {
            return "NOT_REQUIRED";
        }
        if (acts != null && !acts.isEmpty()) {
            return "COMPLETED";
        }
        return executions == null || executions.isEmpty() ? null : executions.get(0).getStatus();
    }

    private boolean isIssuedInvoice(String status) {
        String normalized = normalized(status);
        return ContractInvoiceStatus.ISSUED.name().equals(normalized)
            || ContractInvoiceStatus.PARTIALLY_PAID.name().equals(normalized)
            || ContractInvoiceStatus.PAID.name().equals(normalized);
    }

    private boolean hasDraftInvoice(List<ContractInvoice> invoices) {
        return invoices.stream().anyMatch(item -> ContractInvoiceStatus.DRAFT.name().equals(normalized(item.getStatus())));
    }

    private String invoiceAction(List<ContractInvoice> invoices) {
        return invoiceAction(hasDraftInvoice(invoices));
    }

    private String invoiceStepAction(List<ContractInvoice> invoices, PayablesReadinessResponse readiness) {
        if (hasDraftInvoice(invoices)) {
            return "ISSUE_INVOICE";
        }
        if (readiness == null
            || readiness.remainingInvoiceableAmount() == null
            || readiness.remainingInvoiceableAmount().signum() <= 0) {
            return null;
        }
        return invoiceAction(invoices);
    }

    private String invoiceAction(boolean hasDraftInvoice) {
        return hasDraftInvoice ? "ISSUE_INVOICE" : "CREATE_INVOICE";
    }

    private String paymentAction(List<ContractPayment> payments) {
        if (payments.stream().anyMatch(item -> ContractPaymentStatus.SENT_TO_TREASURY.name().equals(normalized(item.getStatus())))) {
            return "MARK_PAYMENT_PAID";
        }
        if (payments.stream().anyMatch(item -> ContractPaymentStatus.DRAFT.name().equals(normalized(item.getStatus())))) {
            return "SUBMIT_PAYMENT_TO_TREASURY";
        }
        return "CREATE_PAYMENT";
    }

    private String acceptanceAction(boolean works) {
        return works ? "CREATE_WORK_ACT" : "CREATE_SERVICE_ACT";
    }

    private List<FlowDocument> serviceWorkCompletionDocuments(
        List<ServiceExecution> executions,
        List<WorkCompletionAct> workActs
    ) {
        List<FlowDocument> documents = new ArrayList<>();
        executions.stream()
            .map(item -> document("SERVICE_COMPLETION", item))
            .forEach(documents::add);
        workActs.stream()
            .map(item -> document("WORK_COMPLETION_ACT", item))
            .forEach(documents::add);
        return documents;
    }

    private boolean requiresConfirmedDelivery(PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null || purchaseOrder.getId() == null) {
            return false;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = purchaseOrder.getCompanyId() == null
            ? CompanyContext.currentCompany()
            : purchaseOrder.getCompanyId();
        boolean auctionAward = procurementAwardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                tenantId, companyId, purchaseOrder.getId()
            )
            .map(outcome -> outcome.getSourceType() == ProcurementAwardSourceType.MANAGED_AUCTION)
            .orElse(false);
        if (auctionAward) {
            return true;
        }
        return procurementProcedureRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(tenantId, companyId, purchaseOrder.getId())
            .map(procedure -> {
                try {
                    return ProcurementMethodType.valueOf(procedure.getMethod()).executionPath()
                        == ProcurementExecutionPath.AUCTION;
                } catch (RuntimeException ignored) {
                    return ProcurementMethodType.E_AUCTION.name().equals(procedure.getMethod());
                }
            })
            .orElse(false);
    }

    private ProcurementProcedure procedureFor(PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null || purchaseOrder.getId() == null) {
            return null;
        }
        return procurementProcedureRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                purchaseOrder.getCompanyId(),
                purchaseOrder.getId()
            )
            .orElse(null);
    }

    private ProcurementCloseout closeoutFor(ProcurementProcedure procedure) {
        if (procedure == null || procedure.getId() == null) {
            return null;
        }
        return procurementCloseoutRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                procedure.getCompanyId(),
                procedure.getId()
            )
            .orElse(null);
    }

    private ProcurementCloseoutDtos.Readiness closeoutReadinessFor(ProcurementProcedure procedure) {
        if (procedure == null || procedure.getId() == null) {
            return null;
        }
        return closeoutReadinessService.readiness(procedure.getId());
    }

    private String closeoutNextAction(ProcurementCloseoutDtos.Readiness closeoutReadiness) {
        if (closeoutReadiness == null) {
            return "CREATE_CLOSEOUT";
        }
        ProcurementCloseoutStatus status = closeoutReadiness.closeoutStatus();
        if (status == ProcurementCloseoutStatus.CLOSED) {
            return "PROCUREMENT_CLOSED";
        }
        if (status == ProcurementCloseoutStatus.APPROVED) {
            return "FINALIZE_CLOSEOUT";
        }
        if (status == ProcurementCloseoutStatus.APPROVAL_PENDING || status == ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING) {
            return "WAIT_FOR_CLOSEOUT_APPROVAL";
        }
        return closeoutReadiness.availableActions().stream()
            .filter(action -> Boolean.TRUE.equals(action.enabled()))
            .map(ProcurementCloseoutDtos.Action::key)
            .filter(key -> List.of("CREATE_CLOSEOUT", "SUBMIT_CLOSEOUT", "FINALIZE_CLOSEOUT", "REQUEST_REOPEN").contains(key))
            .findFirst()
            .orElse("RECHECK_READINESS");
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
