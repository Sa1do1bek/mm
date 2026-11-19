package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.ProcurementCloseoutType;
import com.enterprise.mm.enums.ProcurementFinancialClosePolicy;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.ProcurementCloseoutDtos;
import com.enterprise.mm.payload.ProcurementPayablesDtos;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementCloseoutRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementWorkspaceCloseoutFlowTest {
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private DemandPlanRepository demandPlanRepository;
    @Mock private AdvanceShippingNoticeRepository advanceShippingNoticeRepository;
    @Mock private GoodsReceiptRepository goodsReceiptRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private ContractInvoiceRepository contractInvoiceRepository;
    @Mock private ContractPaymentRepository contractPaymentRepository;
    @Mock private ProcurementPayablesService payablesService;
    @Mock private ProcurementCloseoutReadinessService closeoutReadinessService;
    @Mock private ProcurementMethodDecisionRepository procurementMethodDecisionRepository;
    @Mock private ProcurementProcedureRepository procurementProcedureRepository;
    @Mock private ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private ProcurementCloseoutRepository procurementCloseoutRepository;
    @Mock private ServiceOrderRepository serviceOrderRepository;
    @Mock private ServiceExecutionRepository serviceExecutionRepository;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @InjectMocks private ProcurementWorkspaceService workspaceService;

    private final UUID companyId = UUID.randomUUID();
    private final UUID purchaseOrderId = UUID.randomUUID();
    private final UUID procedureId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-test");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void paidServicePurchaseOrderMovesFromApprovedToClosedCloseout() {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrder.setStatus("SENT");
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(procedureId);
        procedure.setTenantId(TenantContext.currentTenant());
        procedure.setCompanyId(companyId);
        ProcurementCloseout closeout = new ProcurementCloseout();
        closeout.setId(UUID.randomUUID());
        closeout.setTenantId(TenantContext.currentTenant());
        closeout.setCompanyId(companyId);
        closeout.setProcurementProcedureId(procedureId);
        closeout.setCloseoutType(ProcurementCloseoutType.NORMAL);
        closeout.setLiabilityDisposition(com.enterprise.mm.enums.ProcurementCloseoutLiabilityDisposition.NONE);
        closeout.setUnresolvedLiabilityAmount(BigDecimal.ZERO);

        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setId(UUID.randomUUID());
        serviceOrder.setPurchaseOrderId(purchaseOrderId);
        ServiceExecution execution = new ServiceExecution();
        execution.setId(UUID.randomUUID());
        execution.setStatus("ACCEPTED");
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setStatus("APPROVED");
        ContractInvoice invoice = new ContractInvoice();
        invoice.setStatus("PAID");
        ContractPayment payment = new ContractPayment();
        payment.setStatus("PAID");

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(serviceOrderRepository.findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId)).thenReturn(List.of(serviceOrder));
        when(serviceExecutionRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, serviceOrder.getId())).thenReturn(List.of(execution));
        when(serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, serviceOrder.getId())).thenReturn(List.of(act));
        when(contractInvoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), purchaseOrderId)).thenReturn(List.of(invoice));
        when(contractPaymentRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), purchaseOrderId)).thenReturn(List.of(payment));
        when(payablesService.purchaseOrderReadiness(purchaseOrderId)).thenReturn(payablesReadiness());
        when(procurementProcedureRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, purchaseOrderId)).thenReturn(Optional.of(procedure));
        when(procurementCloseoutRepository.findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, procedureId)).thenReturn(Optional.of(closeout));

        closeout.setCloseoutStatus(ProcurementCloseoutStatus.APPROVED);
        when(closeoutReadinessService.readiness(procedureId)).thenReturn(readiness(ProcurementCloseoutStatus.APPROVED));
        var approvedFlow = workspaceService.byPurchaseOrder(purchaseOrderId);
        assertThat(approvedFlow.nextAction()).isEqualTo("FINALIZE_CLOSEOUT");

        closeout.setCloseoutStatus(ProcurementCloseoutStatus.CLOSED);
        when(closeoutReadinessService.readiness(procedureId)).thenReturn(readiness(ProcurementCloseoutStatus.CLOSED));
        var closedFlow = workspaceService.byPurchaseOrder(purchaseOrderId);
        assertThat(closedFlow.nextAction()).isEqualTo("PROCUREMENT_CLOSED");
        assertThat(closedFlow.steps()).filteredOn(step -> "CLOSEOUT".equals(step.key()))
            .singleElement().extracting(step -> step.complete()).isEqualTo(true);
    }

    private ProcurementPayablesDtos.PayablesReadinessResponse payablesReadiness() {
        return new ProcurementPayablesDtos.PayablesReadinessResponse(
            purchaseOrderId, RequestType.SERVICE.name(), true, BigDecimal.TEN, BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, null, null, List.of());
    }

    private ProcurementCloseoutDtos.Readiness readiness(ProcurementCloseoutStatus status) {
        return new ProcurementCloseoutDtos.Readiness(
            procedureId, null, null, true, false, true, true, true, true, true, true, true,
            ProcurementFinancialClosePolicy.PAYMENT_REQUIRED, status, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
