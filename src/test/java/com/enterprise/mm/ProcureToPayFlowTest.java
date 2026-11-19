package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoLineRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreatePriceDiscoveryRfqRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreateTenderRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.FinalizePriceDiscoveryRequest;
import com.enterprise.mm.payload.ProcurementCampaignCreateRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardPurchaseOrderRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionCreateRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkCallOffRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestItemCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestUpdateRequest;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.CommissionMemberRole;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ProcurementMethodCapability;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.entity.TenderBidOpeningLine;
import com.enterprise.mm.entity.TenderBidOpeningProtocol;
import com.enterprise.mm.entity.TenderEvaluationProtocol;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.IntegrationEventDirection;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementMethodCapabilityRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierEvaluationRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.TenderBidOpeningLineRepository;
import com.enterprise.mm.repository.TenderBidOpeningProtocolRepository;
import com.enterprise.mm.repository.TenderEvaluationProtocolRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.DemandPlanApprovalAdapter;
import com.enterprise.mm.service.approval.ProcurementDirectAwardApprovalAdapter;
import com.enterprise.mm.service.approval.ProcurementFrameworkApprovalAdapter;
import com.enterprise.mm.service.approval.ProcurementMethodDecisionApprovalAdapter;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.service.approval.PurchaseOrderApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.ContractInvoiceService;
import com.enterprise.mm.service.ContractPaymentService;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.DemandPlanService;
import com.enterprise.mm.service.PriceDiscoveryService;
import com.enterprise.mm.service.ProcurementCampaignService;
import com.enterprise.mm.service.ProcurementDirectAwardService;
import com.enterprise.mm.service.ProcurementFrameworkService;
import com.enterprise.mm.service.ProcurementInvoiceEvidenceService;
import com.enterprise.mm.service.ProcurementMethodDecisionService;
import com.enterprise.mm.service.ProcurementProcedureExecutionService;
import com.enterprise.mm.service.ProcurementProcedureService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.service.PurchaseRequestService;
import com.enterprise.mm.service.ServiceAcceptanceActService;
import com.enterprise.mm.service.SupplierQuotationService;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.payload.ContractInvoiceResponse;
import com.enterprise.mm.payload.ContractPaymentCreateRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentMarkPaidRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentSubmitRequest;
import com.enterprise.mm.supplier.po.service.SupplierPoAsnService;
import com.enterprise.mm.supplier.po.service.SupplierPoBuyerDecisionService;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.CommitmentCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.LineDecisionCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = {"ADMIN", "MM_MANAGER", "BUDGET_CONTROLLER", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER"})
class ProcureToPayFlowTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired ProcurementCampaignRepository procurementCampaignRepository;
    @Autowired ProcurementMethodCapabilityRepository procurementMethodCapabilityRepository;
    @Autowired ProcurementProcedureRepository procurementProcedureRepository;
    @Autowired BudgetItemRepository budgetItemRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository rfqItemRepository;
    @Autowired RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository;
    @Autowired ServiceOrderRepository serviceOrderRepository;
    @Autowired SupplierQuotationRepository quotationRepository;
    @Autowired SupplierQuotationItemRepository quotationItemRepository;
    @Autowired TenderBidOpeningProtocolRepository tenderBidOpeningProtocolRepository;
    @Autowired TenderBidOpeningLineRepository tenderBidOpeningLineRepository;
    @Autowired TenderEvaluationProtocolRepository tenderEvaluationProtocolRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired StorageBinRepository storageBinRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired StorageLocationRepository storageLocationRepository;
    @Autowired SupplierAccreditationApplicationRepository supplierAccreditationApplicationRepository;
    @Autowired SupplierAccreditationDecisionRepository supplierAccreditationDecisionRepository;
    @Autowired SupplierAccreditationDocumentRepository supplierAccreditationDocumentRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired SupplierEvaluationRepository supplierEvaluationRepository;
    @Autowired SupplierPoCommitmentRepository supplierPoCommitmentRepository;
    @Autowired SupplierPoDispatchRepository supplierPoDispatchRepository;
    @Autowired DeliveryScheduleRepository deliveryScheduleRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Autowired CommissionRepository commissionRepository;
    @Autowired CommissionMemberRepository commissionMemberRepository;
    @Autowired DemandPlanService demandPlanService;
    @Autowired DemandPlanItemService demandPlanItemService;
    @Autowired ProcurementMethodDecisionService procurementMethodDecisionService;
    @Autowired ProcurementProcedureService procurementProcedureService;
    @Autowired ProcurementProcedureExecutionService procurementProcedureExecutionService;
    @Autowired ProcurementDirectAwardService procurementDirectAwardService;
    @Autowired ProcurementFrameworkService procurementFrameworkService;
    @Autowired ProcurementInvoiceEvidenceService procurementInvoiceEvidenceService;
    @Autowired ProcurementCampaignService procurementCampaignService;
    @Autowired PriceDiscoveryService priceDiscoveryService;
    @Autowired SupplierQuotationService supplierQuotationService;
    @Autowired PurchaseRequestService purchaseRequestService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired ServiceAcceptanceActService serviceAcceptanceActService;
    @Autowired ContractInvoiceService contractInvoiceService;
    @Autowired ContractPaymentService contractPaymentService;
    @Autowired ContractPaymentRepository contractPaymentRepository;
    @Autowired AdvanceShippingNoticeRepository advanceShippingNoticeRepository;
    @Autowired AttachmentService attachmentService;
    @Autowired ApprovalEngineService approvalEngineService;
    @Autowired SupplierPoResponseService supplierPoResponseService;
    @Autowired SupplierPoBuyerDecisionService supplierPoBuyerDecisionService;
    @Autowired SupplierPoAsnService supplierPoAsnService;

    @BeforeEach
    void ensureProcurementApprovalFlows() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        ensureApprovalFlow(DemandPlanApprovalAdapter.MODULE_KEY, "Test demand plan approval flow", "TEST-DP-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(DemandPlanApprovalAdapter.MODULE_KEY, "Test service demand plan approval flow", "TEST-DP-SVC-STEP", "PROCUREMENT_MANAGER", RequestType.SERVICE);
        ensureApprovalFlow(PurchaseRequestApprovalAdapter.MODULE_KEY, "Test PR approval flow", "TEST-PR-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(PurchaseRequestApprovalAdapter.MODULE_KEY, "Test service PR approval flow", "TEST-PR-SVC-STEP", "PROCUREMENT_MANAGER", RequestType.SERVICE);
        ensureApprovalFlow(PurchaseOrderApprovalAdapter.MODULE_KEY, "Test PO approval flow", "TEST-PO-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(PurchaseOrderApprovalAdapter.MODULE_KEY, "Test service PO approval flow", "TEST-PO-SVC-STEP", "PROCUREMENT_MANAGER", RequestType.SERVICE);
        ensureApprovalFlow(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY, "Test procurement method approval flow", "TEST-PMD-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ProcurementDirectAwardApprovalAdapter.MODULE_KEY, "Test direct award approval flow", "TEST-DA-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ProcurementDirectAwardApprovalAdapter.SINGLE_SOURCE_MODULE_KEY, "Test single source approval flow", "TEST-SS-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ProcurementFrameworkApprovalAdapter.MODULE_KEY, "Test framework approval flow", "TEST-FW-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, "Test service act approval flow", "TEST-SAA-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ServiceFinanceApprovalAdapter.MODULE_KEY, "Test service finance approval flow", "TEST-SFA-STEP", "FINANCE_CONTROLLER");
        java.util.List.of(
            "DIRECT_PURCHASE",
            "SINGLE_SOURCE",
            "FRAME_AGREEMENT",
            "REQUEST_FOR_QUOTATION",
            "OPEN_COMPETITION",
            "CLOSED_COMPETITION",
            "OPEN_TENDER",
            "CLOSED_TENDER"
        ).forEach(this::ensureMethodCapability);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void purchaseRequestCreateAndUpdateAcceptNestedItemsTransactionally() {
        Material firstMaterial = material("MAT-NESTED-PR-1-" + UUID.randomUUID().toString().substring(0, 8));
        Material secondMaterial = material("MAT-NESTED-PR-2-" + UUID.randomUUID().toString().substring(0, 8));

        PurchaseRequestCreateRequest request = new PurchaseRequestCreateRequest();
        request.setCode("PR-NESTED-" + UUID.randomUUID().toString().substring(0, 8));
        request.setName("Nested PR");
        request.setCurrencyCode("UZS");
        request.setItems(java.util.List.of(
            purchaseRequestItem(firstMaterial.getId(), 2, 100),
            purchaseRequestItem(secondMaterial.getId(), 3, 50)
        ));

        var created = purchaseRequestService.create(request);

        assertThat(created.getTotalAmount()).isEqualByComparingTo("350");
        assertThat(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            created.getId()
        )).hasSize(2);

        PurchaseRequestUpdateRequest update = new PurchaseRequestUpdateRequest();
        update.setCode(created.getCode());
        update.setName(created.getName());
        update.setCurrencyCode("UZS");
        update.setItems(java.util.List.of(purchaseRequestItem(firstMaterial.getId(), 4, 125)));

        var updated = purchaseRequestService.update(created.getId(), update);

        assertThat(updated.getTotalAmount()).isEqualByComparingTo("500");
        assertThat(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            created.getId()
        )).singleElement().satisfies(item -> {
            assertThat(item.getMaterialId()).isEqualTo(firstMaterial.getId());
            assertThat(item.getQuantity()).isEqualByComparingTo("4");
            assertThat(item.getEstimatedPrice()).isEqualByComparingTo("125");
        });
    }

    @Test
    void procureToPayCreatesPoAndPostsGoodsReceipt() {
        Material material = material("MAT-P2P-1");
        Supplier supplier = supplier("SUP-P2P-1");
        Warehouse warehouse = warehouse("WH-P2P-1");

        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode("PR-P2P-1");
        pr.setName("Pump spare parts");
        pr.setStatus(PurchaseRequestStatus.DRAFT.name());
        pr.setRequiredDate(LocalDate.now().plusDays(10));
        pr.setTotalAmount(BigDecimal.valueOf(1000));
        pr.setCurrencyCode("UZS");
        pr.setDemandPlanId(approvedDemandPlanId("DP-" + pr.getCode()));
        pr = purchaseRequestRepository.save(pr);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-P2P-1");
        item.setName("Pump bearing");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        assertThat(purchaseRequestService.submit(pr.getId()).getStatus()).isEqualTo(PurchaseRequestStatus.APPROVAL_PENDING.name());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());
        assertThat(purchaseRequestRepository.findAnyById(pr.getId()).orElseThrow().getStatus()).isEqualTo(PurchaseRequestStatus.APPROVED.name());

        RFQ rfq = seedRfq(pr);
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-P2P-1");
        quotation.setName("Quotation A");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(950));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-P2P-1");
        quotationItem.setName("Quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setMaterialId(material.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(95));
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT.name());

        po = purchaseOrderService.submitForApproval(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVAL_PENDING.name());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());

        po = purchaseOrderService.send(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());

        po = receiveSentGoodsPoThroughSupplierPortal(po, supplier, warehouse, "P2P-1");
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED.name());
        assertThat(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), material.getId(), warehouse.getId()))
            .singleElement()
            .extracting(balance -> balance.getQuantity().compareTo(BigDecimal.TEN))
            .isEqualTo(0);
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "MM_ACCRUAL_REQUIRED".equals(event.getEventType()));
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "BUDGETING.PR_SUBMITTED".equals(event.getEventType()) && "BUDGETING".equals(event.getAggregateType()));
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "BUDGETING.PR_APPROVED".equals(event.getEventType()) && "BUDGETING".equals(event.getAggregateType()));
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "TREASURY.PO_SENT".equals(event.getEventType()) && "TREASURY".equals(event.getAggregateType()));
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "FI.STOCK_RECEIPT_POSTED".equals(event.getEventType()) && "FI".equals(event.getAggregateType()));
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "MM.INVOICE_REQUIRED".equals(event.getEventType()) && "PurchaseOrder".equals(event.getAggregateType()));
        UUID receivedPoId = po.getId();
        assertThat(supplierEvaluationRepository.findByTenantIdAndSupplier_IdAndIsDeletedFalse(TenantContext.currentTenant(), supplier.getId()))
            .filteredOn(evaluation -> receivedPoId.equals(evaluation.getPurchaseOrderId()))
            .filteredOn(evaluation -> "GOODS_RECEIPT".equals(evaluation.getEvaluationSource()))
            .singleElement()
            .satisfies(evaluation -> {
                assertThat(evaluation.getPurchaseOrderId()).isEqualTo(receivedPoId);
                assertThat(evaluation.getMaterialId()).isEqualTo(material.getId());
                assertThat(evaluation.getStockMovementId()).isNotNull();
                assertThat(evaluation.getEvaluationSource()).isEqualTo("GOODS_RECEIPT");
                assertThat(evaluation.getReceivedQuantity()).isEqualByComparingTo("10.0000");
                assertThat(evaluation.getQuantityScore()).isEqualByComparingTo("100.00");
                assertThat(evaluation.getTotalScore()).isGreaterThan(BigDecimal.valueOf(90));
            });
    }

    @Test
    void goodsProcurementClosesAfterSupplierCommitmentAsnAndGoodsReceipt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material material = material("MAT-P2P-E2E-" + suffix);
        Supplier supplier = supplier("SUP-P2P-E2E-" + suffix);
        Warehouse warehouse = warehouse("WH-P2P-E2E-" + suffix);
        StorageLocation storageLocation = storageLocation("LOC-P2P-E2E-" + suffix, warehouse.getId());

        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode("PR-P2P-E2E-" + suffix);
        pr.setName("End-to-end goods procurement");
        pr.setStatus(PurchaseRequestStatus.DRAFT.name());
        pr.setRequiredDate(LocalDate.now().plusDays(10));
        pr.setTotalAmount(BigDecimal.valueOf(1000));
        pr.setCurrencyCode("UZS");
        pr.setDemandPlanId(approvedDemandPlanId("DP-" + pr.getCode()));
        pr = purchaseRequestRepository.save(pr);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-P2P-E2E-" + suffix);
        item.setName("End-to-end bearing");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        purchaseRequestService.submit(pr.getId());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());

        RFQ rfq = seedRfq(pr);
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-P2P-E2E-" + suffix);
        quotation.setName("End-to-end quotation");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(950));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-P2P-E2E-" + suffix);
        quotationItem.setName("End-to-end quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setMaterialId(material.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(95));
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        po = purchaseOrderService.send(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());

        var dispatch = supplierPoDispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId()
        ).orElseThrow();
        var poItem = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), po.getId()
        ).getFirst();
        assertThat(poItem.getUnitPrice()).isEqualByComparingTo("95.0000");

        var response = supplierPoResponseService.startOrGetDraft(dispatch.getId());
        supplierPoResponseService.saveDraftLines(response.getId(), java.util.List.of(
            new LineDecisionCommand(
                poItem.getId(),
                "ACCEPTED",
                null,
                "Full quantity confirmed",
                java.util.List.of(new CommitmentCommand(BigDecimal.TEN, LocalDate.now().plusDays(3)))
            )
        ), "Confirmed");
        response = supplierPoResponseService.submit(response.getId());
        supplierPoBuyerDecisionService.accept(response.getId(), "Accepted by buyer");

        SupplierPoCommitment commitment = supplierPoCommitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId(), java.util.List.of("CONFIRMED")
            )
            .getFirst();
        var plannedSchedule = deliveryScheduleRepository
            .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), commitment.getId()
            )
            .orElseThrow();
        assertThat(plannedSchedule.getReceivedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

        AdvanceShippingNotice asn = supplierPoAsnService.createDraft(
            po.getId(),
            supplier.getId(),
            null,
            new SupplierPoAsnService.AsnDraftCommand(
                "ASN-P2P-E2E-" + suffix,
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now().plusDays(1),
                "ROAD",
                "Test carrier",
                "VEH-" + suffix,
                null,
                "TRK-" + suffix,
                java.util.List.of(new SupplierPoAsnService.AsnLineCommand(
                    commitment.getId(), BigDecimal.TEN, "BATCH-" + suffix, null
                ))
            )
        );
        asn = supplierPoAsnService.submit(po.getId(), asn.getId(), supplier.getId(), "ASN-SUBMIT-" + suffix);
        assertThat(supplierPoAsnService.submit(po.getId(), asn.getId(), supplier.getId(), "ASN-SUBMIT-" + suffix).getId())
            .isEqualTo(asn.getId());
        asn.setStatus("CONFIRMED");
        asn.setConfirmedAt(LocalDateTime.now());
        advanceShippingNoticeRepository.save(asn);

        po = purchaseOrderService.receive(po.getId(), new ReceivePoRequest(
            warehouse.getId(),
            null,
            asn.getId(),
            java.util.List.of(new ReceivePoLineRequest(
                poItem.getId(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null, "BATCH-" + suffix, storageLocation.getId(), "ACCEPTED"
            ))
        ));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED.name());
        assertThat(goodsReceiptLineRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId()
            )).singleElement().satisfies(line -> {
                assertThat(line.getAcceptedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
                assertThat(line.getQualityStatus()).isEqualTo("NOT_REQUIRED");
            });

        var fulfilledSchedule = deliveryScheduleRepository
            .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), commitment.getId()
            )
            .orElseThrow();
        assertThat(fulfilledSchedule.getReceivedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(fulfilledSchedule.getStatus()).isEqualTo("RECEIVED");

        po = purchaseOrderService.close(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED.name());
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "MM.INVOICE_REQUIRED".equals(event.getEventType()));
    }

    @Test
    void demandPlanGoodsProcurementReachesClosedPoAndIssuedInvoice() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material material = material("MAT-DP-P2P-" + suffix);
        Supplier supplier = supplier("SUP-DP-P2P-" + suffix);
        Warehouse warehouse = warehouse("WH-DP-P2P-" + suffix);
        StorageLocation storageLocation = storageLocation("LOC-DP-P2P-" + suffix, warehouse.getId());
        BudgetItem budgetItem = budgetItem("BI-DP-P2P-" + suffix, "0");
        Contract contract = contract("CON-DP-P2P-" + suffix, supplier.getId(), "2000");

        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setCode("DP-P2P-" + suffix);
        planRequest.setName("Demand originated goods procurement");
        planRequest.setPlanningCycleId(approvedDemandPlanningCycle("DPC-DP-P2P-" + suffix).getId());
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        planRequest.setDepartmentId(department("DEP-DP-P2P-" + suffix).getId());
        planRequest.setBudgetItemId(budgetItem.getId());
        planRequest.setOwnerUsername("planner");
        planRequest.setCurrencyCode("UZS");
        DemandPlanResponse plan = demandPlanService.create(planRequest);

        DemandPlanItemCreateRequest demandItem = new DemandPlanItemCreateRequest();
        demandItem.setDemandPlanId(plan.getId());
        demandItem.setName("Project " + material.getCode());
        demandItem.setMaterialId(material.getId());
        demandItem.setDemandType(DemandType.PROJECT.name());
        demandItem.setPriority(DemandPriority.HIGH.name());
        demandItem.setRequestedQuantity(BigDecimal.TEN);
        demandItem.setApprovedQuantity(BigDecimal.TEN);
        demandItem.setEstimatedUnitPrice(BigDecimal.valueOf(100));
        demandItem.setRequiredDate(LocalDate.now().plusDays(30));
        demandItem.setCurrencyCode("UZS");
        demandPlanItemService.create(demandItem);

        assertThat(demandPlanService.submit(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.SUBMITTED.name());
        approveAll(DemandPlanApprovalAdapter.MODULE_KEY, plan.getId());
        assertThat(demandPlanService.get(plan.getId()).getStatus()).isEqualTo(DemandPlanStatus.APPROVED.name());

        var prResult = demandPlanService.createPurchaseRequests(plan.getId());
        UUID purchaseRequestId = prResult.purchaseRequestIds().getFirst();
        assertThat(purchaseRequestRepository.findAnyById(purchaseRequestId).orElseThrow().getDemandPlanId())
            .isEqualTo(plan.getId());
        assertThat(purchaseRequestService.submit(purchaseRequestId).getStatus())
            .isEqualTo(PurchaseRequestStatus.APPROVAL_PENDING.name());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, purchaseRequestId);

        PurchaseRequest pr = purchaseRequestRepository.findAnyById(purchaseRequestId).orElseThrow();
        RFQ rfq = seedRfq(pr);
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-DP-P2P-" + suffix);
        quotation.setName("Demand originated quotation");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(950));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-DP-P2P-" + suffix);
        quotationItem.setName("Demand originated quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setMaterialId(material.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(95));
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        UUID poId = po.getId();
        PurchaseOrder linkedPo = purchaseOrderRepository.findAnyById(po.getId()).orElseThrow();
        linkedPo.setContractId(contract.getId());
        purchaseOrderRepository.save(linkedPo);

        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        po = purchaseOrderService.send(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());

        var dispatch = supplierPoDispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId()
        ).orElseThrow();
        var poItem = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), po.getId()
        ).getFirst();

        var response = supplierPoResponseService.startOrGetDraft(dispatch.getId());
        supplierPoResponseService.saveDraftLines(response.getId(), java.util.List.of(
            new LineDecisionCommand(
                poItem.getId(),
                "ACCEPTED",
                null,
                "Demand-originated quantity confirmed",
                java.util.List.of(new CommitmentCommand(BigDecimal.TEN, LocalDate.now().plusDays(3)))
            )
        ), "Confirmed");
        response = supplierPoResponseService.submit(response.getId());
        supplierPoBuyerDecisionService.accept(response.getId(), "Accepted by buyer");

        SupplierPoCommitment commitment = supplierPoCommitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId(), java.util.List.of("CONFIRMED")
            )
            .getFirst();
        AdvanceShippingNotice asn = supplierPoAsnService.createDraft(
            po.getId(),
            supplier.getId(),
            null,
            new SupplierPoAsnService.AsnDraftCommand(
                "ASN-DP-P2P-" + suffix,
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now().plusDays(1),
                "ROAD",
                "Test carrier",
                "VEH-" + suffix,
                null,
                "TRK-" + suffix,
                java.util.List.of(new SupplierPoAsnService.AsnLineCommand(
                    commitment.getId(), BigDecimal.TEN, "BATCH-" + suffix, null
                ))
            )
        );
        asn = supplierPoAsnService.submit(po.getId(), asn.getId(), supplier.getId(), "ASN-DP-SUBMIT-" + suffix);
        asn.setStatus("CONFIRMED");
        asn.setConfirmedAt(LocalDateTime.now());
        advanceShippingNoticeRepository.save(asn);

        po = purchaseOrderService.receive(po.getId(), new ReceivePoRequest(
            warehouse.getId(),
            null,
            asn.getId(),
            java.util.List.of(new ReceivePoLineRequest(
                poItem.getId(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null, "BATCH-" + suffix, storageLocation.getId(), "ACCEPTED"
            ))
        ));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED.name());

        var fulfilledSchedule = deliveryScheduleRepository
            .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), commitment.getId()
            )
            .orElseThrow();
        assertThat(fulfilledSchedule.getStatus()).isEqualTo("RECEIVED");
        assertThat(fulfilledSchedule.getReceivedQuantity()).isEqualByComparingTo(BigDecimal.TEN);

        po = purchaseOrderService.close(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED.name());
        assertThat(po.getId()).isEqualTo(poId);
        PurchaseOrder evidencePurchaseOrder = purchaseOrderRepository.findAnyById(poId).orElseThrow();
        assertThat(evidencePurchaseOrder.getRequestType()).isEqualTo(RequestType.GOODS);
        assertThat(purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), poId))
            .singleElement()
            .satisfies(item -> assertThat(item.getUnitPrice()).isEqualByComparingTo("95.0000"));
        assertThat(goodsReceiptLineRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), poId
            )).singleElement().satisfies(line -> {
                assertThat(line.getAcceptedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
                assertThat(line.getQualityStatus()).isEqualTo("NOT_REQUIRED");
            });
        assertThat(procurementInvoiceEvidenceService
            .summarize(evidencePurchaseOrder))
            .satisfies(summary -> {
                assertThat(summary.evidenceAvailable()).isTrue();
                assertThat(summary.evidenceAmount()).isEqualByComparingTo("950.0000");
            });

        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(contract.getId());
        invoiceRequest.setPurchaseOrderId(po.getId());
        invoiceRequest.setInvoiceNumber("INV-DP-P2P-" + suffix);
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(BigDecimal.valueOf(950));
        invoiceRequest.setCurrencyCode("UZS");
        var issuedInvoice = contractInvoiceService.issue(contractInvoiceService.create(invoiceRequest).getId());
        assertThat(issuedInvoice.getStatus()).isEqualTo(ContractInvoiceStatus.ISSUED.name());

        ContractPaymentCreateRequest paymentRequest = new ContractPaymentCreateRequest();
        paymentRequest.setContractInvoiceId(issuedInvoice.getId());
        paymentRequest.setContractId(contract.getId());
        paymentRequest.setPurchaseOrderId(po.getId());
        paymentRequest.setAmount(BigDecimal.valueOf(950));
        paymentRequest.setCurrencyCode("UZS");
        paymentRequest.setPaymentNumber("PAY-DP-P2P-" + suffix);
        var payment = contractPaymentService.create(paymentRequest);
        contractPaymentService.submitToTreasury(
            payment.getId(),
            new PaymentSubmitRequest("PAY-DP-P2P-" + suffix, "RFQ goods payment")
        );
        seedTreasuryAck(payment.getId(), "PAY-DP-P2P-" + suffix);
        contractPaymentService.markPaid(
            payment.getId(),
            new PaymentMarkPaidRequest("PAY-DP-P2P-" + suffix, null)
        );

        ContractPayment storedPayment = contractPaymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(storedPayment.getStatus()).isEqualTo(ContractPaymentStatus.PAID.name());
        assertThat(contractInvoiceService.get(issuedInvoice.getId()).getStatus())
            .isEqualTo(ContractInvoiceStatus.PAID.name());
    }

    @Test
    void directProcurementMethodsProduceTraceablePurchaseOrders() {
        for (String method : java.util.List.of("DIRECT_PURCHASE", "SINGLE_SOURCE")) {
            String suffix = method + "-" + UUID.randomUUID().toString().substring(0, 8);
            Material material = material("MAT-" + suffix);
            Supplier supplier = supplier("SUP-" + suffix);

            PurchaseRequestCreateRequest purchaseRequest = new PurchaseRequestCreateRequest();
            purchaseRequest.setCode("PR-" + suffix);
            purchaseRequest.setName(method + " purchase request");
            purchaseRequest.setCurrencyCode("UZS");
            purchaseRequest.setDemandPlanId(approvedDemandPlanId("DP-" + suffix));
            purchaseRequest.setItems(java.util.List.of(purchaseRequestItem(material.getId(), 4, 125)));
            var createdPurchaseRequest = purchaseRequestService.create(purchaseRequest);
            purchaseRequestService.submit(createdPurchaseRequest.getId());
            approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, createdPurchaseRequest.getId());

            Attachment marketResearch = procurementEvidence(
                "PMD-MARKET-RESEARCH",
                createdPurchaseRequest.getId(),
                "market-research.pdf"
            );
            Attachment technicalSpecification = procurementEvidence(
                "PMD-TECHNICAL-SPECIFICATION",
                createdPurchaseRequest.getId(),
                "technical-specification.pdf"
            );
            ProcurementMethodDecisionCreateRequest decisionRequest = new ProcurementMethodDecisionCreateRequest();
            decisionRequest.setName(method + " method decision");
            decisionRequest.setPurchaseRequestId(createdPurchaseRequest.getId());
            decisionRequest.setSelectedMethod(method);
            decisionRequest.setJustification("Approved exception procurement route for the requested material.");
            decisionRequest.setEstimatedAmount(BigDecimal.valueOf(500));
            decisionRequest.setCurrencyCode("UZS");
            decisionRequest.setPotentialSupplierCount(1);
            decisionRequest.setExceptionReason("Only the selected supplier can satisfy the documented operational need.");
            decisionRequest.setMarketResearchAttachmentId(marketResearch.getId());
            decisionRequest.setTechnicalSpecificationAttachmentId(technicalSpecification.getId());

            var decision = procurementMethodDecisionService.create(decisionRequest);
            procurementMethodDecisionService.submit(decision.getId());
            approveAll(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY, decision.getId());

            var procedure = procurementProcedureRepository
                .findByMethodDecisionIdAndIsDeletedFalse(decision.getId())
                .orElseThrow();
            procurementProcedureService.ready(procedure.getId());
            assertThat(procurementProcedureService.launch(procedure.getId()).getStatus())
                .isEqualTo("DIRECT_AWARD_DRAFT");

            var award = procurementDirectAwardService.create(
                procedure.getId(),
                new DirectAwardRequest(
                    supplier.getId(),
                    BigDecimal.valueOf(480),
                    "UZS",
                    "Award supported by the approved procurement method exception.",
                    "DIRECT_PURCHASE".equals(method) ? "MARKET_RESEARCH" : "APPROVED_EXCEPTION",
                    decision.getDecisionNumber(),
                    "Approved procurement method decision documents why this supplier is the only acceptable source.",
                    null,
                    marketResearch.getId().toString(),
                    BigDecimal.valueOf(500),
                    marketResearch.getId().toString(),
                    "SINGLE_SOURCE".equals(method)
                        ? "Supplier accepted the negotiated price and delivery obligations documented by procurement."
                        : null,
                    "No buyer or approver conflict exists for this supplier selection.",
                    true
                )
            );
            procurementDirectAwardService.submit(award.id());
            approveAll(
                "SINGLE_SOURCE".equals(method)
                    ? ProcurementDirectAwardApprovalAdapter.SINGLE_SOURCE_MODULE_KEY
                    : ProcurementDirectAwardApprovalAdapter.MODULE_KEY,
                award.id()
            );
            award = procurementDirectAwardService.get(award.id());
            assertThat(award.status()).isEqualTo("APPROVED");

            PurchaseOrderResponse purchaseOrder = procurementDirectAwardService.createPurchaseOrder(
                award.id(),
                new DirectAwardPurchaseOrderRequest(LocalDate.now(), LocalDate.now().plusDays(10), null)
            );

            assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT.name());
            assertThat(purchaseOrder.getPurchaseRequestId()).isEqualTo(createdPurchaseRequest.getId());
            assertThat(purchaseOrder.getSupplierId()).isEqualTo(supplier.getId());
            assertThat(purchaseOrder.getTotalAmount()).isEqualByComparingTo("480");
            var purchaseOrderLines = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                purchaseOrder.getId()
            );
            assertThat(purchaseOrderLines).singleElement().satisfies(line -> {
                assertThat(line.getMaterialId()).isEqualTo(material.getId());
                assertThat(line.getQuantity()).isEqualByComparingTo("4");
                assertThat(line.getUnitPrice()).isEqualByComparingTo("120");
            });
            var sourceItem = purchaseRequestItemRepository
                .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    createdPurchaseRequest.getId()
                )
                .getFirst();
            assertThat(purchaseOrderSourceAllocationRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    purchaseOrderLines.getFirst().getId()
                )).singleElement().satisfies(allocation -> {
                    assertThat(allocation.getPurchaseRequestItemId()).isEqualTo(sourceItem.getId());
                    assertThat(allocation.getOrderedQuantity()).isEqualByComparingTo("4");
                });
            assertThat(procurementProcedureService.get(procedure.getId()).getStatus()).isEqualTo("COMPLETED");
            assertThat(procurementDirectAwardService.createPurchaseOrder(award.id(), null).getId())
                .isEqualTo(purchaseOrder.getId());
        }
    }

    @Test
    void auctionRemainsDisabledAndFrameworkMethodProducesTraceablePurchaseOrders() {
        String auctionSuffix = "E_AUCTION-" + UUID.randomUUID().toString().substring(0, 8);
        Material auctionMaterial = material("MAT-" + auctionSuffix);
        assertThatThrownBy(() -> approvedMethodProcedure("E_AUCTION", auctionMaterial, auctionSuffix))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("procurement.method_capability.not_configured");

        String frameworkSuffix = "FRAME_AGREEMENT-" + UUID.randomUUID().toString().substring(0, 8);
        Material frameworkMaterial = material("MAT-" + frameworkSuffix);
        Supplier frameworkSupplier = supplier("SUP-" + frameworkSuffix);
        ProcurementProcedure framework = approvedMethodProcedure(
            "FRAME_AGREEMENT", frameworkMaterial, frameworkSuffix);

        procurementProcedureService.ready(framework.getId());
        assertThat(procurementProcedureService.launch(framework.getId()).getStatus())
            .isEqualTo("FRAMEWORK_DRAFT");
        assertThatThrownBy(() -> procurementProcedureExecutionService.awardFramework(
            framework.getId(),
            new FrameworkAwardRequest(
                frameworkSupplier.getId(),
                BigDecimal.valueOf(-1),
                "UZS",
                "FA-" + frameworkSuffix,
                "FRAMEWORK-AWARD-" + frameworkSuffix,
                LocalDate.now(),
                LocalDate.now().plusYears(1)
            )
        )).hasMessageContaining("ceilingAmount must be greater than zero");
        var awardedFramework = procurementProcedureExecutionService.awardFramework(
            framework.getId(),
            new FrameworkAwardRequest(
                frameworkSupplier.getId(),
                BigDecimal.valueOf(2000),
                "UZS",
                "FA-" + frameworkSuffix,
                "FRAMEWORK-AWARD-" + frameworkSuffix,
                LocalDate.now(),
                LocalDate.now().plusYears(1)
            )
        );
        assertThat(awardedFramework.getStatus()).isEqualTo("FRAMEWORK_DRAFT");
        assertThat(awardedFramework.getContractId()).isNotNull();
        var frameworkRecord = procurementFrameworkService.getByProcedure(framework.getId());
        var submittedFramework = procurementFrameworkService.submit(frameworkRecord.id());
        approveAll(ProcurementFrameworkApprovalAdapter.MODULE_KEY, submittedFramework.id());
        assertThat(procurementProcedureRepository.findAnyById(framework.getId()).orElseThrow().getStatus())
            .isEqualTo("FRAMEWORK_AWARDED");
        assertThat(procurementProcedureExecutionService.activateFramework(framework.getId()).getStatus())
            .isEqualTo("FRAMEWORK_ACTIVE");
        assertThatThrownBy(() -> procurementProcedureExecutionService.createFrameworkCallOff(
            framework.getId(),
            new FrameworkCallOffRequest(
                BigDecimal.valueOf(2500),
                LocalDate.now(),
                LocalDate.now().plusDays(14)
            )
        )).hasMessageContaining("Framework ceiling exceeded");

        PurchaseOrderResponse frameworkPo = procurementProcedureExecutionService.createFrameworkCallOff(
            framework.getId(),
            new FrameworkCallOffRequest(
                BigDecimal.valueOf(475),
                LocalDate.now(),
                LocalDate.now().plusDays(14)
            )
        );
        ProcurementProcedure activeFramework = procurementProcedureRepository.findAnyById(framework.getId()).orElseThrow();
        assertThat(activeFramework.getStatus()).isEqualTo("FRAMEWORK_ACTIVE");
        assertThat(activeFramework.getAwardedSupplierId()).isEqualTo(frameworkSupplier.getId());
        assertThat(activeFramework.getContractId()).isEqualTo(awardedFramework.getContractId());
        assertThat(frameworkPo.getSupplierId()).isEqualTo(frameworkSupplier.getId());
        assertThat(frameworkPo.getPurchaseRequestId()).isEqualTo(framework.getPurchaseRequestId());
        assertThat(frameworkPo.getContractId()).isEqualTo(awardedFramework.getContractId());
        assertThat(frameworkPo.getTotalAmount()).isEqualByComparingTo("475");
        assertThat(procurementProcedureExecutionService.createFrameworkCallOff(
            framework.getId(),
            new FrameworkCallOffRequest(
                BigDecimal.valueOf(475),
                LocalDate.now(),
                LocalDate.now().plusDays(14)
            )
        ).getId()).isEqualTo(frameworkPo.getId());
    }

    @Test
    void rfqExecutionMethodsProduceGovernedTraceablePurchaseOrders() {
        for (String method : java.util.List.of(
            "REQUEST_FOR_QUOTATION",
            "OPEN_COMPETITION",
            "CLOSED_COMPETITION"
        )) {
            String suffix = method + "-" + UUID.randomUUID().toString().substring(0, 8);
            Material material = material("MAT-" + suffix);
            Supplier winner = supplier("SUP-WIN-" + suffix);
            Supplier runnerUp = supplier("SUP-RUN-" + suffix);
            Supplier third = supplier("SUP-3-" + suffix);
            ProcurementProcedure procedure = approvedMethodProcedure(method, material, suffix);
            var campaign = launchCampaign(procedure, suffix);
            var sourcedCampaign = procurementCampaignService.createRfq(campaign.getId());
            RFQ rfq = rfqRepository.findAnyById(sourcedCampaign.getRfqId()).orElseThrow();
            var rfqItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), rfq.getId());

            assertThat(rfqItems).hasSize(1);
            assertThat(rfqPurchaseRequestSourceRepository
                .findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    rfq.getId(),
                    "ACTIVE"
                )).hasSize(1);

            SupplierQuotation winningQuote = governedQuotation(
                rfq, rfqItems, winner, suffix + "-WIN", "480", "95");
            SupplierQuotation otherQuote = governedQuotation(
                rfq, rfqItems, runnerUp, suffix + "-RUN", "500", "80");
            SupplierQuotation thirdQuote = governedQuotation(
                rfq, rfqItems, third, suffix + "-3", "490", "70");
            approveTenderGovernance(
                rfq,
                java.util.List.of(winningQuote, otherQuote, thirdQuote),
                suffix,
                winningQuote
            );

            PurchaseOrderResponse purchaseOrder = supplierQuotationService.selectAndCreatePo(
                winningQuote.getId(),
                "Highest evaluated compliant quotation"
            );

            assertAwardedProcedure(procedure, purchaseOrder, winner, null, "480");
            assertThat(procurementCampaignRepository.findAnyById(campaign.getId()).orElseThrow().getStatus())
                .isEqualTo("COMPLETED");
            assertThat(purchaseRequestRepository.findAnyById(procedure.getPurchaseRequestId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseRequestStatus.PO_CREATED.name());
            assertPoHasSourceLineage(purchaseOrder);
        }
    }

    @Test
    void tenderMethodsUseFrozenPriceDiscoveryAndProduceTraceablePurchaseOrders() {
        for (String method : java.util.List.of("OPEN_TENDER", "CLOSED_TENDER")) {
            String suffix = method + "-" + UUID.randomUUID().toString().substring(0, 8);
            Material material = material("MAT-" + suffix);
            Supplier winner = supplier("SUP-WIN-" + suffix);
            Supplier runnerUp = supplier("SUP-RUN-" + suffix);
            Supplier third = supplier("SUP-3-" + suffix);
            ProcurementProcedure procedure = approvedMethodProcedure(method, material, suffix);
            var campaign = launchCampaign(procedure, suffix);

            var discovery = priceDiscoveryService.createPriceDiscoveryRfq(
                campaign.getId(),
                new CreatePriceDiscoveryRfqRequest(
                    LocalDate.now().plusDays(7),
                    "buyer",
                    "Submit commercial price evidence",
                    "en",
                    java.util.List.of(),
                    null
                )
            );
            RFQ discoveryRfq = rfqRepository.findAnyById(discovery.getId()).orElseThrow();
            var discoveryItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), discovery.getId());
            governedQuotation(discoveryRfq, discoveryItems, winner, suffix + "-PD-WIN", "470", "90");
            governedQuotation(discoveryRfq, discoveryItems, runnerUp, suffix + "-PD-RUN", "490", "80");

            var priceBasis = priceDiscoveryService.finalizePriceDiscovery(
                discovery.getId(),
                new FinalizePriceDiscoveryRequest(discovery.getVersion()),
                "PRICE-DISCOVERY-" + suffix
            );
            assertThat(priceBasis.quotationCount()).isEqualTo(2);
            assertThat(priceBasis.amount()).isEqualByComparingTo("480");

            var tender = priceDiscoveryService.createTender(
                campaign.getId(),
                new CreateTenderRequest(
                    LocalDate.now().plusDays(14),
                    "buyer",
                    "Submit final tender proposal",
                    "en",
                    java.util.List.of(),
                    null
                )
            );
            RFQ tenderRfq = rfqRepository.findAnyById(tender.rfqId()).orElseThrow();
            var tenderItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), tender.rfqId());
            SupplierQuotation winningQuote = governedQuotation(
                tenderRfq, tenderItems, winner, suffix + "-TENDER-WIN", "465", "96");
            SupplierQuotation otherQuote = governedQuotation(
                tenderRfq, tenderItems, runnerUp, suffix + "-TENDER-RUN", "475", "82");
            SupplierQuotation thirdQuote = governedQuotation(
                tenderRfq, tenderItems, third, suffix + "-TENDER-3", "478", "74");
            approveTenderGovernance(
                tenderRfq,
                java.util.List.of(winningQuote, otherQuote, thirdQuote),
                suffix + "-TENDER",
                winningQuote
            );

            PurchaseOrderResponse purchaseOrder = supplierQuotationService.selectAndCreatePo(
                winningQuote.getId(),
                "Approved tender evaluation recommendation"
            );

            assertAwardedProcedure(procedure, purchaseOrder, winner, null, "465");
            ProcurementProcedure completed = procurementProcedureRepository
                .findAnyById(procedure.getId())
                .orElseThrow();
            assertThat(completed.getPriceBasisCalculationId()).isEqualTo(priceBasis.calculationId());
            assertThat(completed.getPriceDiscoveryRfqId()).isEqualTo(discovery.getId());
            assertPoHasSourceLineage(purchaseOrder);
        }
    }

    @Test
    void rfqGovernedGoodsProcurementReachesPaidInvoiceAfterReceipt() {
        String suffix = "RFQ-PAY-" + UUID.randomUUID().toString().substring(0, 8);
        Material material = material("MAT-" + suffix);
        Supplier winner = supplier("SUP-WIN-" + suffix);
        Supplier runnerUp = supplier("SUP-RUN-" + suffix);
        Supplier third = supplier("SUP-3-" + suffix);
        Warehouse warehouse = warehouse("WH-" + suffix);
        Contract contract = contract("CON-" + suffix, winner.getId(), "2000");
        BigDecimal payableAmount = BigDecimal.valueOf(480);

        ProcurementProcedure procedure = approvedMethodProcedure("REQUEST_FOR_QUOTATION", material, suffix);
        var campaign = launchCampaign(procedure, suffix);
        var sourcedCampaign = procurementCampaignService.createRfq(campaign.getId());
        RFQ rfq = rfqRepository.findAnyById(sourcedCampaign.getRfqId()).orElseThrow();
        var rfqItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), rfq.getId());
        SupplierQuotation winningQuote = governedQuotation(
            rfq, rfqItems, winner, suffix + "-WIN", "480", "95");
        SupplierQuotation otherQuote = governedQuotation(
            rfq, rfqItems, runnerUp, suffix + "-RUN", "500", "80");
        SupplierQuotation thirdQuote = governedQuotation(
            rfq, rfqItems, third, suffix + "-3", "490", "70");
        approveTenderGovernance(
            rfq, java.util.List.of(winningQuote, otherQuote, thirdQuote), suffix, winningQuote);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            winningQuote.getId(),
            "Highest evaluated compliant quotation"
        );
        linkPurchaseOrderToContract(po.getId(), contract.getId());
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        po = purchaseOrderService.send(po.getId());

        po = receiveSentGoodsPoThroughSupplierPortal(po, winner, warehouse, suffix);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED.name());
        assertThat(procurementInvoiceEvidenceService.summarize(
            purchaseOrderRepository.findAnyById(po.getId()).orElseThrow()
        ).evidenceAvailable()).isTrue();

        var paidInvoice = issueInvoiceAndCompleteGoodsPayment(
            contract.getId(), po.getId(), payableAmount, suffix);
        assertThat(paidInvoice.getStatus()).isEqualTo(ContractInvoiceStatus.PAID.name());
        assertThat(paidInvoice.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.CONTRACT_CALLOFF);
        assertThat(contractPaymentRepository.findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), paidInvoice.getId()
        )).singleElement().satisfies(payment ->
            assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.PAID.name()));
    }

    @Test
    void openTenderGoodsProcurementReachesPaidInvoiceAfterReceipt() {
        String suffix = "OT-PAY-" + UUID.randomUUID().toString().substring(0, 8);
        Material material = material("MAT-" + suffix);
        Supplier winner = supplier("SUP-WIN-" + suffix);
        Supplier runnerUp = supplier("SUP-RUN-" + suffix);
        Supplier third = supplier("SUP-3-" + suffix);
        Warehouse warehouse = warehouse("WH-" + suffix);
        Contract contract = contract("CON-" + suffix, winner.getId(), "2000");
        BigDecimal payableAmount = BigDecimal.valueOf(465);

        ProcurementProcedure procedure = approvedMethodProcedure("OPEN_TENDER", material, suffix);
        var campaign = launchCampaign(procedure, suffix);
        var discovery = priceDiscoveryService.createPriceDiscoveryRfq(
            campaign.getId(),
            new CreatePriceDiscoveryRfqRequest(
                LocalDate.now().plusDays(7),
                "buyer",
                "Submit commercial price evidence",
                "en",
                java.util.List.of(),
                null
            )
        );
        RFQ discoveryRfq = rfqRepository.findAnyById(discovery.getId()).orElseThrow();
        var discoveryItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), discovery.getId());
        governedQuotation(discoveryRfq, discoveryItems, winner, suffix + "-PD-WIN", "470", "90");
        governedQuotation(discoveryRfq, discoveryItems, runnerUp, suffix + "-PD-RUN", "490", "80");
        governedQuotation(discoveryRfq, discoveryItems, third, suffix + "-PD-3", "485", "75");
        priceDiscoveryService.finalizePriceDiscovery(
            discovery.getId(),
            new FinalizePriceDiscoveryRequest(discovery.getVersion()),
            "PRICE-DISCOVERY-" + suffix
        );

        var tender = priceDiscoveryService.createTender(
            campaign.getId(),
            new CreateTenderRequest(
                LocalDate.now().plusDays(14),
                "buyer",
                "Submit final tender proposal",
                "en",
                java.util.List.of(),
                null
            )
        );
        RFQ tenderRfq = rfqRepository.findAnyById(tender.rfqId()).orElseThrow();
        var tenderItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), tender.rfqId());
        SupplierQuotation winningQuote = governedQuotation(
            tenderRfq, tenderItems, winner, suffix + "-TENDER-WIN", "465", "96");
        SupplierQuotation otherQuote = governedQuotation(
            tenderRfq, tenderItems, runnerUp, suffix + "-TENDER-RUN", "475", "82");
        SupplierQuotation thirdQuote = governedQuotation(
            tenderRfq, tenderItems, third, suffix + "-TENDER-3", "478", "74");
        approveTenderGovernance(
            tenderRfq,
            java.util.List.of(winningQuote, otherQuote, thirdQuote),
            suffix + "-TENDER",
            winningQuote);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            winningQuote.getId(),
            "Approved tender evaluation recommendation"
        );
        linkPurchaseOrderToContract(po.getId(), contract.getId());
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        po = purchaseOrderService.send(po.getId());

        po = receiveSentGoodsPoThroughSupplierPortal(po, winner, warehouse, suffix);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED.name());

        var paidInvoice = issueInvoiceAndCompleteGoodsPayment(
            contract.getId(), po.getId(), payableAmount, suffix);
        assertThat(paidInvoice.getStatus()).isEqualTo(ContractInvoiceStatus.PAID.name());
        assertThat(paidInvoice.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.CONTRACT_CALLOFF);
    }

    @Test
    void purchaseOrderReceivePersistsStorageLocationOnStockMovement() {
        Material material = material("MAT-P2P-LOC-1");
        Supplier supplier = supplier("SUP-P2P-LOC-1");
        Warehouse warehouse = warehouse("WH-P2P-LOC-1");
        StorageLocation storageLocation = storageLocation("LOC-P2P-1", warehouse.getId());
        StorageBin storageBin = storageBin("BIN-P2P-1", warehouse.getId(), storageLocation.getId());

        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode("PR-P2P-LOC-1");
        pr.setName("Storage location flow");
        pr.setStatus(PurchaseRequestStatus.DRAFT.name());
        pr.setRequiredDate(LocalDate.now().plusDays(10));
        pr.setTotalAmount(BigDecimal.valueOf(1000));
        pr.setCurrencyCode("UZS");
        pr.setDemandPlanId(approvedDemandPlanId("DP-" + pr.getCode()));
        pr = purchaseRequestRepository.save(pr);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-P2P-LOC-1");
        item.setName("Pump bearing");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        purchaseRequestService.submit(pr.getId());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());

        RFQ rfq = seedRfq(pr);
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-P2P-LOC-" + "A".repeat(50));
        quotation.setName("Quotation A");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(950));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-P2P-LOC-" + "B".repeat(49));
        quotationItem.setName("Quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setMaterialId(material.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(95));
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        purchaseOrderService.send(po.getId());
        AdvanceShippingNotice asn = confirmedPortalAsn(po, supplier, "P2P-LOC-1", BigDecimal.TEN);
        purchaseOrderService.receive(po.getId(), new ReceivePoRequest(
            warehouse.getId(),
            storageLocation.getId(),
            asn.getId(),
            java.util.List.of(new ReceivePoLineRequest(
                purchaseOrderItemRepository
                    .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
                    .getFirst()
                    .getId(),
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "BATCH-P2P-LOC-1",
                storageLocation.getId(),
                "ACCEPTED",
                storageBin.getId()
            )),
            storageBin.getId()
        ));

        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            "PurchaseOrder",
            po.getId()
        )).singleElement().satisfies(movement -> {
            assertThat(movement.getCode().length()).isLessThanOrEqualTo(80);
            assertThat(movement.getWarehouseId()).isEqualTo(warehouse.getId());
            assertThat(movement.getStorageLocationId()).isEqualTo(storageLocation.getId());
            assertThat(movement.getStorageBinId()).isEqualTo(storageBin.getId());
        });

        assertThat(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            material.getId(),
            warehouse.getId()
        )).singleElement().satisfies(balance -> {
            assertThat(balance.getStorageLocationId()).isEqualTo(storageLocation.getId());
            assertThat(balance.getStorageBinId()).isEqualTo(storageBin.getId());
            assertThat(balance.getQuantity()).isEqualByComparingTo("10.0000");
        });
    }

    @Test
    void purchaseOrderReceiveRejectsStorageBinOutsideReceiptLocation() {
        Material material = material("MAT-P2P-BIN-NEG-1");
        Supplier supplier = supplier("SUP-P2P-BIN-NEG-1");
        Warehouse warehouse = warehouse("WH-P2P-BIN-NEG-1");
        StorageLocation receiptLocation = storageLocation("LOC-P2P-BIN-NEG-1", warehouse.getId());
        StorageLocation otherLocation = storageLocation("LOC-P2P-BIN-NEG-2", warehouse.getId());
        StorageBin otherLocationBin = storageBin("BIN-P2P-BIN-NEG-1", warehouse.getId(), otherLocation.getId());

        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode("PR-P2P-BIN-NEG-1");
        pr.setName("Storage bin validation flow");
        pr.setStatus(PurchaseRequestStatus.DRAFT.name());
        pr.setRequiredDate(LocalDate.now().plusDays(10));
        pr.setTotalAmount(BigDecimal.valueOf(1000));
        pr.setCurrencyCode("UZS");
        pr.setDemandPlanId(approvedDemandPlanId("DP-" + pr.getCode()));
        pr = purchaseRequestRepository.save(pr);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-P2P-BIN-NEG-1");
        item.setName("Pump bearing");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        purchaseRequestService.submit(pr.getId());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());

        RFQ rfq = seedRfq(pr);
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-P2P-BIN-NEG-1");
        quotation.setName("Quotation A");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(950));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-P2P-BIN-NEG-1");
        quotationItem.setName("Quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setMaterialId(material.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(95));
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        purchaseOrderService.send(po.getId());

        UUID poId = po.getId();
        assertThatThrownBy(() -> purchaseOrderService.receive(poId, new ReceivePoRequest(
            warehouse.getId(),
            receiptLocation.getId(),
            null,
            java.util.List.of(),
            otherLocationBin.getId()
        )))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessage("Storage bin must belong to receipt storage location");
    }

    @Test
    void purchaseOrderReceiveProcessesLineQuantitiesAndExceptions() {
        Material material = material("MAT-P2P-LINE-1");
        Supplier supplier = supplier("SUP-P2P-LINE-1");
        Warehouse warehouse = warehouse("WH-P2P-LINE-1");
        StorageLocation storageLocation = storageLocation("LOC-P2P-LINE-1", warehouse.getId());

        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode("PR-P2P-LINE-1");
        pr.setName("Line receiving flow");
        pr.setStatus(PurchaseRequestStatus.DRAFT.name());
        pr.setRequiredDate(LocalDate.now().plusDays(10));
        pr.setTotalAmount(BigDecimal.valueOf(1000));
        pr.setCurrencyCode("UZS");
        pr.setDemandPlanId(approvedDemandPlanId("DP-" + pr.getCode()));
        pr = purchaseRequestRepository.save(pr);

        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-P2P-LINE-1");
        item.setName("Pump bearing");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        purchaseRequestService.submit(pr.getId());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());

        RFQ rfq = seedRfq(pr);
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-P2P-LINE-1");
        quotation.setName("Quotation A");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(950));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-P2P-LINE-1");
        quotationItem.setName("Quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setMaterialId(material.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(95));
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        po = purchaseOrderService.send(po.getId());
        var poItem = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            po.getId()
        ).getFirst();
        AdvanceShippingNotice asn = confirmedPortalAsn(po, supplier, "P2P-LINE-1", BigDecimal.TEN);
        var receiptEvidence = attachmentService.saveBytes(
            "PurchaseOrder",
            po.getId(),
            "RECEIPT_EVIDENCE",
            "delivery-exception.txt",
            "text/plain",
            "Supplier shortage and damage evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        po = purchaseOrderService.receive(po.getId(), new ReceivePoRequest(
            warehouse.getId(),
            null,
            asn.getId(),
            java.util.List.of(new ReceivePoLineRequest(
                poItem.getId(),
                BigDecimal.valueOf(4),
                BigDecimal.ONE,
                BigDecimal.valueOf(2),
                "Short shipped by supplier",
                "LOT-P2P-LINE-1",
                storageLocation.getId(),
                "HOLD"
            )),
            null,
            po.getVersion(),
            java.util.List.of(receiptEvidence.getId())
        ));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        var updatedItem = purchaseOrderItemRepository.findByIdAndIsDeletedFalse(poItem.getId()).orElseThrow();
        assertThat(updatedItem.getReceivedQuantity()).isEqualByComparingTo("4.0000");
        assertThat(updatedItem.getRejectedQuantity()).isEqualByComparingTo("1.0000");
        assertThat(updatedItem.getDamagedQuantity()).isEqualByComparingTo("2.0000");
        assertThat(updatedItem.getShortageReason()).isEqualTo("Short shipped by supplier");
        assertThat(updatedItem.getReceiptBatchNumber()).isEqualTo("LOT-P2P-LINE-1");
        assertThat(updatedItem.getQualityStatus()).isEqualTo("PENDING_INSPECTION");
        assertThat(updatedItem.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());

        assertThat(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            material.getId(),
            warehouse.getId()
        )).isEmpty();

        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            "PurchaseOrder",
            po.getId()
        )).isEmpty();
    }

    @Test
    void worksPurchaseOrderRequiresContractAndRejectsWarehouseReceiptAndPrematureClose() {
        Supplier supplier = supplier("SUP-WORKS-P2P-" + UUID.randomUUID().toString().substring(0, 8));
        Warehouse warehouse = warehouse("WH-WORKS-P2P-" + UUID.randomUUID().toString().substring(0, 8));

        PurchaseOrder po = new PurchaseOrder();
        po.setCode("PO-WORKS-P2P-" + UUID.randomUUID().toString().substring(0, 8));
        po.setName("Works execution PO");
        po.setStatus(PurchaseOrderStatus.APPROVED.name());
        po.setSupplierId(supplier.getId());
        po.setRequestType(RequestType.SERVICE);
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(7));
        po.setTotalAmount(BigDecimal.valueOf(500));
        po.setCurrencyCode("UZS");
        po = purchaseOrderRepository.save(po);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-WORKS-P2P-" + UUID.randomUUID().toString().substring(0, 8));
        item.setName("Works execution line");
        item.setStatus("OPEN");
        item.setPurchaseOrderId(po.getId());
        item.setLineType(ProcurementLineType.WORK);
        item.setMaterialId(null);
        item.setServiceDescription("Install transformer foundation");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.valueOf(500));
        purchaseOrderItemRepository.save(item);

        UUID poId = po.getId();
        assertThatThrownBy(() -> purchaseOrderService.send(poId))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("WORKS purchase order requires an ACTIVE contract before sending");

        Contract draftContract = draftContract("CON-WORKS-DRAFT-" + UUID.randomUUID().toString().substring(0, 8), supplier.getId(), "500");
        po.setContractId(draftContract.getId());
        po = purchaseOrderRepository.save(po);
        assertThatThrownBy(() -> purchaseOrderService.send(poId))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("WORKS purchase order requires an ACTIVE contract before sending");

        Supplier otherSupplier = supplier("SUP-WORKS-OTHER-" + UUID.randomUUID().toString().substring(0, 8));
        Contract supplierMismatchContract = contract("CON-WORKS-SUP-" + UUID.randomUUID().toString().substring(0, 8), otherSupplier.getId(), "500");
        po.setContractId(supplierMismatchContract.getId());
        po = purchaseOrderRepository.save(po);
        assertThatThrownBy(() -> purchaseOrderService.send(poId))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("WORKS contract supplier must match purchase order supplier");

        Contract currencyMismatchContract = contract("CON-WORKS-CUR-" + UUID.randomUUID().toString().substring(0, 8), supplier.getId(), "500");
        currencyMismatchContract.setCurrencyCode("USD");
        currencyMismatchContract = contractRepository.save(currencyMismatchContract);
        po.setContractId(currencyMismatchContract.getId());
        po = purchaseOrderRepository.save(po);
        assertThatThrownBy(() -> purchaseOrderService.send(poId))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessageContaining("WORKS contract currency must match purchase order currency");

        Contract contract = contract("CON-WORKS-P2P-" + UUID.randomUUID().toString().substring(0, 8), supplier.getId(), "500");
        po.setContractId(contract.getId());
        purchaseOrderRepository.save(po);

        PurchaseOrderResponse sent = purchaseOrderService.send(poId);
        assertThat(sent.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());
        assertThat(contractRepository.findAnyById(contract.getId()).orElseThrow().getUsedAmount()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> purchaseOrderService.receive(poId, warehouse.getId(), null))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessage("Warehouse receipt is only allowed for GOODS purchase orders");
        assertThat(stockMovementRepository.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            "PurchaseOrder",
            poId
        )).isEmpty();

        assertThatThrownBy(() -> purchaseOrderService.close(poId))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessage("WORKS purchase order must be COMPLETED before close");
    }

    @Test
    void serviceProcurementFlowClosesPoUsingApprovedServiceActAndConsumesBudgetOnce() {
        Supplier supplier = supplier("SUP-SVC-1");
        BudgetItem budgetItem = budgetItem("BUD-SVC-1", "1000");
        Contract contract = contract("CON-SVC-1", supplier.getId(), "1000");

        String serviceSuffix = UUID.randomUUID().toString().substring(0, 8);
        PurchaseRequestCreateRequest request = new PurchaseRequestCreateRequest();
        request.setCode("PR-SVC-" + serviceSuffix);
        request.setName("Compressor maintenance");
        request.setCurrencyCode("UZS");
        request.setRequestType(RequestType.SERVICE.name());
        request.setDemandPlanId(approvedDemandPlanId("DP-SVC-" + serviceSuffix, RequestType.SERVICE));
        request.setItems(java.util.List.of(servicePurchaseRequestItem(
            "Quarterly compressor maintenance",
            "MAINTENANCE",
            budgetItem.getId(),
            10,
            50
        )));

        var createdPr = purchaseRequestService.create(request);
        purchaseRequestService.submit(createdPr.getId());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, createdPr.getId());

        BudgetItem reservedBudget = budgetItemRepository.findAnyById(budgetItem.getId()).orElseThrow();
        assertThat(reservedBudget.getReservedAmount()).isEqualByComparingTo("500");

        RFQ rfq = seedRfq(purchaseRequestRepository.findAnyById(createdPr.getId()).orElseThrow());
        var rfqItem = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfq.getId()).getFirst();

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-SVC-1");
        quotation.setName("Service quotation");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(BigDecimal.valueOf(500));
        quotation.setCurrencyCode("UZS");
        quotation = quotationRepository.save(quotation);

        SupplierQuotationItem quotationItem = new SupplierQuotationItem();
        quotationItem.setCode("QI-SVC-1");
        quotationItem.setName("Service quotation item");
        quotationItem.setStatus("SUBMITTED");
        quotationItem.setQuotationId(quotation.getId());
        quotationItem.setRfqItemId(rfqItem.getId());
        quotationItem.setLineType(com.enterprise.mm.enums.ProcurementLineType.MATERIAL);
        quotationItem.setServiceDescription("Quarterly compressor maintenance");
        quotationItem.setServiceCategory("MAINTENANCE");
        quotationItem.setUnit("hour");
        quotationItem.setPeriod("Q3-2026");
        quotationItem.setStage("STAGE-1");
        quotationItem.setBudgetItemId(budgetItem.getId());
        quotationItem.setQuantity(BigDecimal.TEN);
        quotationItem.setUnitPrice(BigDecimal.valueOf(50));
        quotationItem.setPricingModel("HOUR");
        quotationItemRepository.save(quotationItem);

        PurchaseOrderResponse po = supplierQuotationService.selectAndCreatePo(
            quotation.getId(),
            "Selected compliant quotation"
        );
        purchaseOrderService.submitForApproval(po.getId());
        approveAll(PurchaseOrderApprovalAdapter.MODULE_KEY, po.getId());
        po = purchaseOrderService.send(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());

        var poItem = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId()).getFirst();
        assertThat(poItem.getLineType()).isEqualTo(com.enterprise.mm.enums.ProcurementLineType.SERVICE);

        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setCode("SO-SVC-1");
        serviceOrder.setName("Service order");
        serviceOrder.setStatus("IN_PROGRESS");
        serviceOrder.setSupplierId(supplier.getId());
        serviceOrder.setContractId(contract.getId());
        serviceOrder.setPurchaseOrderId(po.getId());
        serviceOrder.setServiceType("MAINTENANCE");
        serviceOrder.setTotalAmount(BigDecimal.valueOf(500));
        serviceOrder.setCurrencyCode("UZS");
        serviceOrder = serviceOrderRepository.save(serviceOrder);

        var act = serviceAcceptanceActService.accept(serviceOrder.getId(), "commission", "accepted");
        assertThat(act.getStatus()).isEqualTo("DRAFT");

        attachmentService.saveBytes(
            "SERVICE_ACCEPTANCE_ACT",
            act.getId(),
            "ACT",
            "act.pdf",
            "application/pdf",
            "service-act".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        attachmentService.saveBytes(
            "SERVICE_ACCEPTANCE_ACT",
            act.getId(),
            "PHOTO",
            "photo.jpg",
            "image/jpeg",
            "service-photo".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        serviceAcceptanceActService.submitForApproval(act.getId());
        approveAll(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId());
        serviceAcceptanceActService.documentApprove(act.getId(), null);
        approveAll(ServiceFinanceApprovalAdapter.MODULE_KEY, act.getId());

        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(contract.getId());
        invoiceRequest.setPurchaseOrderId(po.getId());
        invoiceRequest.setSupplierId(supplier.getId());
        invoiceRequest.setInvoiceNumber("INV-SVC-" + UUID.randomUUID().toString().substring(0, 8));
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(BigDecimal.valueOf(500));
        invoiceRequest.setCurrencyCode("UZS");
        PurchaseOrder linkedPo = purchaseOrderRepository.findAnyById(po.getId()).orElseThrow();
        linkedPo.setContractId(contract.getId());
        linkedPo.setRequestType(RequestType.SERVICE);
        purchaseOrderRepository.save(linkedPo);
        contractInvoiceService.issue(contractInvoiceService.create(invoiceRequest).getId());

        act = serviceAcceptanceActService.sendToPayment(act.getId(), "PAY-SVC-1", "finance ok");
        assertThat(act.getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(act.getBudgetConsumedAt()).isNotNull();
        assertThat(act.getContractConsumedAt()).isNotNull();

        var servicePayment = contractPaymentRepository
            .findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT.name(),
                act.getId()
            )
            .getFirst();
        assertThat(servicePayment.getStatus()).isEqualTo(ContractPaymentStatus.SENT_TO_TREASURY.name());
        assertThat(servicePayment.getPurchaseOrderId()).isEqualTo(po.getId());
        assertThat(servicePayment.getSourceDocumentId()).isEqualTo(act.getId());
        assertThat(servicePayment.getAmount()).isEqualByComparingTo("500");

        var repeated = serviceAcceptanceActService.sendToPayment(act.getId(), "PAY-SVC-1", "retry");
        assertThat(repeated.getStatus()).isEqualTo("SENT_TO_PAYMENT");

        po = purchaseOrderService.close(po.getId());
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED.name());

        BudgetItem consumedBudget = budgetItemRepository.findAnyById(budgetItem.getId()).orElseThrow();
        assertThat(consumedBudget.getReservedAmount()).isEqualByComparingTo("500");
        assertThat(consumedBudget.getUsedAmount()).isEqualByComparingTo("500");

        Contract consumedContract = contractRepository.findAnyById(contract.getId()).orElseThrow();
        assertThat(consumedContract.getUsedAmount()).isEqualByComparingTo("500");
        assertThat(consumedContract.getServiceUsedAmount()).isEqualByComparingTo("500");
    }

    private ProcurementProcedure approvedMethodProcedure(
        String method,
        Material material,
        String suffix
    ) {
        PurchaseRequestCreateRequest purchaseRequest = new PurchaseRequestCreateRequest();
        purchaseRequest.setCode("PR-" + suffix);
        purchaseRequest.setName(method + " purchase request");
        purchaseRequest.setCurrencyCode("UZS");
        purchaseRequest.setDemandPlanId(approvedDemandPlanId("DP-" + suffix));
        purchaseRequest.setItems(java.util.List.of(purchaseRequestItem(material.getId(), 4, 125)));
        var createdPurchaseRequest = purchaseRequestService.create(purchaseRequest);
        purchaseRequestService.submit(createdPurchaseRequest.getId());
        approveAll(PurchaseRequestApprovalAdapter.MODULE_KEY, createdPurchaseRequest.getId());

        Attachment marketResearch = procurementEvidence(
            "PMD-MARKET-RESEARCH",
            createdPurchaseRequest.getId(),
            "market-research-" + suffix + ".pdf"
        );
        Attachment technicalSpecification = procurementEvidence(
            "PMD-TECHNICAL-SPECIFICATION",
            createdPurchaseRequest.getId(),
            "technical-specification-" + suffix + ".pdf"
        );
        ProcurementMethodDecisionCreateRequest decisionRequest = new ProcurementMethodDecisionCreateRequest();
        decisionRequest.setName(method + " method decision");
        decisionRequest.setPurchaseRequestId(createdPurchaseRequest.getId());
        decisionRequest.setSelectedMethod(method);
        decisionRequest.setJustification("Approved procurement route supported by market and technical evidence.");
        decisionRequest.setEstimatedAmount(BigDecimal.valueOf(500));
        decisionRequest.setCurrencyCode("UZS");
        decisionRequest.setPotentialSupplierCount(
            java.util.Set.of("DIRECT_PURCHASE", "SINGLE_SOURCE").contains(method) ? 1 : 3);
        UUID processCommissionId = processCommissionId(method, suffix);
        if (processCommissionId != null) {
            decisionRequest.setCommissionId(processCommissionId);
        }
        if (java.util.Set.of("DIRECT_PURCHASE", "SINGLE_SOURCE").contains(method)) {
            decisionRequest.setExceptionReason(
                "The selected exception route is supported by the documented operational need.");
        }
        decisionRequest.setMarketResearchAttachmentId(marketResearch.getId());
        decisionRequest.setTechnicalSpecificationAttachmentId(technicalSpecification.getId());

        var decision = procurementMethodDecisionService.create(decisionRequest);
        procurementMethodDecisionService.submit(decision.getId());
        approveAll(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY, decision.getId());
        return procurementProcedureRepository
            .findByMethodDecisionIdAndIsDeletedFalse(decision.getId())
            .orElseThrow();
    }

    private UUID processCommissionId(String method, String suffix) {
        CommissionType type = switch (method) {
            case "OPEN_TENDER", "CLOSED_TENDER" -> CommissionType.TENDER;
            case "OPEN_COMPETITION", "CLOSED_COMPETITION" -> CommissionType.COMPETITION;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        Commission commission = new Commission();
        commission.setCode("COM-" + type.name() + "-" + suffix);
        commission.setName(type.name() + " commission " + suffix);
        commission.setStatus("ACTIVE");
        commission.setDescription("Process commission for " + method);
        commission.setType(type);
        commission.setActive(true);
        commission.setValidFrom(LocalDate.now().minusDays(1));
        commission.setValidTo(LocalDate.now().plusYears(1));
        commission = commissionRepository.save(commission);
        for (int i = 1; i <= 3; i++) {
            CommissionMember member = new CommissionMember();
            member.setCode("COM-MEMBER-" + suffix + "-" + i);
            member.setName("Commission member " + i);
            member.setStatus("ACTIVE");
            member.setCommissionId(commission.getId());
            member.setMemberRole(i == 1 ? CommissionMemberRole.CHAIRMAN : i == 2 ? CommissionMemberRole.SECRETARY : CommissionMemberRole.MEMBER);
            member.setMemberName("Commission member " + i);
            member.setActive(true);
            commissionMemberRepository.save(member);
        }
        return commission.getId();
    }

    private com.enterprise.mm.payload.ProcurementCampaignResponse launchCampaign(
        ProcurementProcedure procedure,
        String suffix
    ) {
        ProcurementCampaignCreateRequest request = new ProcurementCampaignCreateRequest();
        request.setName("Campaign for " + procedure.getName());
        request.setProcurementProcedureId(procedure.getId());
        request.setTargetSupplierCount(2);
        request.setPlannedSpendAmount(BigDecimal.valueOf(500));
        request.setApprovedSpendAmount(BigDecimal.valueOf(500));
        request.setSavingsTargetAmount(BigDecimal.ZERO);
        request.setCurrencyCode("UZS");
        request.setPlannedStartDate(LocalDate.now());
        request.setPlannedEndDate(LocalDate.now().plusDays(30));
        var campaign = procurementCampaignService.create(request);
        procurementCampaignService.plan(campaign.getId());
        return procurementCampaignService.launch(campaign.getId());
    }

    private SupplierQuotation governedQuotation(
        RFQ rfq,
        java.util.List<RFQItem> rfqItems,
        Supplier supplier,
        String suffix,
        String total,
        String score
    ) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-" + suffix);
        quotation.setName("Quotation " + suffix);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(new BigDecimal(total));
        quotation.setCurrencyCode(rfq.getCurrencyCode() == null ? "UZS" : rfq.getCurrencyCode());
        quotation.setSubmittedAt(LocalDateTime.now());
        quotation.setCurrentRevision(true);
        quotation.setTechnicalScore(new BigDecimal(score));
        quotation.setDeliveryScore(BigDecimal.ZERO);
        quotation.setPriceScore(BigDecimal.ZERO);
        quotation.setTotalScore(new BigDecimal(score));
        quotation = quotationRepository.save(quotation);

        BigDecimal lineTotal = new BigDecimal(total).divide(
            BigDecimal.valueOf(rfqItems.size()), 4, java.math.RoundingMode.HALF_UP);
        for (RFQItem rfqItem : rfqItems) {
            SupplierQuotationItem line = new SupplierQuotationItem();
            line.setCode("QI-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4));
            line.setName(rfqItem.getName());
            line.setStatus("SUBMITTED");
            line.setQuotationId(quotation.getId());
            line.setRfqItemId(rfqItem.getId());
            line.setLineType(rfqItem.getLineType());
            line.setMaterialId(rfqItem.getMaterialId());
            line.setServiceRequestId(rfqItem.getServiceRequestId());
            line.setServiceDescription(rfqItem.getServiceDescription());
            line.setServiceCategory(rfqItem.getServiceCategory());
            line.setUnit(rfqItem.getUnit());
            line.setPeriod(rfqItem.getPeriod());
            line.setStage(rfqItem.getStage());
            line.setBudgetItemId(rfqItem.getBudgetItemId());
            line.setQuantity(rfqItem.getQuantity());
            line.setUnitPrice(lineTotal.divide(
                rfqItem.getQuantity(), 4, java.math.RoundingMode.HALF_UP));
            line.setNoBid(false);
            quotationItemRepository.save(line);
        }
        return quotation;
    }

    private void approveTenderGovernance(
        RFQ rfq,
        SupplierQuotation winner,
        SupplierQuotation runnerUp,
        String suffix
    ) {
        approveTenderGovernance(rfq, java.util.List.of(winner, runnerUp), suffix, winner);
    }

    private void approveTenderGovernance(
        RFQ rfq,
        java.util.List<SupplierQuotation> quotations,
        String suffix,
        SupplierQuotation winner
    ) {
        TenderBidOpeningProtocol opening = new TenderBidOpeningProtocol();
        opening.setCode("OPEN-" + suffix);
        opening.setName("Bid opening " + suffix);
        opening.setStatus("POSTED");
        opening.setRfqId(rfq.getId());
        opening.setProtocolNumber("OPEN-" + suffix);
        opening.setOpenedAt(LocalDateTime.now());
        opening.setOpenedBy("tester");
        opening.setQuorumRequired(1);
        opening.setQuorumPresent(1);
        opening.setQuorumConfirmed(true);
        opening.setQuotationCount(quotations.size());
        opening.setAcceptedQuotationCount(quotations.size());
        opening.setRejectedQuotationCount(0);
        opening.setPostedAt(LocalDateTime.now());
        opening = tenderBidOpeningProtocolRepository.save(opening);

        for (int index = 0; index < quotations.size(); index++) {
            saveOpeningLine(opening, rfq, quotations.get(index), suffix + "-" + index);
        }

        TenderEvaluationProtocol evaluation = new TenderEvaluationProtocol();
        evaluation.setCode("EVAL-" + suffix);
        evaluation.setName("Tender evaluation " + suffix);
        evaluation.setStatus("APPROVED");
        evaluation.setRfqId(rfq.getId());
        evaluation.setProtocolNumber("EVAL-" + suffix);
        evaluation.setEvaluatedAt(LocalDateTime.now());
        evaluation.setSelectionRule(rfq.getSelectionRule() == null
            ? "BEST_SCORE"
            : rfq.getSelectionRule());
        evaluation.setRecommendedQuotationId(winner.getId());
        evaluation.setRecommendedSupplierId(winner.getSupplierId());
        evaluation.setRecommendationReason("Highest evaluated compliant quotation");
        evaluation.setApprovedBy("tester");
        evaluation.setApprovedAt(LocalDateTime.now());
        tenderEvaluationProtocolRepository.save(evaluation);
    }

    private void saveOpeningLine(
        TenderBidOpeningProtocol opening,
        RFQ rfq,
        SupplierQuotation quotation,
        String suffix
    ) {
        TenderBidOpeningLine line = new TenderBidOpeningLine();
        line.setCode("OPEN-LINE-" + suffix);
        line.setName("Accepted quotation " + suffix);
        line.setStatus("ACCEPTED");
        line.setProtocolId(opening.getId());
        line.setRfqId(rfq.getId());
        line.setQuotationId(quotation.getId());
        line.setSupplierId(quotation.getSupplierId());
        line.setSubmittedAt(quotation.getSubmittedAt());
        line.setTotalAmount(quotation.getTotalAmount());
        line.setCurrencyCode(quotation.getCurrencyCode());
        line.setAcceptedForEvaluation(true);
        tenderBidOpeningLineRepository.save(line);
    }

    private void assertAwardedProcedure(
        ProcurementProcedure original,
        PurchaseOrderResponse purchaseOrder,
        Supplier supplier,
        UUID contractId,
        String expectedAmount
    ) {
        ProcurementProcedure completed = procurementProcedureRepository
            .findAnyById(original.getId())
            .orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getAwardedSupplierId()).isEqualTo(supplier.getId());
        assertThat(completed.getPurchaseOrderId()).isEqualTo(purchaseOrder.getId());
        assertThat(completed.getContractId()).isEqualTo(contractId);
        assertThat(purchaseOrder.getSupplierId()).isEqualTo(supplier.getId());
        assertThat(purchaseOrder.getPurchaseRequestId()).isEqualTo(original.getPurchaseRequestId());
        assertThat(purchaseOrder.getContractId()).isEqualTo(contractId);
        assertThat(purchaseOrder.getTotalAmount()).isEqualByComparingTo(expectedAmount);
    }

    private void assertPoHasSourceLineage(PurchaseOrderResponse purchaseOrder) {
        var lines = purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrder.getId());
        assertThat(lines).isNotEmpty();
        assertThat(purchaseOrderSourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                lines.getFirst().getId()
            )).isNotEmpty();
    }

    private UUID approvedDemandPlanId(String code) {
        return approvedDemandPlanId(code, RequestType.GOODS);
    }

    private UUID approvedDemandPlanId(String code, RequestType requestType) {
        DemandPlanningCycle cycle = approvedDemandPlanningCycle("DPC-" + code);
        DemandPlan plan = new DemandPlan();
        plan.setCode(code);
        plan.setName("Approved demand plan " + code);
        plan.setStatus(DemandPlanStatus.APPROVED.name());
        plan.setPlanningCycle(cycle);
        plan.setRequestType(requestType == null ? RequestType.GOODS : requestType);
        plan.setPlanYear(Year.now().getValue());
        plan.setPeriodType(DemandPeriodType.YEAR.name());
        plan.setPeriodStart(LocalDate.now().withDayOfYear(1));
        plan.setPeriodEnd(LocalDate.now().withDayOfYear(LocalDate.now().lengthOfYear()));
        plan.setCurrencyCode("UZS");
        plan.setTotalPlannedAmount(BigDecimal.ZERO);
        plan.setTotalApprovedAmount(BigDecimal.ZERO);
        return demandPlanRepository.save(plan).getId();
    }

    private DemandPlanningCycle approvedDemandPlanningCycle(String code) {
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode(code);
        cycle.setName("Approved demand planning cycle " + code);
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setPlanYear(Year.now().getValue());
        cycle.setPeriodType(DemandPeriodType.YEAR.name());
        cycle.setPeriodStart(LocalDate.now().withDayOfYear(1));
        cycle.setPeriodEnd(LocalDate.now().withDayOfYear(LocalDate.now().lengthOfYear()));
        cycle.setCollectionStartDate(LocalDate.now().minusDays(10));
        cycle.setCollectionEndDate(LocalDate.now().minusDays(1));
        cycle.setBudgetReviewDeadline(LocalDate.now());
        cycle.setCurrencyCode("UZS");
        return demandPlanningCycleRepository.save(cycle);
    }

    private RFQ seedRfq(PurchaseRequest purchaseRequest) {
        PurchaseRequest currentPurchaseRequest = purchaseRequestRepository
            .findAnyById(purchaseRequest.getId())
            .orElseThrow();
        if (currentPurchaseRequest.getDemandPlanId() == null) {
            currentPurchaseRequest.setDemandPlanId(approvedDemandPlanId(
                "DP-" + currentPurchaseRequest.getCode(),
                currentPurchaseRequest.getRequestType()
            ));
            currentPurchaseRequest = purchaseRequestRepository.save(currentPurchaseRequest);
        }
        PurchaseRequestItem purchaseRequestItem = purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), currentPurchaseRequest.getId())
            .getFirst();
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-" + currentPurchaseRequest.getCode());
        rfq.setName("RFQ for " + currentPurchaseRequest.getName());
        rfq.setStatus("DRAFT");
        rfq.setPurchaseRequestId(currentPurchaseRequest.getId());
        rfq.setTenderProcedureType("SINGLE_SUPPLIER");
        rfq.setSelectionRule("SINGLE_SUPPLIER");
        rfq.setMinQuotationsRequired(1);
        rfq.setCurrencyCode(currentPurchaseRequest.getCurrencyCode());
        rfq.setDueDate(LocalDate.now().plusDays(7));
        rfq.setSourcePurchaseRequestCount(1);
        rfq.setSourceItemCount(1);
        rfq = rfqRepository.save(rfq);

        RFQItem rfqItem = new RFQItem();
        rfqItem.setCode("RFQI-" + purchaseRequestItem.getCode());
        rfqItem.setName(purchaseRequestItem.getName());
        rfqItem.setStatus("ACTIVE");
        rfqItem.setRfqId(rfq.getId());
        rfqItem.setMaterialId(purchaseRequestItem.getMaterialId());
        rfqItem.setLineType(purchaseRequestItem.getLineType() == null ? ProcurementLineType.MATERIAL : purchaseRequestItem.getLineType());
        rfqItem.setQuantity(purchaseRequestItem.getQuantity());
        rfqItem.setServiceDescription(purchaseRequestItem.getServiceDescription());
        rfqItem.setServiceCategory(purchaseRequestItem.getServiceCategory());
        rfqItem.setUnit(purchaseRequestItem.getUnit());
        rfqItem.setPeriod(purchaseRequestItem.getPeriod());
        rfqItem.setStage(purchaseRequestItem.getStage());
        rfqItem.setBudgetItemId(purchaseRequestItem.getBudgetItemId());
        rfqItemRepository.save(rfqItem);
        return rfq;
    }

    private Material material(String code) {
        MaterialGroup group = defaultMaterialGroup();
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(group.getId());
        return materialRepository.save(material);
    }

    private Department department(String code) {
        Department department = new Department();
        department.setCode(code);
        department.setName(code);
        department.setStatus("ACTIVE");
        return departmentRepository.save(department);
    }

    private MaterialGroup defaultMaterialGroup() {
        return materialGroupRepository.findAll().stream()
            .filter(group -> !group.isDeleted() && "P2P-DEFAULT-GROUP".equals(group.getCode()))
            .findFirst()
            .orElseGet(() -> {
                MaterialGroup group = new MaterialGroup();
                group.setCode("P2P-DEFAULT-GROUP");
                group.setName("P2P default material group");
                group.setStatus("ACTIVE");
                return materialGroupRepository.save(group);
            });
    }

    private PurchaseRequestItemCreateRequest purchaseRequestItem(UUID materialId, int quantity, int estimatedPrice) {
        PurchaseRequestItemCreateRequest item = new PurchaseRequestItemCreateRequest();
        item.setMaterialId(materialId);
        item.setQuantity(BigDecimal.valueOf(quantity));
        item.setEstimatedPrice(BigDecimal.valueOf(estimatedPrice));
        return item;
    }

    private PurchaseRequestItemCreateRequest servicePurchaseRequestItem(String description, String category, UUID budgetItemId, int quantity, int estimatedPrice) {
        PurchaseRequestItemCreateRequest item = new PurchaseRequestItemCreateRequest();
        item.setLineType("SERVICE");
        item.setServiceDescription(description);
        item.setServiceCategory(category);
        item.setUnit("hour");
        item.setPeriod("Q3-2026");
        item.setStage("STAGE-1");
        item.setBudgetItemId(budgetItemId);
        item.setQuantity(BigDecimal.valueOf(quantity));
        item.setEstimatedPrice(BigDecimal.valueOf(estimatedPrice));
        return item;
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setRating(BigDecimal.valueOf(90));
        supplier.setCategory("A");
        Supplier saved = supplierRepository.save(supplier);
        accreditSupplier(saved);
        return saved;
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private StorageLocation storageLocation(String code, java.util.UUID warehouseId) {
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setCode(code);
        storageLocation.setName(code);
        storageLocation.setStatus("ACTIVE");
        storageLocation.setWarehouseId(warehouseId);
        return storageLocationRepository.save(storageLocation);
    }

    private StorageBin storageBin(String code, java.util.UUID warehouseId, java.util.UUID storageLocationId) {
        StorageBin storageBin = new StorageBin();
        storageBin.setCode(code);
        storageBin.setName(code);
        storageBin.setStatus("ACTIVE");
        storageBin.setWarehouseId(warehouseId);
        storageBin.setStorageLocationId(storageLocationId);
        storageBin.setBinCode(code);
        return storageBinRepository.save(storageBin);
    }

    private void accreditSupplier(Supplier supplier) {
        MaterialGroup group = defaultMaterialGroup();
        saveAccreditation(supplier, "GOODS", java.util.List.of(group.getId()), "Industrial materials");
        saveAccreditation(supplier, "SERVICES", java.util.List.of(), "General services");
        saveAccreditation(supplier, "WORKS", java.util.List.of(), "General works");
    }

    private void saveAccreditation(
        Supplier supplier,
        String type,
        java.util.List<UUID> groupIds,
        String approvedScope
    ) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("ACC-" + type + "-" + supplier.getCode());
        application.setName("Accreditation " + type + " " + supplier.getCode());
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType(type);
        application.setRequestedScope(approvedScope);
        application.setRequestedMaterialGroupIds(groupIds);
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusYears(1));
        application = supplierAccreditationApplicationRepository.save(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("ACC-DEC-" + type + "-" + supplier.getCode());
        decision.setName("Decision " + type + " " + supplier.getCode());
        decision.setStatus("RECORDED");
        decision.setApplicationId(application.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope(approvedScope);
        decision.setAllowedMaterialGroupIds(groupIds);
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusYears(1));
        supplierAccreditationDecisionRepository.save(decision);
        saveVerifiedAccreditationEvidence(application);
    }

    private void saveVerifiedAccreditationEvidence(SupplierAccreditationApplication application) {
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setApplicationId(application.getId());
        document.setDocumentType("REGISTRATION_CERTIFICATE");
        document.setStatus("VERIFIED");
        document.setValidFrom(LocalDate.now().minusDays(1));
        document.setValidTo(LocalDate.now().plusYears(1));
        document = supplierAccreditationDocumentRepository.save(document);

        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + UUID.randomUUID().toString().substring(0, 8));
        attachment.setName("accreditation-evidence.pdf");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("SUPPLIER_ACCREDITATION_DOCUMENT");
        attachment.setEntityId(document.getId());
        attachment.setAttachmentType("REGISTRATION_CERTIFICATE");
        attachment.setOriginalFileName("accreditation-evidence.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(128L);
        attachment.setChecksumSha256("a".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("tests/accreditation/" + UUID.randomUUID());
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setDownloadable(true);
        attachment = attachmentRepository.save(attachment);

        document.setAttachmentId(attachment.getId());
        supplierAccreditationDocumentRepository.save(document);
    }

    private Attachment procurementEvidence(String type, UUID entityId, String fileName) {
        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + UUID.randomUUID().toString().substring(0, 8));
        attachment.setName(fileName);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("PROCUREMENT_METHOD_DECISION");
        attachment.setEntityId(entityId);
        attachment.setAttachmentType(type);
        attachment.setOriginalFileName(fileName);
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(128L);
        attachment.setChecksumSha256(UUID.randomUUID().toString().replace("-", "").repeat(2));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("tests/procurement/" + UUID.randomUUID());
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setDownloadable(true);
        return attachmentRepository.save(attachment);
    }

    private BudgetItem budgetItem(String code, String annualLimit) {
        BudgetItem budgetItem = new BudgetItem();
        budgetItem.setCode(code);
        budgetItem.setName(code);
        budgetItem.setStatus("ACTIVE");
        budgetItem.setAnnualLimit(new BigDecimal(annualLimit));
        budgetItem.setReservedAmount(BigDecimal.ZERO);
        budgetItem.setUsedAmount(BigDecimal.ZERO);
        return budgetItemRepository.save(budgetItem);
    }

    private Contract draftContract(String code, UUID supplierId, String limit) {
        Contract contract = new Contract();
        contract.setCode(code);
        contract.setName(code);
        contract.setStatus(ContractStatus.DRAFT.name());
        contract.setSupplierId(supplierId);
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusDays(30));
        contract.setLimitAmount(new BigDecimal(limit));
        contract.setUsedAmount(BigDecimal.ZERO);
        contract.setServiceUsedAmount(BigDecimal.ZERO);
        contract.setCurrencyCode("UZS");
        return contractRepository.save(contract);
    }

    private Contract contract(String code, UUID supplierId, String limit) {
        Contract contract = new Contract();
        contract.setCode(code);
        contract.setName(code);
        contract.setStatus(ContractStatus.DRAFT.name());
        contract.setSupplierId(supplierId);
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusDays(30));
        contract.setLimitAmount(new BigDecimal(limit));
        contract.setUsedAmount(BigDecimal.ZERO);
        contract.setServiceUsedAmount(BigDecimal.ZERO);
        contract.setCurrencyCode("UZS");
        contract = contractRepository.save(contract);
        contract.setStatus(ContractStatus.ACTIVE.name());
        return contractRepository.save(contract);
    }

    private void seedTreasuryAck(UUID paymentId, String externalReference) {
        IntegrationEvent callback = new IntegrationEvent();
        callback.setTenantId(TenantContext.currentTenant());
        callback.setCompanyId(CompanyContext.currentCompany());
        callback.setCode("TREASURY-CALLBACK-" + UUID.randomUUID().toString().substring(0, 8));
        callback.setName("Treasury payment callback");
        callback.setDirection(IntegrationEventDirection.INBOUND.name());
        callback.setStatus("PUBLISHED");
        callback.setEventType("TREASURY.PAYMENT_STATUS_PAID");
        callback.setAggregateType("TREASURY");
        callback.setAggregateId(paymentId);
        callback.setExternalId(externalReference);
        callback.setDescription("PAID");
        callback.setPayload("{}");
        callback.setCreatedAt(LocalDateTime.now());
        integrationEventRepository.saveAndFlush(callback);
    }

    private void linkPurchaseOrderToContract(UUID purchaseOrderId, UUID contractId) {
        PurchaseOrder linkedPo = purchaseOrderRepository.findAnyById(purchaseOrderId).orElseThrow();
        linkedPo.setContractId(contractId);
        if (linkedPo.getCode() != null && linkedPo.getCode().length() > 30) {
            linkedPo.setCode("PO-" + purchaseOrderId.toString().substring(0, 8));
        }
        purchaseOrderRepository.save(linkedPo);
        purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
            .forEach(item -> {
                if (item.getCode() != null && item.getCode().length() > 20) {
                    item.setCode("POI-" + item.getId().toString().substring(0, 8));
                    purchaseOrderItemRepository.save(item);
                }
            });
    }

    private PurchaseOrderResponse receiveSentGoodsPoThroughSupplierPortal(
        PurchaseOrderResponse po,
        Supplier supplier,
        Warehouse warehouse,
        String suffix
    ) {
        var poItem = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
            .getFirst();
        AdvanceShippingNotice asn = confirmedPortalAsn(po, supplier, suffix, poItem.getQuantity());
        StorageLocation storageLocation = storageLocation("LOC-P2P-" + suffix, warehouse.getId());

        return purchaseOrderService.receive(po.getId(), new ReceivePoRequest(
            warehouse.getId(),
            storageLocation.getId(),
            asn.getId(),
            java.util.List.of(new ReceivePoLineRequest(
                poItem.getId(),
                poItem.getQuantity(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "BATCH-" + suffix,
                storageLocation.getId(),
                "ACCEPTED"
            ))
        ));
    }

    private AdvanceShippingNotice confirmedPortalAsn(
        PurchaseOrderResponse po,
        Supplier supplier,
        String suffix,
        BigDecimal quantity
    ) {
        var dispatch = supplierPoDispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId()
            )
            .orElseThrow();
        var poItem = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
            .getFirst();

        var response = supplierPoResponseService.startOrGetDraft(dispatch.getId());
        supplierPoResponseService.saveDraftLines(response.getId(), java.util.List.of(
            new LineDecisionCommand(
                poItem.getId(),
                "ACCEPTED",
                null,
                "Confirmed for receipt",
                java.util.List.of(new CommitmentCommand(quantity, LocalDate.now().plusDays(3)))
            )
        ), "Confirmed");
        response = supplierPoResponseService.submit(response.getId());
        supplierPoBuyerDecisionService.accept(response.getId(), "Accepted by buyer");

        SupplierPoCommitment commitment = supplierPoCommitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), po.getId(), java.util.List.of("CONFIRMED")
            )
            .getFirst();

        AdvanceShippingNotice asn = supplierPoAsnService.createDraft(
            po.getId(),
            supplier.getId(),
            null,
            new SupplierPoAsnService.AsnDraftCommand(
                "ASN-" + suffix,
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now().plusDays(1),
                "ROAD",
                "Test carrier",
                "VEH-" + suffix,
                null,
                "TRK-" + suffix,
                java.util.List.of(new SupplierPoAsnService.AsnLineCommand(
                    commitment.getId(), quantity, "BATCH-" + suffix, null
                ))
            )
        );
        asn = supplierPoAsnService.submit(po.getId(), asn.getId(), supplier.getId(), "ASN-SUBMIT-" + suffix);
        asn.setStatus("CONFIRMED");
        asn.setConfirmedAt(LocalDateTime.now());
        return advanceShippingNoticeRepository.save(asn);
    }

    private ContractInvoiceResponse issueInvoiceAndCompleteGoodsPayment(
        UUID contractId,
        UUID purchaseOrderId,
        BigDecimal amount,
        String suffix
    ) {
        String paymentReference = "PAY-" + suffix;
        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(contractId);
        invoiceRequest.setPurchaseOrderId(purchaseOrderId);
        invoiceRequest.setInvoiceNumber("INV-" + suffix);
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(amount);
        invoiceRequest.setCurrencyCode("UZS");
        var issuedInvoice = contractInvoiceService.issue(contractInvoiceService.create(invoiceRequest).getId());

        ContractPaymentCreateRequest paymentRequest = new ContractPaymentCreateRequest();
        paymentRequest.setContractInvoiceId(issuedInvoice.getId());
        paymentRequest.setContractId(contractId);
        paymentRequest.setPurchaseOrderId(purchaseOrderId);
        paymentRequest.setAmount(amount);
        paymentRequest.setCurrencyCode("UZS");
        paymentRequest.setPaymentNumber(paymentReference);
        var payment = contractPaymentService.create(paymentRequest);
        contractPaymentService.submitToTreasury(
            payment.getId(),
            new PaymentSubmitRequest(paymentReference, "goods payment")
        );
        seedTreasuryAck(payment.getId(), paymentReference);
        contractPaymentService.markPaid(
            payment.getId(),
            new PaymentMarkPaidRequest(paymentReference, null)
        );
        return contractInvoiceService.get(issuedInvoice.getId());
    }

    private void approveAll(String moduleKey, UUID entityId) {
        Authentication original = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "approval-tester",
            "n/a",
            java.util.List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_MM_MANAGER"),
                new SimpleGrantedAuthority("ROLE_BUDGET_CONTROLLER"),
                new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"),
                new SimpleGrantedAuthority("ROLE_FINANCE_CONTROLLER")
            )
        ));
        try {
            var instance = approvalEngineService.currentInstance(moduleKey, entityId);
            while (instance.approvalStatus() != ApprovalInstanceStatus.APPROVED) {
                var task = instance.tasks().stream()
                    .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
                    .findFirst()
                    .orElseThrow();
                instance = approvalEngineService.approveTask(task.id(), "ok");
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
    }

    private void ensureMethodCapability(String method) {
        ProcurementMethodCapability capability = procurementMethodCapabilityRepository
            .findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                method
            )
            .orElseGet(ProcurementMethodCapability::new);
        capability.setMethodCode(method);
        capability.setCode("TEST-CAP-" + method);
        capability.setName("Test capability " + method);
        capability.setState(ProcurementMethodCapabilityState.ENABLED);
        capability.setEffectiveFrom(LocalDate.now().minusDays(1));
        capability.setEffectiveTo(LocalDate.now().plusYears(1));
        capability.setCurrencyCode("UZS");
        capability.setAllowedRequestTypes(java.util.Set.of("GOODS", "SERVICE", "WORKS"));
        capability.setApprovalRequired(true);
        capability.setConfigurationVersion(capability.getConfigurationVersion() == null ? 1 : capability.getConfigurationVersion());
        capability.setChangeReason("Phase 5 test capability seed");
        capability.setConfiguredAt(LocalDateTime.now());
        capability.setConfiguredBy("test");
        procurementMethodCapabilityRepository.save(capability);
    }

    private void ensureApprovalFlow(String moduleKey, String name, String stepCodePrefix, String approverRole) {
        boolean usesRequestType = !java.util.Set.of(
            ProcurementDirectAwardApprovalAdapter.MODULE_KEY,
            ProcurementDirectAwardApprovalAdapter.SINGLE_SOURCE_MODULE_KEY,
            ProcurementFrameworkApprovalAdapter.MODULE_KEY,
            ProcurementMethodDecisionApprovalAdapter.MODULE_KEY,
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            ServiceFinanceApprovalAdapter.MODULE_KEY
        ).contains(moduleKey);
        ensureApprovalFlow(moduleKey, name, stepCodePrefix, approverRole, usesRequestType ? RequestType.GOODS : null);
    }

    private void ensureApprovalFlow(String moduleKey, String name, String stepCodePrefix, String approverRole, RequestType requestType) {
        ApprovalFlow saved = (requestType == null
            ? approvalFlowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                moduleKey
            )
            : approvalFlowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                moduleKey,
                ApprovalRequestType.valueOf(requestType.name())
            )
        ).orElseGet(() -> {
            ApprovalFlow flow = new ApprovalFlow();
            flow.setCode("TEST-FLOW-" + UUID.randomUUID().toString().substring(0, 8));
            flow.setName(name);
            flow.setStatus("ACTIVE");
            flow.setModuleKey(moduleKey);
            flow.setActive(true);
            flow.setRequestType(requestType == null ? null : ApprovalRequestType.valueOf(requestType.name()));
            return approvalFlowRepository.save(flow);
        });
        saved.setRequestType(requestType == null ? null : ApprovalRequestType.valueOf(requestType.name()));
        approvalFlowRepository.save(saved);
        var steps = approvalFlowStepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(),
            saved.getId()
        );
        if (steps.isEmpty()) {
            ApprovalFlowStep created = new ApprovalFlowStep();
            created.setApprovalFlowId(saved.getId());
            created.setCode(stepCodePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            created.setName(name + " step");
            created.setStepOrder(1);
            steps = java.util.List.of(created);
        }
        steps.forEach(step -> {
            step.setStatus("ACTIVE");
            step.setAssigneeType(ApprovalAssigneeType.ROLE);
            step.setAssigneeRole(approverRole);
            step.setAssigneeUserId(null);
            step.setAssigneeUsername(null);
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
        });
    }
}
