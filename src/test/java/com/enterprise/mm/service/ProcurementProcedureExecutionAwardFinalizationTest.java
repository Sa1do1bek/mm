package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.payload.ContractLimitResult;
import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.Response;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.AuctionAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkCallOffRequest;
import com.enterprise.mm.payload.ProcurementProcedureResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.service.ProcurementAwardFinalizationOrchestrator.AwardFinalizationCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementProcedureExecutionAwardFinalizationTest {
    private static final String TENANT_ID = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;
    private static final UUID PROCEDURE_ID = UUID.randomUUID();
    private static final UUID PURCHASE_REQUEST_ID = UUID.randomUUID();
    private static final UUID SUPPLIER_ID = UUID.randomUUID();
    private static final UUID PURCHASE_ORDER_ID = UUID.randomUUID();
    private static final UUID CONTRACT_ID = UUID.randomUUID();
    private static final UUID MATERIAL_ID = UUID.randomUUID();

    @Mock private ProcurementProcedureRepository repository;
    @Mock private ProcurementProcedureService procedureService;
    @Mock private ProcurementAwardPurchaseOrderService awardPurchaseOrderService;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private ContractService contractService;
    @Mock private ContractSpecificationService contractSpecificationService;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Spy private ProcurementMethodPolicy methodPolicy = new ProcurementMethodPolicy();
    @Mock private ProcurementAwardFinalizationOrchestrator awardFinalizationOrchestrator;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private ProcurementFrameworkService frameworkService;
    @InjectMocks private ProcurementProcedureExecutionService service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void auctionAwardCreatesPurchaseOrderAndFinalizesManagedAuctionOutcome() {
        ProcurementProcedure procedure = procedure("E_AUCTION", "AUCTION", "AUCTION_CLOSED");
        AuctionAwardRequest request = new AuctionAwardRequest(
            SUPPLIER_ID,
            BigDecimal.valueOf(460),
            "UZS",
            "EA-1",
            "Winning bid",
            LocalDate.now(),
            LocalDate.now().plusDays(7)
        );
        PurchaseOrderResponse purchaseOrder = purchaseOrder(PURCHASE_ORDER_ID, null, BigDecimal.valueOf(460));
        when(repository.lockScopedById(TENANT_ID, COMPANY_ID, PROCEDURE_ID)).thenReturn(Optional.of(procedure));
        when(awardPurchaseOrderService.create(
            eq(procedure),
            eq(SUPPLIER_ID),
            eq(BigDecimal.valueOf(460)),
            eq("UZS"),
            isNull(),
            eq(request.orderDate()),
            eq(request.expectedDeliveryDate()),
            eq("Winning bid")
        )).thenReturn(purchaseOrder);

        PurchaseOrderResponse response = service.awardAuction(PROCEDURE_ID, request);

        assertThat(response).isSameAs(purchaseOrder);
        AwardFinalizationCommand command = capturedCommand();
        assertThat(command.sourceType()).isEqualTo(ProcurementAwardSourceType.MANAGED_AUCTION);
        assertThat(command.sourceId()).isEqualTo(PROCEDURE_ID);
        assertThat(command.awardedSupplierId()).isEqualTo(SUPPLIER_ID);
        assertThat(command.purchaseOrderId()).isEqualTo(PURCHASE_ORDER_ID);
        assertThat(command.awardAmount()).isEqualByComparingTo("460");
        assertThat(command.currencyCode()).isEqualTo("UZS");
        assertThat(command.awardReference()).isEqualTo("EA-1");
        assertThat(command.procedureStatus()).isEqualTo(ProcurementProcedureStatus.COMPLETED);
    }

    @Test
    void frameworkAwardDelegatesContractOutcomeToOrchestrator() {
        ProcurementProcedureResponse procedureResponse = new ProcurementProcedureResponse();
        procedureResponse.setId(PROCEDURE_ID);
        procedureResponse.setStatus("FRAMEWORK_DRAFT");
        when(procedureService.get(PROCEDURE_ID)).thenReturn(procedureResponse);
        FrameworkAwardRequest request = new FrameworkAwardRequest(
            SUPPLIER_ID,
            BigDecimal.valueOf(2000),
            "UZS",
            "FA-1",
            "FRAMEWORK-1",
            LocalDate.now(),
            LocalDate.now().plusYears(1)
        );

        ProcurementProcedureResponse response = service.awardFramework(PROCEDURE_ID, request);

        assertThat(response).isSameAs(procedureResponse);
        verify(frameworkService).createFromProcedure(PROCEDURE_ID, request);
    }

    @Test
    void frameworkCallOffDelegatesPurchaseOrderOutcomeToOrchestrator() {
        PurchaseOrderResponse purchaseOrder = purchaseOrder(PURCHASE_ORDER_ID, CONTRACT_ID, BigDecimal.valueOf(475));
        FrameworkCallOffRequest request = new FrameworkCallOffRequest(
            BigDecimal.valueOf(475),
            LocalDate.now(),
            LocalDate.now().plusDays(14)
        );
        when(frameworkService.callOffFromProcedure(PROCEDURE_ID, request)).thenReturn(purchaseOrder);

        PurchaseOrderResponse response = service.createFrameworkCallOff(PROCEDURE_ID, request);

        assertThat(response).isSameAs(purchaseOrder);
        verify(frameworkService).callOffFromProcedure(PROCEDURE_ID, request);
    }

    private AwardFinalizationCommand capturedCommand() {
        ArgumentCaptor<AwardFinalizationCommand> captor =
            ArgumentCaptor.forClass(AwardFinalizationCommand.class);
        verify(awardFinalizationOrchestrator).finalizeAward(captor.capture());
        return captor.getValue();
    }

    private ProcurementProcedure procedure(String method, String executionPath, String status) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(PROCEDURE_ID);
        procedure.setTenantId(TENANT_ID);
        procedure.setCompanyId(COMPANY_ID);
        procedure.setCode("PROC-1");
        procedure.setName("Procurement procedure");
        procedure.setProcedureNumber("PROC-1");
        procedure.setMethod(method);
        procedure.setExecutionPath(executionPath);
        procedure.setStatus(status);
        procedure.setPurchaseRequestId(PURCHASE_REQUEST_ID);
        return procedure;
    }

    private PurchaseOrderResponse purchaseOrder(UUID id, UUID contractId, BigDecimal totalAmount) {
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        response.setId(id);
        response.setStatus("DRAFT");
        response.setSupplierId(SUPPLIER_ID);
        response.setPurchaseRequestId(PURCHASE_REQUEST_ID);
        response.setContractId(contractId);
        response.setTotalAmount(totalAmount);
        response.setCurrencyCode("UZS");
        return response;
    }

    private PurchaseRequestItem purchaseRequestItem() {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(TENANT_ID);
        item.setCompanyId(COMPANY_ID);
        item.setCode("PRI-1");
        item.setName("Framework material");
        item.setStatus("APPROVED");
        item.setPurchaseRequestId(PURCHASE_REQUEST_ID);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(MATERIAL_ID);
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.valueOf(200));
        return item;
    }
}
