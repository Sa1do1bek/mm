package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.IntegrationEventDirection;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.PurchaseOrderProcessStage;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceDetail;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.payload.ContractPaymentCreateRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftInvoiceFromPurchaseOrderRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftPaymentFromInvoiceRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentMarkPaidRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentSubmitRequest;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = "ADMIN")
class ProcurementPayablesFlowTest {
    @Autowired ContractInvoiceService invoiceService;
    @Autowired ContractPaymentService paymentService;
    @Autowired ProcurementPayablesService payablesService;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired ContractInvoiceRepository contractInvoiceRepository;
    @Autowired ContractPaymentRepository contractPaymentRepository;
    @Autowired GoodsReceiptLineRepository goodsReceiptLineRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired PurchaseOrderWorkspaceService purchaseOrderWorkspaceService;
    @Autowired ProcurementWorkspaceService procurementWorkspaceService;
    @Autowired ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Autowired ServiceExecutionRepository serviceExecutionRepository;
    @Autowired ServiceOrderRepository serviceOrderRepository;
    @Autowired ServiceRequestRepository serviceRequestRepository;
    @Autowired WorkCompletionActRepository workCompletionActRepository;
    @Autowired ProcurementInvoiceEvidenceService procurementInvoiceEvidenceService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void invoiceIssueAndPartialPaymentUpdateInvoiceStatus() {
        PurchaseOrder purchaseOrder = purchaseOrderWithReceiptEvidence();
        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber("INV-PAY-1");
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(new BigDecimal("100.0000"));
        var created = invoiceService.create(invoiceRequest);
        var issued = invoiceService.issue(created.getId());

        assertThat(issued.getStatus()).isEqualTo(ContractInvoiceStatus.ISSUED.name());

        ContractPaymentCreateRequest firstPayment = new ContractPaymentCreateRequest();
        firstPayment.setContractInvoiceId(issued.getId());
        firstPayment.setAmount(new BigDecimal("40.0000"));
        var payment = paymentService.create(firstPayment);
        paymentService.submitToTreasury(payment.getId(), new PaymentSubmitRequest("PAY-REF-1", null));
        seedTreasuryAck(payment.getId(), "PAY-REF-1");
        paymentService.markPaid(payment.getId(), new PaymentMarkPaidRequest("PAY-REF-1", null));

        ContractPayment stored = contractPaymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ContractPaymentStatus.PAID.name());
        ContractInvoice invoice = contractInvoiceRepository.findById(issued.getId()).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(ContractInvoiceStatus.PARTIALLY_PAID.name());
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("40.0000");

        ContractPaymentCreateRequest secondPayment = new ContractPaymentCreateRequest();
        secondPayment.setContractInvoiceId(issued.getId());
        secondPayment.setAmount(new BigDecimal("60.0000"));
        var remaining = paymentService.create(secondPayment);
        paymentService.submitToTreasury(remaining.getId(), new PaymentSubmitRequest("PAY-REF-2", null));
        seedTreasuryAck(remaining.getId(), "PAY-REF-2");
        paymentService.markPaid(remaining.getId(), new PaymentMarkPaidRequest("PAY-REF-2", null));

