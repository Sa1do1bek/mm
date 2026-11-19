package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationProviderRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSendRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSyncTestRequest;
import com.enterprise.mm.payload.BusinessActionDtos.TenderBidOpeningRequest;
import com.enterprise.mm.payload.BusinessActionDtos.TenderEvaluationProtocolRequest;
import com.enterprise.mm.payload.ContractAddendumCreateRequest;
import com.enterprise.mm.payload.DeliveryScheduleCreateRequest;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.payload.PurchaseOrderUpdateRequest;
import com.enterprise.mm.payload.TenderCommissionMemberCreateRequest;
import com.enterprise.mm.service.ContractAddendumService;
import com.enterprise.mm.service.ContractService;
import com.enterprise.mm.service.DeliveryScheduleService;
import com.enterprise.mm.service.ExternalIntegrationService;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.IntegrationDispatchService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.service.IntegrationSettingService;
import com.enterprise.mm.service.MrpService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.service.ReportService;
import com.enterprise.mm.service.ServiceAcceptanceActService;
import com.enterprise.mm.service.SupplierQuotationService;
import com.enterprise.mm.service.TenderCommissionMemberService;
import com.enterprise.mm.service.TenderProcessService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.entity.TenderCommissionMember;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.TenderCommissionMemberRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = {"ADMIN", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER"})
class MrpContractIntegrationAnalyticsTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository units;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository rfqItemRepository;
    @Autowired ServiceOrderRepository serviceOrderRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired SupplierAccreditationApplicationRepository supplierAccreditationApplicationRepository;
    @Autowired SupplierAccreditationDecisionRepository supplierAccreditationDecisionRepository;
    @Autowired SupplierAccreditationDocumentRepository supplierAccreditationDocumentRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired MrpService mrpService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired DeliveryScheduleService deliveryScheduleService;
    @Autowired SupplierQuotationService supplierQuotationService;
    @Autowired TenderCommissionMemberService tenderCommissionMemberService;
    @Autowired TenderProcessService tenderProcessService;
    @Autowired ContractAddendumService contractAddendumService;
    @Autowired ContractService contractService;
    @Autowired ServiceAcceptanceActService actService;
    @Autowired AttachmentService attachmentService;
    @Autowired ApprovalEngineService approvalEngineService;
    @Autowired IntegrationEventService integrationEventService;
    @Autowired ExternalIntegrationService externalIntegrationService;
    @Autowired IntegrationDispatchService dispatchService;
    @Autowired IntegrationSettingService integrationSettingService;
    @Autowired ReportService reportService;
    @Autowired SupplierQuotationRepository supplierQuotationRepository;
    @Autowired SupplierQuotationItemRepository supplierQuotationItemRepository;
    @Autowired TenderCommissionMemberRepository tenderCommissionMemberRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ApprovalFlowRepository approvalFlowRepository;
    @Autowired ApprovalFlowStepRepository approvalFlowStepRepository;

    @Test
    void mrpCreatesPurchaseRequestOnlyForNetShortage() {
        Material material = new Material();
        material.setCode("MAT-MRP-1");
        material.setName("MRP material");
        material.setStatus("ACTIVE");
        material.setReorderPoint(BigDecimal.valueOf(25));
        material.setSafetyStock(BigDecimal.valueOf(5));
        material = materialRepository.save(material);
        var materialId = material.getId();

        var shortages = mrpService.calculateShortages();
        assertThat(shortages).anyMatch(shortage -> shortage.materialId().equals(materialId) && shortage.recommendedPurchaseQuantity().compareTo(BigDecimal.valueOf(25)) == 0);

        var result = mrpService.runAndCreatePurchaseRequests();
        assertThat(result.purchaseRequestCount()).isEqualTo(1);
        assertThat(purchaseRequestRepository.findAnyById(result.purchaseRequestIds().getFirst()).orElseThrow().getStatus()).isEqualTo(PurchaseRequestStatus.DRAFT.name());

        var secondRun = mrpService.runAndCreatePurchaseRequests();
        assertThat(secondRun.purchaseRequestCount()).isZero();
    }

    @Test
    void contractLimitControlsPurchaseOrdersAndServiceAcceptance() {
        Supplier supplier = supplier("SUP-CONTRACT-1");
        Contract contract = contract(supplier, BigDecimal.valueOf(100));

        PurchaseOrder tooLarge = po("PO-LIMIT-BAD", supplier, contract, BigDecimal.valueOf(150));
        assertThatThrownBy(() -> purchaseOrderService.send(tooLarge.getId())).isInstanceOf(BusinessException.class);

        PurchaseOrder ok = po("PO-LIMIT-OK", supplier, contract, BigDecimal.valueOf(40));
        purchaseOrderService.send(ok.getId());
        assertThat(contractRepository.findAnyById(contract.getId()).orElseThrow().getUsedAmount()).isEqualByComparingTo("40");

        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setCode("SRV-LIMIT-OK");
        serviceOrder.setName("Maintenance");
        serviceOrder.setStatus("DRAFT");
        serviceOrder.setSupplierId(supplier.getId());
        serviceOrder.setContractId(contract.getId());
        serviceOrder.setServiceType("WORK");
        serviceOrder.setTotalAmount(BigDecimal.valueOf(60));
        serviceOrder.setCurrencyCode("UZS");
        serviceOrder = serviceOrderRepository.save(serviceOrder);

        ensureApprovalFlow(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, "Service act flow", "ACT-STEP", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ServiceFinanceApprovalAdapter.MODULE_KEY, "Service finance flow", "FIN-STEP", "FINANCE_CONTROLLER");
        var act = actService.accept(serviceOrder.getId(), "commission", "accepted");
        assertThat(act.getStatus()).isEqualTo("DRAFT");
        attachmentService.saveBytes(
            "SERVICE_ACCEPTANCE_ACT",
            act.getId(),
            "ACT",
            "act.pdf",
            "application/pdf",
            "contract-service".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        attachmentService.saveBytes(
            "SERVICE_ACCEPTANCE_ACT",
            act.getId(),
            "PHOTO",
            "work-photo.jpg",
            "image/jpeg",
            "work-photo".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        actService.submitForApproval(act.getId());
        approveAll(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId());
        actService.documentApprove(act.getId(), new com.enterprise.mm.payload.ServiceActionRequest("documents ok", null, null));
        approveAll(ServiceFinanceApprovalAdapter.MODULE_KEY, act.getId());
        act = actService.sendToPayment(act.getId(), "PAY-CONTRACT-1", "finance ok");
        assertThat(act.getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(contractRepository.findAnyById(contract.getId()).orElseThrow().getUsedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void integrationDispatchAndAnalyticsSnapshotWork() {
        IntegrationEvent event = integrationEventService.emit("TEST_EVENT", "Test", null, "{\"ok\":true}");
        var dispatched = dispatchService.dispatch(event.getId());
        assertThat(dispatched.getStatus()).isEqualTo("RETRY");

        var snapshot = reportService.snapshot();
        assertThat(snapshot.dashboard()).isNotNull();
        assertThat(snapshot.abc()).isNotNull();
        assertThat(snapshot.metadata().metricScopes()).containsKey("abc");
        assertThat(reportService.analyticsExcel()).isNotEmpty();
    }

    @Test
    void snapshotIsFilteredByCurrentCompany() {
        UUID otherCompanyId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        CompanyContext.setCompany(otherCompanyId);
        try {
            Material material = new Material();
            material.setCode("MAT-ORG-ONLY");
            material.setName("Organization scoped item");
            material.setStatus("ACTIVE");
            material = materialRepository.save(material);
            String expectedMaterialName = materialRepository.findAnyById(material.getId()).orElseThrow().getName();

            Supplier supplier = new Supplier();
            supplier.setCode("SUP-ORG-ONLY");
            supplier.setName("Org scoped supplier");
            supplier.setStatus("ACTIVE");
            supplier.setCategory("A");
            supplier = supplierRepository.save(supplier);

            Warehouse warehouse = new Warehouse();
            warehouse.setCode("WH-ORG-ONLY");
            warehouse.setName("Org scoped warehouse");
            warehouse.setStatus("ACTIVE");
            warehouse = warehouseRepository.save(warehouse);

            StockBalance balance = new StockBalance();
            balance.setCode("SB-ORG-ONLY");
            balance.setName("Org scoped stock");
            balance.setStatus("ACTIVE");
            balance.setMaterialId(material.getId());
            balance.setWarehouseId(warehouse.getId());
            balance.setQuantity(BigDecimal.TEN);
            balance.setAvailableQuantity(BigDecimal.TEN);
            balance.setReservedQuantity(BigDecimal.ZERO);
            balance.setTotalValue(BigDecimal.valueOf(500));

            var savedBalance = stockBalanceRepository.save(balance);
            assertThat(savedBalance.getCompanyId()).isEqualTo(otherCompanyId);

            var snapshot = reportService.snapshot();
            assertThat(snapshot.dashboard().materials()).isEqualTo(1);
            assertThat(snapshot.dashboard().suppliers()).isEqualTo(1);
            assertThat(snapshot.dashboard().materialsAmount()).isEqualByComparingTo("500");
            assertThat(snapshot.dashboard().totalStockValue()).isEqualByComparingTo("500");
            assertThat(snapshot.abc()).hasSize(1);
            assertThat(snapshot.abc().getFirst().materialCode()).isEqualTo("MAT-ORG-ONLY");
            assertThat(snapshot.abc().getFirst().materialName()).isEqualTo(expectedMaterialName);
            assertThat(snapshot.supplierRating()).extracting("code").containsExactly("SUP-ORG-ONLY");
            assertThat(snapshot.nonMoving()).isEmpty();
            assertThat(snapshot.overdueDeliveries()).isEmpty();
        } finally {
            CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        }
    }

    @Test
    void externalIntegrationSystemContractsQueueIdempotentEvents() {
        var systems = externalIntegrationService.listSystems();
        assertThat(systems).extracting("code").contains("FI", "SOLIQ", "DIDOX", "FAKTURA_UZ", "CHATGPT", "CLAUDE", "GEMINI", "ONE_C", "WMS");

        var request = new IntegrationSendRequest("STOCK_RECEIPT_POSTED", null, "{\"amount\":10}", "fi-stock-receipt-test");
        var first = externalIntegrationService.enqueue(ExternalIntegrationSystem.FI, request, null);
        var second = externalIntegrationService.enqueue(ExternalIntegrationSystem.FI, request, null);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getAggregateType()).isEqualTo("FI");
        assertThat(first.getEventType()).isEqualTo("FI.STOCK_RECEIPT_POSTED");
        assertThat(first.getPayload()).contains("\"targetSystem\":\"FI\"");

        var health = externalIntegrationService.health(ExternalIntegrationSystem.SOLIQ);
        assertThat(health.status()).isEqualTo("MOCK_CONFIGURED");

        var sync = externalIntegrationService.syncTest(
            ExternalIntegrationSystem.WMS,
            new IntegrationSyncTestRequest("CONNECTIVITY_TEST", "{\"ping\":true}", "wms-connectivity-test"),
            null
        );
        assertThat(sync.getStatus()).isEqualTo("RETRY_SCHEDULED");
    }

    @Test
    void tenderSelectionCreatesPurchaseOrderFromLowestPriceQuotation() {
        Supplier supplierA = supplier("SUP-TENDER-A");
        Supplier supplierB = supplier("SUP-TENDER-B");
        Material material = material("MAT-TENDER-1");

        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-TENDER-1");
        rfq.setName("Tender RFQ");
        rfq.setStatus("OPEN");
        rfq.setTenderProcedureType("OPEN_COMPETITION");
        rfq.setSelectionRule("LOWEST_PRICE");
        rfq.setMinQuotationsRequired(2);
        rfq.setDueDate(LocalDate.now().minusDays(1));
        rfq.setPurchaseRequestId(approvedPurchaseRequest("PR-TENDER-1").getId());
        rfq = rfqRepository.save(rfq);
        RFQItem rfqItem = rfqItem(rfq, material);

        SupplierQuotation expensive = quotation("QUO-TENDER-HIGH", rfq.getId(), supplierA.getId(), BigDecimal.valueOf(200));
        quotationItem(expensive, rfqItem, material.getId(), BigDecimal.TEN, BigDecimal.valueOf(20));
        SupplierQuotation cheap = quotation("QUO-TENDER-LOW", rfq.getId(), supplierB.getId(), BigDecimal.valueOf(100));
        quotationItem(cheap, rfqItem, material.getId(), BigDecimal.TEN, BigDecimal.TEN);

        assertThatThrownBy(() -> supplierQuotationService.selectAndCreatePo(cheap.getId(), "without commission protocol"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("opening protocol");

        commissionMember(rfq.getId(), "Chair", "CHAIR");
        commissionMember(rfq.getId(), "Secretary", "SECRETARY");
        commissionMember(rfq.getId(), "Member", "MEMBER");
        var opening = tenderProcessService.openBids(rfq.getId(), new TenderBidOpeningRequest("commission", 3, "All bids opened"));
        assertThat(opening.getAcceptedQuotationCount()).isEqualTo(2);
        assertThat(tenderProcessService.runConflictChecks(rfq.getId())).hasSize(2);
        var evaluation = tenderProcessService.createEvaluationProtocol(
            rfq.getId(),
            new TenderEvaluationProtocolRequest(cheap.getId(), "lowest price", "commission", "Compared by LOWEST_PRICE")
        );
        assertThat(evaluation.getRecommendedQuotationId()).isEqualTo(cheap.getId());

        assertThatThrownBy(() -> supplierQuotationService.selectAndCreatePo(expensive.getId(), "not lowest"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("LOWEST_PRICE");

        var po = supplierQuotationService.selectAndCreatePo(cheap.getId(), "lowest price");
        assertThat(po.getStatus()).isEqualTo("DRAFT");
        assertThat(po.getSupplierId()).isEqualTo(supplierB.getId());
        assertThat(po.getTotalAmount()).isEqualByComparingTo("100");
        assertThat(supplierQuotationRepository.findAnyById(cheap.getId()).orElseThrow().isSelected()).isTrue();
    }

    @Test
    void tenderConflictChecksAcceptUiCreatedActiveSignedCommissionMembers() {
        Supplier supplier = supplier("SUP-TENDER-UI-COMMISSION");
        Material material = material("MAT-TENDER-UI-COMMISSION");

        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-TENDER-UI-COMMISSION");
        rfq.setName("Tender RFQ with UI-created commission");
        rfq.setStatus("OPEN");
        rfq.setTenderProcedureType("REQUEST_FOR_QUOTATION");
        rfq.setSelectionRule("LOWEST_PRICE");
        rfq.setMinQuotationsRequired(1);
        rfq.setDueDate(LocalDate.now().minusDays(1));
        rfq = rfqRepository.save(rfq);
        RFQItem rfqItem = rfqItem(rfq, material);

        SupplierQuotation quotation = quotation("QUO-TENDER-UI-COMMISSION", rfq.getId(), supplier.getId(), BigDecimal.valueOf(100));
        quotationItem(quotation, rfqItem, material.getId(), BigDecimal.TEN, BigDecimal.TEN);

        TenderCommissionMemberCreateRequest member = new TenderCommissionMemberCreateRequest();
        member.setRfqId(rfq.getId());
        member.setMemberName("Commission Chair");
        member.setMemberRole("CHAIR");
        member.setConflictDeclarationSigned(true);
        TenderCommissionMember savedMember = tenderCommissionMemberRepository.findAnyById(
            tenderCommissionMemberService.create(member).getId()
        ).orElseThrow();
        assertThat(savedMember.isActive()).isTrue();

        tenderProcessService.openBids(rfq.getId(), new TenderBidOpeningRequest("commission", 1, "One bid opened"));

        assertThat(tenderProcessService.runConflictChecks(rfq.getId())).hasSize(1);
    }

    @Test
    void evaluationProtocolRequiresRecommendedQuotationAcceptedInOpeningProtocol() {
        Supplier supplierA = supplier("SUP-TENDER-OPENED");
        Supplier supplierB = supplier("SUP-TENDER-AFTER-OPEN");
        Material material = material("MAT-TENDER-OPENING-GUARD");

        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-TENDER-OPENING-GUARD");
        rfq.setName("Tender RFQ opening guard");
        rfq.setStatus("OPEN");
        rfq.setTenderProcedureType("REQUEST_FOR_QUOTATION");
        rfq.setSelectionRule("LOWEST_PRICE");
        rfq.setMinQuotationsRequired(1);
        rfq.setDueDate(LocalDate.now().minusDays(1));
        rfq = rfqRepository.save(rfq);
        UUID rfqId = rfq.getId();
        RFQItem rfqItem = rfqItem(rfq, material);

        SupplierQuotation opened = quotation("QUO-TENDER-OPENED", rfqId, supplierA.getId(), BigDecimal.valueOf(200));
        quotationItem(opened, rfqItem, material.getId(), BigDecimal.TEN, BigDecimal.valueOf(20));

        commissionMember(rfqId, "Chair", "CHAIR");
        commissionMember(rfqId, "Secretary", "SECRETARY");
        commissionMember(rfqId, "Member", "MEMBER");
        var opening = tenderProcessService.openBids(rfqId, new TenderBidOpeningRequest("commission", 1, "One bid opened"));
        assertThat(opening.getAcceptedQuotationCount()).isEqualTo(1);
        tenderProcessService.runConflictChecks(rfqId);

        SupplierQuotation afterOpening = quotation("QUO-TENDER-AFTER-OPEN", rfqId, supplierB.getId(), BigDecimal.valueOf(100));
        quotationItem(afterOpening, rfqItem, material.getId(), BigDecimal.TEN, BigDecimal.TEN);

        assertThatThrownBy(() -> tenderProcessService.createEvaluationProtocol(
            rfqId,
            new TenderEvaluationProtocolRequest(afterOpening.getId(), "lowest price but not opened", "commission", "Compared by LOWEST_PRICE")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("accepted in posted tender opening protocol");
    }

    @Test
    void integrationSettingsMaskSecretsAndQueueProviderActions() {
        var settings = integrationSettingService.listSettings();
        assertThat(settings).extracting("systemCode")
            .contains(
                "SOLIQ",
                "DIDOX",
                "FAKTURA_UZ",
                "CHATGPT",
                "CLAUDE",
                "GEMINI"
            );

        IntegrationSettingUpdateRequest soliq = new IntegrationSettingUpdateRequest();
        soliq.setBearerToken("secret-token");
        soliq.setSenderTin("307123456");
        var saved = integrationSettingService.saveSetting(ExternalIntegrationSystem.SOLIQ, soliq);
        assertThat(saved.getBearerToken()).isNull();
        assertThat(saved.isBearerTokenConfigured()).isTrue();
        assertThat(saved.getSenderTin()).isEqualTo("307123456");

        var queued = integrationSettingService.enqueueAction(
            ExternalIntegrationSystem.SOLIQ,
            "TAX_INVOICE",
            new IntegrationProviderRequest(null, java.util.Map.of("invoiceNo", "SF-1"), "soliq-tax-invoice-test"),
            null
        );
        assertThat(queued.getAggregateType()).isEqualTo("SOLIQ");
        assertThat(queued.getEventType()).isEqualTo("SOLIQ.TAX_INVOICE");
        assertThat(queued.getPayload()).contains("\"targetPath\":\"/api/invoice\"");

        IntegrationSettingUpdateRequest chatgpt = new IntegrationSettingUpdateRequest();
        chatgpt.setApiKey("openai-test-key");
        chatgpt.setAiModel("gpt-test-model");
        chatgpt.setAiMaxTokens(256);
        var savedChatgpt = integrationSettingService.saveSetting(ExternalIntegrationSystem.CHATGPT, chatgpt);
        assertThat(savedChatgpt.getApiKey()).isNull();
        assertThat(savedChatgpt.isApiKeyConfigured()).isTrue();
        assertThat(savedChatgpt.getAiModel()).isEqualTo("gpt-test-model");

        var aiTest = integrationSettingService.testAiProvider(
            ExternalIntegrationSystem.CHATGPT,
            new AiPromptRequest("Проверь подключение", java.util.Map.of("module", "MM"))
        );
        assertThat(aiTest.status()).isEqualTo("MOCK_OK");
        assertThat(aiTest.response()).contains("Mock response");

        assertThatThrownBy(() -> integrationSettingService.testAiProvider(ExternalIntegrationSystem.SOLIQ, new AiPromptRequest("ping", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("AI test");
    }

    @Test
    void reportsAndIntegrationIdempotencyAreTenantScoped() {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-report-a");
            material("MAT-REPORT-A");
            var tenantAEvent = externalIntegrationService.enqueue(
                ExternalIntegrationSystem.FI,
                new IntegrationSendRequest("TENANT_SCOPE_CHECK", null, "{\"tenant\":\"a\"}", "shared-idempotency-key"),
                null
            );
            assertThat(reportService.dashboard().materials()).isEqualTo(1);

            TenantContext.setTenant("tenant-report-b");
            material("MAT-REPORT-B");
            var tenantBEvent = externalIntegrationService.enqueue(
                ExternalIntegrationSystem.FI,
                new IntegrationSendRequest("TENANT_SCOPE_CHECK", null, "{\"tenant\":\"b\"}", "shared-idempotency-key"),
                null
            );
            assertThat(reportService.dashboard().materials()).isEqualTo(1);
            assertThat(tenantBEvent.getId()).isNotEqualTo(tenantAEvent.getId());
            assertThat(tenantBEvent.getTenantId()).isEqualTo("tenant-report-b");

            TenantContext.setTenant("tenant-report-a");
            var tenantAReplay = externalIntegrationService.enqueue(
                ExternalIntegrationSystem.FI,
                new IntegrationSendRequest("TENANT_SCOPE_CHECK", null, "{\"tenant\":\"a-replay\"}", "shared-idempotency-key"),
                null
            );
            assertThat(tenantAReplay.getId()).isEqualTo(tenantAEvent.getId());
            assertThat(tenantAReplay.getTenantId()).isEqualTo("tenant-report-a");
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    @Test
    void manualIntegrationDispatchCannotUseEventFromAnotherTenant() {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-dispatch-a");
            IntegrationEvent event = integrationEventService.emit("TENANT_DISPATCH_CHECK", "Test", null, "{\"ok\":true}");

            TenantContext.setTenant("tenant-dispatch-b");
            assertThatThrownBy(() -> dispatchService.dispatch(event.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Integration event not found");
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    @Test
    void scheduledMrpRunProcessesAllTenantsWithActiveMaterials() {
        String previousTenant = TenantContext.currentTenant();
        try {
            TenantContext.setTenant("tenant-mrp-scheduler-a");
            material("MAT-MRP-SCHED-A", BigDecimal.valueOf(11));
            int beforeA = purchaseRequestCount("tenant-mrp-scheduler-a");

            TenantContext.setTenant("tenant-mrp-scheduler-b");
            material("MAT-MRP-SCHED-B", BigDecimal.valueOf(13));
            int beforeB = purchaseRequestCount("tenant-mrp-scheduler-b");

            mrpService.runAndCreatePurchaseRequestsForAllTenants();

            assertThat(purchaseRequestCount("tenant-mrp-scheduler-a")).isGreaterThan(beforeA);
            assertThat(purchaseRequestCount("tenant-mrp-scheduler-b")).isGreaterThan(beforeB);
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }

    @Test
    void purchaseOrderUpdateCreatesRevisionAndDeliveryScheduleControlMarksOverdue() {
        Supplier supplier = supplier("SUP-REVISION-1");
        Contract contract = contract(supplier, BigDecimal.valueOf(500));
        PurchaseOrder po = po("PO-REVISION-1", supplier, contract, BigDecimal.valueOf(100));
        po.setStatus(PurchaseOrderStatus.DRAFT.name());
        po = purchaseOrderRepository.save(po);

        PurchaseOrderUpdateRequest dto = new PurchaseOrderUpdateRequest();
        dto.setSupplierId(supplier.getId());
        dto.setContractId(contract.getId());
        dto.setOrderDate(po.getOrderDate());
        dto.setExpectedDeliveryDate(po.getExpectedDeliveryDate().plusDays(2));
        dto.setTotalAmount(BigDecimal.valueOf(120));
        dto.setCurrencyCode("UZS");
        purchaseOrderService.update(po.getId(), dto);

        var revisions = purchaseOrderService.revisions(po.getId());
        assertThat(revisions).hasSize(1);
        assertThat(revisions.getFirst().getDiffJson()).contains("totalAmount").contains("expectedDeliveryDate");

        DeliveryScheduleCreateRequest schedule = new DeliveryScheduleCreateRequest();
        schedule.setCode("SCH-PO-REVISION-1");
        schedule.setName("Revision PO schedule");
        schedule.setPurchaseOrderId(po.getId());
        schedule.setContractId(contract.getId());
        schedule.setScheduleDate(LocalDate.now().minusDays(1));
        schedule.setPlannedQuantity(BigDecimal.TEN);
        schedule.setReceivedQuantity(BigDecimal.ZERO);
        var savedSchedule = deliveryScheduleService.create(schedule);
        var controlled = deliveryScheduleService.control(savedSchedule.getId());
        assertThat(controlled.getStatus()).isEqualTo("OVERDUE");
        assertThat(controlled.isOverdueFlag()).isTrue();
    }

    private void approveAll(String moduleKey, UUID entityId) {
        var instance = approvalEngineService.currentInstance(moduleKey, entityId);
        while (instance.approvalStatus() != ApprovalInstanceStatus.APPROVED) {
            var task = instance.tasks().stream()
                .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
                .findFirst()
                .orElseThrow();
            instance = approvalEngineService.approveTask(task.id(), "ok");
        }
    }

    private void ensureApprovalFlow(String moduleKey, String name, String stepCodePrefix, String approverRole) {
        ApprovalFlow saved = approvalFlowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            moduleKey
        ).orElseGet(() -> {
            ApprovalFlow flow = new ApprovalFlow();
            flow.setCode("TEST-FLOW-" + UUID.randomUUID().toString().substring(0, 8));
            flow.setName(name);
            flow.setStatus("ACTIVE");
            flow.setModuleKey(moduleKey);
            flow.setActive(true);
            return approvalFlowRepository.save(flow);
        });
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

    @Test
    void contractAddendumProlongsContractAndChangesLimit() {
        Supplier supplier = supplier("SUP-ADDENDUM-1");
        Contract contract = contract(supplier, BigDecimal.valueOf(100));

        ContractAddendumCreateRequest addendum = new ContractAddendumCreateRequest();
        addendum.setCode("ADD-CONTRACT-1");
        addendum.setName("Contract prolongation");
        addendum.setContractId(contract.getId());
        addendum.setAddendumNumber("DS-1");
        addendum.setAddendumType("PROLONGATION");
        addendum.setAddendumDate(LocalDate.now());
        addendum.setNewEndDate(LocalDate.now().plusDays(120));
        addendum.setLimitDelta(BigDecimal.valueOf(50));

        var created = contractAddendumService.create(addendum);
        var posted = contractAddendumService.post(created.getId());

        Contract updated = contractRepository.findAnyById(contract.getId()).orElseThrow();
        assertThat(posted.getStatus()).isEqualTo("POSTED");
        assertThat(updated.getEndDate()).isEqualTo(LocalDate.now().plusDays(120));
        assertThat(updated.getLimitAmount()).isEqualByComparingTo("150");
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

    private com.enterprise.mm.entity.PurchaseRequest approvedPurchaseRequest(String code) {
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode(code + "-CYCLE");
        cycle.setName(code + " approved cycle");
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setPlanYear(LocalDate.now().getYear());
        cycle.setPeriodType("ANNUAL");
        cycle.setPeriodStart(LocalDate.now().withDayOfYear(1));
        cycle.setPeriodEnd(LocalDate.now().withDayOfYear(LocalDate.now().lengthOfYear()));
        cycle = demandPlanningCycleRepository.save(cycle);

        DemandPlan plan = new DemandPlan();
        plan.setCode(code + "-PLAN");
        plan.setName(code + " demand plan");
        plan.setStatus("APPROVED");
        plan.setPlanningCycle(cycle);
        plan.setPlanYear(cycle.getPlanYear());
        plan.setPeriodType(cycle.getPeriodType());
        plan.setPeriodStart(cycle.getPeriodStart());
        plan.setPeriodEnd(cycle.getPeriodEnd());
        plan = demandPlanRepository.save(plan);

        com.enterprise.mm.entity.PurchaseRequest request = new com.enterprise.mm.entity.PurchaseRequest();
        request.setCode(code);
        request.setName(code + " purchase request");
        request.setStatus(PurchaseRequestStatus.APPROVED.name());
        request.setDemandPlanId(plan.getId());
        request.setRequestType(com.enterprise.mm.enums.RequestType.GOODS);
        request.setRequiredDate(LocalDate.now().plusDays(7));
        request.setCurrencyCode("UZS");
        return purchaseRequestRepository.save(request);
    }

    private Material material(String code) {
        MaterialGroup group = defaultMaterialGroup();
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();unit.setCode("EA-"+UUID.randomUUID());unit.setName("Each");unit.setMeasurementNameShort("EA");unit.setStatus("ACTIVE");unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);unit=units.saveAndFlush(unit);
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(group.getId());material.setUnitOfMeasureId(unit.getId());material.setUnitOfMeasure(unit);
        return materialRepository.save(material);
    }

    private MaterialGroup defaultMaterialGroup() {
        return materialGroupRepository.findAll().stream()
            .filter(group -> !group.isDeleted() && "MRP-DEFAULT-GROUP".equals(group.getCode()))
            .findFirst()
            .orElseGet(() -> {
                MaterialGroup group = new MaterialGroup();
                group.setCode("MRP-DEFAULT-GROUP");
                group.setName("MRP default material group");
                group.setStatus("ACTIVE");
                return materialGroupRepository.save(group);
            });
    }

    private Material material(String code, BigDecimal reorderPoint) {
        Material material = material(code);
        material.setReorderPoint(reorderPoint);
        return materialRepository.save(material);
    }

    private SupplierQuotation quotation(String code, UUID rfqId, UUID supplierId, BigDecimal totalAmount) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode(code);
        quotation.setName(code);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfqId);
        quotation.setSupplierId(supplierId);
        quotation.setTotalAmount(totalAmount);
        quotation.setCurrencyCode("UZS");
        quotation.setPriceScore(BigDecimal.valueOf(100).subtract(totalAmount.divide(BigDecimal.TEN)));
        return supplierQuotationRepository.save(quotation);
    }

    private RFQItem rfqItem(RFQ rfq, Material material) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + rfq.getCode());
        item.setName("RFQ item");
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.TEN);
        item.setUnit("EA");
        return rfqItemRepository.save(item);
    }

    private SupplierQuotationItem quotationItem(
        SupplierQuotation quotation,
        RFQItem rfqItem,
        UUID materialId,
        BigDecimal quantity,
        BigDecimal unitPrice
    ) {
        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setCode("QI-" + quotation.getCode());
        item.setName("Quotation item");
        item.setStatus("ACTIVE");
        item.setQuotationId(quotation.getId());
        item.setRfqItemId(rfqItem.getId());
        item.setMaterialId(materialId);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setUnit(rfqItem.getUnit());
        item.setDeliveryDays(5);
        return supplierQuotationItemRepository.save(item);
    }

    private TenderCommissionMember commissionMember(UUID rfqId, String name, String role) {
        TenderCommissionMember member = new TenderCommissionMember();
        member.setCode("TCM-" + role + "-" + UUID.randomUUID().toString().substring(0, 4));
        member.setName(name);
        member.setStatus("ACTIVE");
        member.setRfqId(rfqId);
        member.setMemberName(name);
        member.setMemberRole(role);
        member.setConflictDeclarationSigned(true);
        member.setDeclaredAt(java.time.LocalDateTime.now());
        member.setActive(true);
        return tenderCommissionMemberRepository.save(member);
    }

    private int purchaseRequestCount(String tenantId) {
        return (int) purchaseRequestRepository.findAll().stream()
            .filter(request -> tenantId.equals(request.getTenantId()) && !request.isDeleted())
            .count();
    }

    private Contract contract(Supplier supplier, BigDecimal limit) {
        Contract contract = new Contract();
        contract.setCode("CON-" + supplier.getCode());
        contract.setName("Contract " + supplier.getCode());
        contract.setStatus(ContractStatus.DRAFT.name());
        contract.setSupplierId(supplier.getId());
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusDays(30));
        contract.setLimitAmount(limit);
        contract.setUsedAmount(BigDecimal.ZERO);
        contract = contractRepository.save(contract);
        contract.setStatus(ContractStatus.ACTIVE.name());
        return contractRepository.save(contract);
    }

    private PurchaseOrder po(String code, Supplier supplier, Contract contract, BigDecimal amount) {
        MaterialGroup group = defaultMaterialGroup();
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();unit.setCode("EA-"+UUID.randomUUID());unit.setName("Each");unit.setMeasurementNameShort("EA");unit.setStatus("ACTIVE");unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);unit=units.saveAndFlush(unit);
        Material material = new Material();
        material.setCode(code + "-MAT");
        material.setName(code + " material");
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(group.getId());
        material = materialRepository.save(material);

        PurchaseOrder po = new PurchaseOrder();
        po.setCode(code);
        po.setName(code);
        po.setStatus(PurchaseOrderStatus.APPROVED.name());
        po.setSupplierId(supplier.getId());
        po.setContractId(contract.getId());
        po.setOrderDate(LocalDate.now());
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(5));
        po.setTotalAmount(amount);
        po.setCurrencyCode("UZS");
        po = purchaseOrderRepository.save(po);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode(code + "-LINE-1");
        item.setName(code + " line");
        item.setStatus("OPEN");
        item.setPurchaseOrderId(po.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(amount);
        purchaseOrderItemRepository.save(item);
        return po;
    }

    private void accreditSupplier(Supplier supplier) {
        MaterialGroup group = defaultMaterialGroup();
        saveAccreditation(supplier, "GOODS", java.util.List.of(group.getId()), "Industrial materials");
        saveAccreditation(supplier, "SERVICES", java.util.List.of(), "General services");
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
        attachment.setUploadedAt(java.time.LocalDateTime.now());
        attachment.setDownloadable(true);
        attachment = attachmentRepository.save(attachment);

        document.setAttachmentId(attachment.getId());
        supplierAccreditationDocumentRepository.save(document);
    }
}