        invoice = contractInvoiceRepository.findById(issued.getId()).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(ContractInvoiceStatus.PAID.name());
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("100.0000");
    }

    @Test
    void purchaseOrderLinkedServiceActCannotSendToPaymentWithoutInvoice() {
        PurchaseOrder purchaseOrder = purchaseOrderWithServiceEvidence();
        ServiceAcceptanceAct act = serviceAcceptanceActRepository
            .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                purchaseOrder.getId(),
                java.util.List.of(ServiceAcceptanceActStatus.APPROVED.name())
            )
            .getFirst();

        // ServiceAcceptanceActService.sendToPayment requires attachments and finance path;
        // for PO-linked acts, payables gate is also required. Use payablesService directly.
        assertThatThrownBy(() -> payablesService.requirePayableInvoice(purchaseOrder.getId(), new BigDecimal("250")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Issued invoice");
    }

    @Test
    void paymentWithoutInvoiceIsRejected() {
        ContractPaymentCreateRequest request = new ContractPaymentCreateRequest();
        request.setContractId(UUID.randomUUID());
        request.setAmount(new BigDecimal("10.0000"));

        assertThatThrownBy(() -> paymentService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("contractInvoiceId is required");
    }

    @Test
    void payablesReadinessReflectsEvidenceAndInvoiceHeadroom() {
        PurchaseOrder purchaseOrder = purchaseOrderWithReceiptEvidence();
        var readiness = payablesService.purchaseOrderReadiness(purchaseOrder.getId());

        assertThat(readiness.evidenceAvailable()).isTrue();
        assertThat(readiness.remainingInvoiceableAmount()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void workspaceShowsPaymentStageAfterInvoiceIssued() {
        PurchaseOrder purchaseOrder = purchaseOrderWithReceiptEvidence();
        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber("INV-WS-1");
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(new BigDecimal("100.0000"));
        invoiceService.issue(invoiceService.create(invoiceRequest).getId());

        PurchaseOrderWorkspaceDetail workspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());

        assertThat(workspace.payablesSummary().evidenceAvailable()).isTrue();
        assertThat(workspace.payablesSummary().outstandingPayableAmount()).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(workspace.stages().stream().filter(stage -> stage.current()).findFirst().orElseThrow().key())
            .isEqualTo(PurchaseOrderProcessStage.PAYMENT.name());
    }

    @Test
    void fulfilledGoodsPurchaseOrderDoesNotCompleteInvoiceAndPaymentWithoutPayablesEvidence() {
        PurchaseOrder purchaseOrder = purchaseOrderWithReceiptEvidence();
        PurchaseOrderItem item = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrder.getId())
            .getFirst();
        item.setUnitPrice(null);
        purchaseOrderItemRepository.save(item);
        GoodsReceiptLine receiptLine = new GoodsReceiptLine();
        receiptLine.setTenantId(TenantContext.currentTenant());
        receiptLine.setCompanyId(companyId);
        receiptLine.setCode("GRL-PAY-NO-AMOUNT");
        receiptLine.setName("Receipt line without invoiceable amount");
        receiptLine.setStatus("POSTED");
        receiptLine.setGoodsReceiptId(UUID.randomUUID());
        receiptLine.setPurchaseOrderId(purchaseOrder.getId());
        receiptLine.setPurchaseOrderItemId(item.getId());
        receiptLine.setSupplierId(purchaseOrder.getSupplierId());
        receiptLine.setMaterialId(UUID.randomUUID());
        receiptLine.setWarehouseId(UUID.randomUUID());
        receiptLine.setOpenQuantityBefore(new BigDecimal("10"));
        receiptLine.setPhysicalReceivedQuantity(new BigDecimal("10"));
        receiptLine.setAcceptedQuantity(new BigDecimal("10"));
        receiptLine.setQualityStatus("ACCEPTED");
        receiptLine.setAccreditationStatus("ELIGIBLE");
        goodsReceiptLineRepository.save(receiptLine);

        PurchaseOrderWorkspaceDetail workspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());

        assertThat(workspace.stages().stream().filter(stage -> stage.current()).findFirst().orElseThrow().key())
            .isEqualTo(PurchaseOrderProcessStage.INVOICING.name());
        assertThat(workspace.stages().stream()
            .filter(stage -> PurchaseOrderProcessStage.INVOICING.name().equals(stage.key()))
            .findFirst()
            .orElseThrow()
            .state()).isEqualTo("IN_PROGRESS");
        assertThat(workspace.stages().stream()
            .filter(stage -> PurchaseOrderProcessStage.PAYMENT.name().equals(stage.key()))
            .findFirst()
            .orElseThrow()
            .state()).isEqualTo("NOT_STARTED");
    }

    @Test
    void serviceEvidenceSupportsInvoiceAndWorkspaceInvoicingStage() {
        PurchaseOrder purchaseOrder = purchaseOrderWithServiceEvidence();
        var summary = procurementInvoiceEvidenceService.summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isTrue();
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("250.0000");

        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber("INV-SVC-1");
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(new BigDecimal("250.0000"));
        invoiceService.issue(invoiceService.create(invoiceRequest).getId());

        PurchaseOrderWorkspaceDetail workspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());
        assertThat(workspace.stages().stream().filter(stage -> stage.current()).findFirst().orElseThrow().key())
            .isEqualTo(PurchaseOrderProcessStage.PAYMENT.name());
    }

    @Test
    void servicePurchaseOrderDraftInvoiceMovesFlowToIssueThenIssuedInvoiceMovesToPayment() {
        PurchaseOrder purchaseOrder = purchaseOrderWithServiceEvidence();

        var draft = invoiceService.createDraftFromPurchaseOrder(new DraftInvoiceFromPurchaseOrderRequest(
            purchaseOrder.getId(),
            null,
            "INV-SVC-DRAFT-1",
            LocalDate.now(),
            null
        ));

        assertThat(draft.getStatus()).isEqualTo(ContractInvoiceStatus.DRAFT.name());
        var readinessWithDraft = payablesService.purchaseOrderReadiness(purchaseOrder.getId());
        assertThat(readinessWithDraft.evidenceAmount()).isEqualByComparingTo("250.0000");
        assertThat(readinessWithDraft.invoicedAmount()).isEqualByComparingTo("0.0000");
        assertThat(readinessWithDraft.remainingInvoiceableAmount()).isEqualByComparingTo("0.0000");
        assertThat(readinessWithDraft.outstandingPayableAmount()).isEqualByComparingTo("0.0000");
        assertThat(readinessWithDraft.checks().stream()
            .noneMatch(check -> "INVOICE_HEADROOM".equals(check.code()) && "BLOCKER".equals(check.level()))).isTrue();

        var repeatedDraft = invoiceService.createDraftFromPurchaseOrder(new DraftInvoiceFromPurchaseOrderRequest(
            purchaseOrder.getId(),
            null,
            "INV-SVC-DRAFT-DUPLICATE",
            LocalDate.now(),
            null
        ));
        assertThat(repeatedDraft.getId()).isEqualTo(draft.getId());

        ContractInvoiceCreateRequest duplicate = new ContractInvoiceCreateRequest();
        duplicate.setContractId(purchaseOrder.getContractId());
        duplicate.setPurchaseOrderId(purchaseOrder.getId());
        duplicate.setInvoiceNumber("INV-SVC-DRAFT-2");
        duplicate.setInvoiceDate(LocalDate.now());
        duplicate.setAmount(new BigDecimal("250.0000"));
        assertThatThrownBy(() -> invoiceService.create(duplicate))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Active draft invoice already exists");

        var draftFlow = procurementWorkspaceService.byPurchaseOrder(purchaseOrder.getId());
        assertThat(draftFlow.nextAction()).isEqualTo("ISSUE_INVOICE");
        assertThat(draftFlow.invoices()).singleElement().satisfies(invoice ->
            assertThat(invoice.status()).isEqualTo(ContractInvoiceStatus.DRAFT.name()));
        assertThat(draftFlow.steps().stream()
            .filter(step -> "INVOICE".equals(step.key()))
            .findFirst()
            .orElseThrow()
            .action()).isEqualTo("ISSUE_INVOICE");

        PurchaseOrderWorkspaceDetail draftWorkspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());
        assertThat(draftWorkspace.availableActions().primary().key()).isEqualTo("OPEN_INVOICE");
        assertThat(draftWorkspace.availableActions().secondary().stream()
            .noneMatch(action -> "CREATE_INVOICE".equals(action.key()))).isTrue();

        var issued = invoiceService.issue(draft.getId());
        assertThat(issued.getStatus()).isEqualTo(ContractInvoiceStatus.ISSUED.name());

        var issuedReadiness = payablesService.purchaseOrderReadiness(purchaseOrder.getId());
        assertThat(issuedReadiness.evidenceAmount()).isEqualByComparingTo("250.0000");
        assertThat(issuedReadiness.invoicedAmount()).isEqualByComparingTo("250.0000");
        assertThat(issuedReadiness.remainingInvoiceableAmount()).isEqualByComparingTo("0.0000");
        assertThat(issuedReadiness.outstandingPayableAmount()).isEqualByComparingTo("250.0000");

        var issuedFlow = procurementWorkspaceService.byPurchaseOrder(purchaseOrder.getId());
        assertThat(issuedFlow.nextAction()).isEqualTo("CREATE_PAYMENT");
        assertThat(issuedFlow.invoices()).singleElement().satisfies(invoice ->
            assertThat(invoice.status()).isEqualTo(ContractInvoiceStatus.ISSUED.name()));

        PurchaseOrderWorkspaceDetail issuedWorkspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());
        assertThat(issuedWorkspace.availableActions().primary().key()).isEqualTo("CREATE_PAYMENT");
        assertThat(issuedWorkspace.availableActions().primary().targetDocument()).satisfies(document -> {
            assertThat(document.documentType()).isEqualTo("CONTRACT_INVOICE");
            assertThat(document.documentId()).isEqualTo(issued.getId());
        });

        var draftPayment = paymentService.createDraftFromInvoice(new DraftPaymentFromInvoiceRequest(
            issued.getId(),
            null,
            "PAY-SVC-DRAFT-1",
            LocalDate.now()
        ));

        PurchaseOrderWorkspaceDetail paymentWorkspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());
        assertThat(paymentWorkspace.availableActions().primary().key()).isEqualTo("OPEN_PAYMENT");
        assertThat(paymentWorkspace.availableActions().primary().targetDocument()).satisfies(document -> {
            assertThat(document.documentType()).isEqualTo("CONTRACT_PAYMENT");
            assertThat(document.documentId()).isEqualTo(draftPayment.getId());
        });
        assertThat(paymentWorkspace.availableActions().secondary()).anySatisfy(action -> {
            assertThat(action.key()).isEqualTo("SUBMIT_PAYMENT_TO_TREASURY");
            assertThat(action.targetDocument().documentId()).isEqualTo(draftPayment.getId());
        });
        assertThat(paymentWorkspace.relatedDocuments()).anySatisfy(document -> {
            assertThat(document.documentType()).isEqualTo("CONTRACT_PAYMENT");
            assertThat(document.documentId()).isEqualTo(draftPayment.getId());
        });
        assertThat(paymentWorkspace.stages().stream()
            .filter(stage -> PurchaseOrderProcessStage.PAYMENT.name().equals(stage.key()))
            .findFirst()
            .orElseThrow()
            .relatedDocuments()).anySatisfy(document -> {
                assertThat(document.documentType()).isEqualTo("CONTRACT_PAYMENT");
                assertThat(document.documentId()).isEqualTo(draftPayment.getId());
            });
    }

    @Test
    void servicePurchaseOrderPaidInvoiceAndPaymentMovesFlowToCloseout() {
        PurchaseOrder purchaseOrder = purchaseOrderWithServiceAcceptanceEvidenceOnly();

        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber("INV-SVC-PAID-1");
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(new BigDecimal("250.0000"));
        var issued = invoiceService.issue(invoiceService.create(invoiceRequest).getId());

        ContractPaymentCreateRequest paymentRequest = new ContractPaymentCreateRequest();
        paymentRequest.setContractInvoiceId(issued.getId());
        paymentRequest.setAmount(new BigDecimal("250.0000"));
        var payment = paymentService.create(paymentRequest);
        paymentService.submitToTreasury(payment.getId(), new PaymentSubmitRequest("PAY-SVC-PAID-1", null));
        seedTreasuryAck(payment.getId(), "PAY-SVC-PAID-1");
        paymentService.markPaid(payment.getId(), new PaymentMarkPaidRequest("PAY-SVC-PAID-1", null));

        var flow = procurementWorkspaceService.byPurchaseOrder(purchaseOrder.getId());

        assertThat(flow.currentStage()).isEqualTo("CLOSEOUT");
        assertThat(flow.nextAction()).isEqualTo("CREATE_CLOSEOUT");
        assertThat(flow.steps().stream()
            .filter(step -> "SERVICE_EXECUTION".equals(step.key()))
            .findFirst()
            .orElseThrow()).satisfies(step -> {
                assertThat(step.complete()).isTrue();
                assertThat(step.action()).isNull();
            });
        assertThat(flow.steps().stream()
            .filter(step -> "SERVICE_ACCEPTANCE".equals(step.key()))
            .findFirst()
            .orElseThrow()
            .complete()).isTrue();
        assertThat(flow.steps().stream()
            .filter(step -> "INVOICE".equals(step.key()))
            .findFirst()
            .orElseThrow()).satisfies(step -> {
                assertThat(step.complete()).isTrue();
                assertThat(step.action()).isNull();
            });
        assertThat(flow.steps().stream()
            .filter(step -> "PAYMENT".equals(step.key()))
            .findFirst()
            .orElseThrow()).satisfies(step -> {
                assertThat(step.complete()).isTrue();
                assertThat(step.action()).isNull();
            });
        assertThat(flow.invoices()).singleElement().satisfies(invoice ->
            assertThat(invoice.status()).isEqualTo(ContractInvoiceStatus.PAID.name()));
        assertThat(flow.payments()).singleElement().satisfies(item ->
            assertThat(item.status()).isEqualTo(ContractPaymentStatus.PAID.name()));
        assertThat(flow.payablesReadiness().remainingInvoiceableAmount()).isEqualByComparingTo("0.0000");
        assertThat(flow.payablesReadiness().checks().stream()
            .noneMatch(check -> "INVOICE_HEADROOM".equals(check.code()) && "BLOCKER".equals(check.level()))).isTrue();
    }

    @Test
    void procurementFlowIncludesPaymentsLinkedThroughPurchaseOrderInvoices() {
        PurchaseOrder purchaseOrder = purchaseOrderWithServiceAcceptanceEvidenceOnly();
        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber("INV-SVC-LINKED-PAYMENT");
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(new BigDecimal("250.0000"));
        var issued = invoiceService.issue(invoiceService.create(invoiceRequest).getId());

        ContractPayment payment = new ContractPayment();
        payment.setTenantId(TenantContext.currentTenant());
        payment.setCompanyId(companyId);
        payment.setCode("PAY-INVOICE-LINKED");
        payment.setName("Invoice-linked payment without direct PO");
        payment.setStatus(ContractPaymentStatus.DRAFT.name());
        payment.setContractId(purchaseOrder.getContractId());
        payment.setSupplierId(purchaseOrder.getSupplierId());
        payment.setContractInvoiceId(issued.getId());
        payment.setAmount(new BigDecimal("250.0000"));
        payment.setCurrencyCode("UZS");
        payment = contractPaymentRepository.save(payment);
        UUID paymentId = payment.getId();

        var flow = procurementWorkspaceService.byPurchaseOrder(purchaseOrder.getId());

        assertThat(flow.nextAction()).isEqualTo("SUBMIT_PAYMENT_TO_TREASURY");
        assertThat(flow.payments()).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo(paymentId);
            assertThat(document.status()).isEqualTo(ContractPaymentStatus.DRAFT.name());
        });
        assertThat(flow.steps().stream()
            .filter(step -> "PAYMENT".equals(step.key()))
            .findFirst()
            .orElseThrow()).satisfies(step -> {
                assertThat(step.status()).isEqualTo(ContractPaymentStatus.DRAFT.name());
                assertThat(step.action()).isEqualTo("SUBMIT_PAYMENT_TO_TREASURY");
            });
    }

    @Test
    void worksEvidenceSupportsInvoiceAndWorkspaceInvoicingStage() {
        PurchaseOrder purchaseOrder = purchaseOrderWithWorksEvidence();
        var summary = procurementInvoiceEvidenceService.summarize(purchaseOrder);

        assertThat(summary.evidenceAvailable()).isTrue();
        assertThat(summary.evidenceAmount()).isEqualByComparingTo("500.0000");

        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber("INV-WORKS-1");
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(new BigDecimal("500.0000"));
        invoiceService.issue(invoiceService.create(invoiceRequest).getId());

        PurchaseOrderWorkspaceDetail workspace = purchaseOrderWorkspaceService.detail(purchaseOrder.getId());
        assertThat(workspace.stages().stream().filter(stage -> stage.current()).findFirst().orElseThrow().key())
            .isEqualTo(PurchaseOrderProcessStage.PAYMENT.name());
    }

    @Test
    void serviceSourceDocumentPaymentSubmitsToTreasuryAndIsIdempotent() {
        PurchaseOrder purchaseOrder = purchaseOrderWithServiceEvidence();
        ServiceAcceptanceAct act = approvedServiceAct(purchaseOrder);
        issueInvoice(purchaseOrder, "INV-SVC-PAY-1", new BigDecimal("250.0000"));

        var payment = payablesService.submitSourceDocumentPayment(
            purchaseOrder.getId(),
            act.getId(),
            ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT,
            new BigDecimal("250.0000"),
            "PAY-SVC-REF-1",
            "service payment"
        );
        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.SENT_TO_TREASURY.name());
        assertThat(payment.getSourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT.name());
        assertThat(payment.getSourceDocumentId()).isEqualTo(act.getId());
        assertThat(payment.getPurchaseOrderId()).isEqualTo(purchaseOrder.getId());

        var repeated = payablesService.submitSourceDocumentPayment(
            purchaseOrder.getId(),
            act.getId(),
            ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT,
            new BigDecimal("250.0000"),
            "PAY-SVC-REF-1",
            "retry"
        );
        assertThat(repeated.getId()).isEqualTo(payment.getId());
        assertThat(repeated.getStatus()).isEqualTo(ContractPaymentStatus.SENT_TO_TREASURY.name());

        seedTreasuryAck(payment.getId(), "PAY-SVC-REF-1");
        var paid = payablesService.markSourceDocumentPaymentPaid(
            act.getId(),
            ProcurementSourceDocumentType.SERVICE_ACCEPTANCE_ACT,
            "PAY-SVC-REF-1",
            null
        );
        assertThat(paid.getStatus()).isEqualTo(ContractPaymentStatus.PAID.name());

        ContractInvoice invoice = contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), purchaseOrder.getId())
            .getFirst();
        assertThat(invoice.getStatus()).isEqualTo(ContractInvoiceStatus.PAID.name());
    }

    @Test
    void worksSourceDocumentPaymentSubmitsToTreasuryAndIsIdempotent() {
        PurchaseOrder purchaseOrder = purchaseOrderWithWorksEvidence();
        WorkCompletionAct act = approvedWorkAct(purchaseOrder);
        issueInvoice(purchaseOrder, "INV-WORKS-PAY-1", new BigDecimal("500.0000"));

        var payment = payablesService.submitSourceDocumentPayment(
            purchaseOrder.getId(),
            act.getId(),
            ProcurementSourceDocumentType.WORK_COMPLETION_ACT,
            new BigDecimal("500.0000"),
            "PAY-WORKS-REF-1",
            "works payment"
        );
        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.SENT_TO_TREASURY.name());
        assertThat(payment.getSourceDocumentType()).isEqualTo(ProcurementSourceDocumentType.WORK_COMPLETION_ACT.name());
        assertThat(payment.getSourceDocumentId()).isEqualTo(act.getId());

        var repeated = payablesService.submitSourceDocumentPayment(
            purchaseOrder.getId(),
            act.getId(),
            ProcurementSourceDocumentType.WORK_COMPLETION_ACT,
            new BigDecimal("500.0000"),
            "PAY-WORKS-REF-1",
            "retry"
        );
        assertThat(repeated.getId()).isEqualTo(payment.getId());

        seedTreasuryAck(payment.getId(), "PAY-WORKS-REF-1");
        var paid = payablesService.markSourceDocumentPaymentPaid(
            act.getId(),
            ProcurementSourceDocumentType.WORK_COMPLETION_ACT,
            "PAY-WORKS-REF-1",
            null
        );
        assertThat(paid.getStatus()).isEqualTo(ContractPaymentStatus.PAID.name());
    }

    private PurchaseOrder purchaseOrderWithReceiptEvidence() {
        Supplier supplier = new Supplier();
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-PAY-1");
        supplier.setName("Supplier Pay 1");
        supplier.setStatus("ACTIVE");
        supplier = supplierRepository.save(supplier);

        Contract contract = new Contract();
        contract.setTenantId(TenantContext.currentTenant());
        contract.setCompanyId(companyId);
        contract.setCode("CON-PAY-1");
        contract.setName("Contract Pay 1");
        contract.setSupplierId(supplier.getId());
        contract.setStatus("ACTIVE");
        contract.setCurrencyCode("UZS");
        contract = contractRepository.save(contract);

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setCode("PO-PAY-1");
        purchaseOrder.setName("PO Pay 1");
        purchaseOrder.setContractId(contract.getId());
        purchaseOrder.setSupplierId(supplier.getId());
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setStatus(PurchaseOrderStatus.RECEIVED.name());
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setTenantId(TenantContext.currentTenant());
        item.setCompanyId(companyId);
        item.setCode("POI-PAY-1");
        item.setName("PO Item Pay 1");
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("10"));
        item.setReceivedQuantity(new BigDecimal("10"));
        item.setAcceptedQuantity(new BigDecimal("10"));
        purchaseOrderItemRepository.save(item);
        return purchaseOrder;
    }

    private PurchaseOrder purchaseOrderWithServiceEvidence() {
        PurchaseOrder purchaseOrder = basePurchaseOrder(RequestType.SERVICE, "PO-PAY-SVC", PurchaseOrderStatus.SENT);
        ServiceRequest request = new ServiceRequest();
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(companyId);
        request.setCode("SR-PAY-1");
        request.setName("Service request payables");
        request.setTitle("Service request payables");
        request.setStatus("WAITING_ACCEPTANCE");
        request.setSupplierId(purchaseOrder.getSupplierId());
        request.setContractId(purchaseOrder.getContractId());
        request.setPurchaseOrderId(purchaseOrder.getId());
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(new BigDecimal("250"));
        request.setAcceptedAmount(BigDecimal.ZERO);
        request.setRejectedAmount(BigDecimal.ZERO);
        request.setCurrencyCode("UZS");
        request = serviceRequestRepository.save(request);

        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setTenantId(TenantContext.currentTenant());
        serviceOrder.setCompanyId(companyId);
        serviceOrder.setCode("SO-PAY-1");
        serviceOrder.setName("Service order payables");
        serviceOrder.setStatus("IN_PROGRESS");
        serviceOrder.setServiceRequestId(request.getId());
        serviceOrder.setSupplierId(purchaseOrder.getSupplierId());
        serviceOrder.setContractId(purchaseOrder.getContractId());
        serviceOrder.setPurchaseOrderId(purchaseOrder.getId());
        serviceOrder.setServiceType("MAINTENANCE");
        serviceOrder.setTotalAmount(new BigDecimal("250"));
        serviceOrder.setCurrencyCode("UZS");
        serviceOrder = serviceOrderRepository.save(serviceOrder);

        ServiceExecution execution = new ServiceExecution();
        execution.setTenantId(TenantContext.currentTenant());
        execution.setCompanyId(companyId);
        execution.setCode("SE-PAY-1");
        execution.setName("Accepted service execution payables");
        execution.setStatus(ServiceExecutionStatus.ACCEPTED.name());
        execution.setServiceRequestId(request.getId());
        execution.setServiceOrderId(serviceOrder.getId());
        execution.setProgressPercent(new BigDecimal("100"));
        serviceExecutionRepository.save(execution);

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setTenantId(TenantContext.currentTenant());
        act.setCompanyId(companyId);
        act.setCode("SAA-PAY-1");
        act.setName("Service acceptance payables");
        act.setStatus(ServiceAcceptanceActStatus.APPROVED.name());
        act.setServiceRequestId(request.getId());
        act.setServiceOrderId(serviceOrder.getId());
        act.setPurchaseOrderId(purchaseOrder.getId());
        act.setContractId(purchaseOrder.getContractId());
        act.setAmount(new BigDecimal("250"));
        serviceAcceptanceActRepository.save(act);
        return purchaseOrder;
    }

    private PurchaseOrder purchaseOrderWithServiceAcceptanceEvidenceOnly() {
        PurchaseOrder purchaseOrder = basePurchaseOrder(RequestType.SERVICE, "PO-PAY-SVC-ACT", PurchaseOrderStatus.SENT);
        ServiceRequest request = new ServiceRequest();
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(companyId);
        request.setCode("SR-PAY-ACT-1");
        request.setName("Service request acceptance only payables");
        request.setTitle("Service request acceptance only payables");
        request.setStatus("WAITING_ACCEPTANCE");
        request.setSupplierId(purchaseOrder.getSupplierId());
        request.setContractId(purchaseOrder.getContractId());
        request.setPurchaseOrderId(purchaseOrder.getId());
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(new BigDecimal("250"));
        request.setAcceptedAmount(BigDecimal.ZERO);
        request.setRejectedAmount(BigDecimal.ZERO);
        request.setCurrencyCode("UZS");
        request = serviceRequestRepository.save(request);

        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setTenantId(TenantContext.currentTenant());
        serviceOrder.setCompanyId(companyId);
        serviceOrder.setCode("SO-PAY-ACT-1");
        serviceOrder.setName("Service order acceptance only payables");
        serviceOrder.setStatus("IN_PROGRESS");
        serviceOrder.setServiceRequestId(request.getId());
        serviceOrder.setSupplierId(purchaseOrder.getSupplierId());
        serviceOrder.setContractId(purchaseOrder.getContractId());
        serviceOrder.setPurchaseOrderId(purchaseOrder.getId());
        serviceOrder.setServiceType("MAINTENANCE");
        serviceOrder.setTotalAmount(new BigDecimal("250"));
        serviceOrder.setCurrencyCode("UZS");
        serviceOrder = serviceOrderRepository.save(serviceOrder);

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setTenantId(TenantContext.currentTenant());
        act.setCompanyId(companyId);
        act.setCode("SAA-PAY-ACT-1");
        act.setName("Service acceptance only payables");
        act.setStatus(ServiceAcceptanceActStatus.APPROVED.name());
        act.setServiceRequestId(request.getId());
        act.setServiceOrderId(serviceOrder.getId());
        act.setPurchaseOrderId(purchaseOrder.getId());
        act.setContractId(purchaseOrder.getContractId());
        act.setAmount(new BigDecimal("250"));
        serviceAcceptanceActRepository.save(act);
        return purchaseOrder;
    }

    private PurchaseOrder purchaseOrderWithWorksEvidence() {
        PurchaseOrder purchaseOrder = basePurchaseOrder(RequestType.SERVICE, "PO-PAY-WORKS", PurchaseOrderStatus.SENT);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setTenantId(TenantContext.currentTenant());
        item.setCompanyId(companyId);
        item.setCode("POI-PAY-WORKS-1");
        item.setName("Works line payables");
        item.setStatus("ACTIVE");
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setLineType(ProcurementLineType.WORK);
        item.setServiceDescription("Install transformer foundation");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("500"));
        item = purchaseOrderItemRepository.save(item);

        WorkCompletionAct act = new WorkCompletionAct();
        act.setTenantId(TenantContext.currentTenant());
        act.setCompanyId(companyId);
        act.setCode("WCA-PAY-1");
        act.setName("Work completion payables");
        act.setStatus(WorkCompletionActStatus.APPROVED.name());
        act.setPurchaseOrderId(purchaseOrder.getId());
        act.setPurchaseOrderItemId(item.getId());
        act.setContractId(purchaseOrder.getContractId());
        act.setCompletedQuantity(BigDecimal.ONE);
        act.setAmount(new BigDecimal("500"));
        workCompletionActRepository.save(act);
        return purchaseOrder;
    }

    private ServiceAcceptanceAct approvedServiceAct(PurchaseOrder purchaseOrder) {
        return serviceAcceptanceActRepository
            .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                purchaseOrder.getId(),
                java.util.List.of(ServiceAcceptanceActStatus.APPROVED.name())
            )
            .getFirst();
    }

    private WorkCompletionAct approvedWorkAct(PurchaseOrder purchaseOrder) {
        return workCompletionActRepository
            .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                purchaseOrder.getId(),
                java.util.List.of(WorkCompletionActStatus.APPROVED.name())
            )
            .getFirst();
    }

    private void issueInvoice(PurchaseOrder purchaseOrder, String invoiceNumber, BigDecimal amount) {
        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(purchaseOrder.getContractId());
        invoiceRequest.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRequest.setInvoiceNumber(invoiceNumber);
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(amount);
        invoiceService.issue(invoiceService.create(invoiceRequest).getId());
    }

    private void seedTreasuryAck(UUID paymentId, String externalReference) {
        IntegrationEvent callback = new IntegrationEvent();
        callback.setTenantId(TenantContext.currentTenant());
        callback.setCompanyId(companyId);
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

    private PurchaseOrder basePurchaseOrder(RequestType requestType, String code, PurchaseOrderStatus status) {
        Supplier supplier = new Supplier();
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setCode(code + "-SUP");
        supplier.setName("Supplier " + code);
        supplier.setStatus("ACTIVE");
        supplier = supplierRepository.save(supplier);

        Contract contract = new Contract();
        contract.setTenantId(TenantContext.currentTenant());
        contract.setCompanyId(companyId);
        contract.setCode(code + "-CON");
        contract.setName("Contract " + code);
        contract.setSupplierId(supplier.getId());
        contract.setStatus("ACTIVE");
        contract.setCurrencyCode("UZS");
        contract = contractRepository.save(contract);

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setCode(code);
        purchaseOrder.setName("PO " + code);
        purchaseOrder.setContractId(contract.getId());
        purchaseOrder.setSupplierId(supplier.getId());
        purchaseOrder.setRequestType(requestType);
        purchaseOrder.setStatus(status.name());
        purchaseOrder.setCurrencyCode("UZS");
        return purchaseOrderRepository.save(purchaseOrder);
    }
}
