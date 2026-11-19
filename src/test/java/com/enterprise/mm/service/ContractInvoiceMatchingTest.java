package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ContractInvoiceMapper;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.payload.ContractInvoiceResponse;
import com.enterprise.mm.payload.ContractInvoiceUpdateRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftInvoiceFromPurchaseOrderRequest;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractInvoiceMatchingTest {
    @Mock private ContractInvoiceRepository repository;
    @Mock private ContractInvoiceMapper mapper;
    @Mock private AuditService auditService;
    @Mock private ContractRepository contractRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private ProcurementInvoiceEvidenceService evidenceService;
    @Mock private ProcurementPayablesNotificationService payablesNotificationService;
    @Mock private ProcurementAwardOutcomeRepository awardOutcomeRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private BudgetItemService budgetItemService;

    private UUID contractId;
    private UUID supplierId;
    private Contract contract;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        contractId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        contract = new Contract();
        contract.setId(contractId);
        contract.setTenantId("default");
        contract.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        contract.setSupplierId(supplierId);
        contract.setCurrencyCode("USD");
        contract.setStatus(ContractStatus.ACTIVE.name());
        org.mockito.Mockito.lenient().when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void rejectsSupplierMismatchInsteadOfSilentlyReassigningInvoice() {
        ContractInvoice invoice = invoice();
        invoice.setSupplierId(UUID.randomUUID());

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Invoice supplier must match contract supplier");
    }

    @Test
    void rejectsCurrencyMismatch() {
        ContractInvoice invoice = invoice();
        invoice.setSupplierId(supplierId);
        invoice.setCurrencyCode("EUR");

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Invoice currency must match contract currency");
    }

    @Test
    void derivesSupplierAndCurrencyFromContractWhenOmitted() {
        ContractInvoice invoice = invoice();
        invoice.setSupplierId(null);
        invoice.setCurrencyCode(null);

        service().beforeCreate(invoice, null);

        assertThat(invoice.getSupplierId()).isEqualTo(supplierId);
        assertThat(invoice.getCurrencyCode()).isEqualTo("USD");
        assertThat(invoice.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.CONTRACT_CALLOFF);
    }

    @Test
    void resolvesFrameworkCallOffFromAwardOutcomeWhenLinkedToPurchaseOrder() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = linkedInvoice("DRAFT", purchaseOrder);
        invoice.setCommercialInstrumentType(null);
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setSourceType(ProcurementAwardSourceType.FRAMEWORK_CALLOFF);
        when(awardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrder.getId()))
            .thenReturn(Optional.of(outcome));

        service().beforeCreate(invoice, null);

        assertThat(invoice.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.FRAMEWORK_CALLOFF);
    }

    @Test
    void createIgnoresCallerSuppliedIssuedStatus() {
        ContractInvoice invoice = invoice();
        invoice.setStatus("ISSUED");

        service().beforeCreate(invoice, null);

        assertThat(invoice.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void rejectsPurchaseOrderFromAnotherContract() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        purchaseOrder.setContractId(UUID.randomUUID());
        ContractInvoice invoice = linkedInvoice("DRAFT", purchaseOrder);

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order must belong to invoice contract");
    }

    @Test
    void rejectsPurchaseOrderSupplierMismatch() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        purchaseOrder.setSupplierId(UUID.randomUUID());
        ContractInvoice invoice = linkedInvoice("DRAFT", purchaseOrder);

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order supplier must match invoice supplier");
    }

    @Test
    void rejectsPurchaseOrderCurrencyMismatch() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        purchaseOrder.setCurrencyCode("EUR");
        ContractInvoice invoice = linkedInvoice("DRAFT", purchaseOrder);

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order currency must match invoice currency");
    }

    @Test
    void createCannotUseCallerStatusToBypassDedicatedIssueCommand() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = linkedInvoice("ISSUED", purchaseOrder);

        service().beforeCreate(invoice, null);

        assertThat(invoice.getStatus()).isEqualTo("DRAFT");
        org.mockito.Mockito.verify(evidenceService, org.mockito.Mockito.never())
            .validateInvoiceAmount(any(), any(), any());
    }

    @Test
    void standalonePoInvoiceCanBeIssuedWithoutContract() {
        PurchaseOrder purchaseOrder = standalonePurchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = standaloneInvoice("ISSUED", purchaseOrder);

        service().beforeCreate(invoice, null);

        assertThat(invoice.getContractId()).isNull();
        assertThat(invoice.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.STANDALONE_PO);
        assertThat(invoice.getPurchaseOrderId()).isEqualTo(purchaseOrder.getId());
        assertThat(invoice.getSupplierId()).isEqualTo(supplierId);
        assertThat(invoice.getCurrencyCode()).isEqualTo("USD");
        assertThat(invoice.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void draftFromContractPurchaseOrderCarriesContractLineage() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        purchaseOrder.setCode("PO-CONTRACT-1");
        ProcurementInvoiceEvidenceService.EvidenceSummary summary = new ProcurementInvoiceEvidenceService.EvidenceSummary(
            purchaseOrder.getId(),
            RequestType.GOODS,
            true,
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(25),
            BigDecimal.valueOf(75),
            BigDecimal.ZERO,
            BigDecimal.valueOf(75),
            null,
            null
        );
        ContractInvoiceMapper realMapper = new ContractInvoiceMapper();

        when(evidenceService.summarize(purchaseOrder)).thenReturn(summary);
        when(mapper.toEntity(any(ContractInvoiceCreateRequest.class)))
            .thenAnswer(invocation -> realMapper.toEntity(invocation.getArgument(0)));
        when(repository.save(any(ContractInvoice.class))).thenAnswer(invocation -> {
            ContractInvoice invoice = invocation.getArgument(0);
            invoice.setId(UUID.randomUUID());
            invoice.setTenantId("default");
            invoice.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
            return invoice;
        });
        when(mapper.toResponse(any(ContractInvoice.class)))
            .thenAnswer(invocation -> realMapper.toResponse(invocation.getArgument(0)));

        ContractInvoiceResponse response = service().createDraftFromPurchaseOrder(
            new DraftInvoiceFromPurchaseOrderRequest(purchaseOrder.getId(), null, null, null, null)
        );

        assertThat(response.getContractId()).isEqualTo(contractId);
        assertThat(response.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.CONTRACT_CALLOFF);
        assertThat(response.getPurchaseOrderId()).isEqualTo(purchaseOrder.getId());
        assertThat(response.getAmount()).isEqualByComparingTo("75");
    }

    @Test
    void issueConsumesGoodsInvoiceBudgetProportionallyAcrossMaterialLines() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = linkedInvoice("DRAFT", purchaseOrder);
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-1");
        invoice.setAmount(new BigDecimal("50"));
        UUID firstBudgetItemId = UUID.randomUUID();
        UUID secondBudgetItemId = UUID.randomUUID();
        PurchaseOrderItem firstLine = purchaseOrderItem(purchaseOrder, firstBudgetItemId, "1", "60");
        PurchaseOrderItem secondLine = purchaseOrderItem(purchaseOrder, secondBudgetItemId, "1", "40");

        when(repository.findByIdAndIsDeletedFalse(invoice.getId())).thenReturn(Optional.of(invoice));
        when(repository.save(invoice)).thenReturn(invoice);
        when(mapper.toResponse(invoice)).thenReturn(new ContractInvoiceResponse());
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrder.getId()))
            .thenReturn(List.of(firstLine, secondLine));
        org.mockito.Mockito.doNothing().when(evidenceService)
            .validateInvoiceAmount(purchaseOrder, invoice.getAmount(), invoice.getId());

        service().issue(invoice.getId());

        org.mockito.Mockito.verify(budgetItemService).consumeIdempotent(
            eq(firstBudgetItemId),
            argThat(amount -> amount.compareTo(new BigDecimal("30")) == 0),
            eq("ContractInvoice INV-1"),
            eq("ContractInvoice"),
            eq(invoice.getId()),
            eq(firstLine.getId()),
            eq("ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:" + firstLine.getId()),
            eq("CONSUME_ISSUED")
        );
        org.mockito.Mockito.verify(budgetItemService).consumeIdempotent(
            eq(secondBudgetItemId),
            argThat(amount -> amount.compareTo(new BigDecimal("20")) == 0),
            eq("ContractInvoice INV-1"),
            eq("ContractInvoice"),
            eq(invoice.getId()),
            eq(secondLine.getId()),
            eq("ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:" + secondLine.getId()),
            eq("CONSUME_ISSUED")
        );
    }

    @Test
    void softDeleteReversesIssuedGoodsInvoiceBudgetConsumption() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = linkedInvoice("ISSUED", purchaseOrder);
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-1");
        invoice.setAmount(new BigDecimal("50"));
        UUID firstBudgetItemId = UUID.randomUUID();
        UUID secondBudgetItemId = UUID.randomUUID();
        PurchaseOrderItem firstLine = purchaseOrderItem(purchaseOrder, firstBudgetItemId, "1", "60");
        PurchaseOrderItem secondLine = purchaseOrderItem(purchaseOrder, secondBudgetItemId, "1", "40");

        when(repository.findByIdAndIsDeletedFalse(invoice.getId())).thenReturn(Optional.of(invoice));
        when(repository.save(invoice)).thenReturn(invoice);
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrder.getId()))
            .thenReturn(List.of(firstLine, secondLine));

        service().softDelete(invoice.getId());

        org.mockito.Mockito.verify(budgetItemService).reverseConsumptionIdempotent(
            eq(firstBudgetItemId),
            argThat(amount -> amount.compareTo(new BigDecimal("30")) == 0),
            eq("ContractInvoice INV-1"),
            eq("ContractInvoice"),
            eq(invoice.getId()),
            eq(firstLine.getId()),
            eq("ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:" + firstLine.getId()),
            eq("REVERSE_CANCELLED")
        );
        org.mockito.Mockito.verify(budgetItemService).reverseConsumptionIdempotent(
            eq(secondBudgetItemId),
            argThat(amount -> amount.compareTo(new BigDecimal("20")) == 0),
            eq("ContractInvoice INV-1"),
            eq("ContractInvoice"),
            eq(invoice.getId()),
            eq(secondLine.getId()),
            eq("ContractInvoice:" + invoice.getId() + ":PurchaseOrderItem:" + secondLine.getId()),
            eq("REVERSE_CANCELLED")
        );
        assertThat(invoice.isDeleted()).isTrue();
    }

    @Test
    void updateRejectsAmountChangeAfterInvoiceIssued() {
        UUID invoiceId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = linkedInvoice("ISSUED", purchaseOrder);
        invoice.setId(invoiceId);
        invoice.setTenantId("default");
        invoice.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        invoice.setContract(contract);
        invoice.setSupplierId(supplierId);
        invoice.setCurrencyCode("USD");
        invoice.setAmount(BigDecimal.TEN);
        ContractInvoiceUpdateRequest update = new ContractInvoiceUpdateRequest();
        update.setContractId(contractId);
        update.setPurchaseOrderId(purchaseOrder.getId());
        update.setSupplierId(supplierId);
        update.setCurrencyCode("USD");
        update.setAmount(new BigDecimal("11"));

        when(repository.findByIdAndIsDeletedFalse(invoiceId)).thenReturn(Optional.of(invoice));
        org.mockito.Mockito.doAnswer(invocation -> {
            invoice.setContractId(update.getContractId());
            invoice.setPurchaseOrderId(update.getPurchaseOrderId());
            invoice.setSupplierId(update.getSupplierId());
            invoice.setCurrencyCode(update.getCurrencyCode());
            invoice.setAmount(update.getAmount());
            return null;
        }).when(mapper).updateEntity(invoice, update);

        assertThatThrownBy(() -> service().update(invoiceId, update))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Issued invoice financial fields cannot be changed; cancel and create a replacement invoice");
    }

    @Test
    void infersStandalonePoInstrumentWhenContractMissingAndPurchaseOrderPresent() {
        PurchaseOrder purchaseOrder = standalonePurchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = standaloneInvoice("DRAFT", purchaseOrder);
        invoice.setCommercialInstrumentType(null);

        service().beforeCreate(invoice, null);

        assertThat(invoice.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.STANDALONE_PO);
        assertThat(invoice.getContractId()).isNull();
    }

    @Test
    void rejectsStandalonePoInvoiceAgainstContractedPurchaseOrder() {
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        ContractInvoice invoice = standaloneInvoice("DRAFT", purchaseOrder);

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Standalone PO invoice requires a purchase order without contract");
    }

    @Test
    void rejectsStandalonePoInvoiceBeforePurchaseOrderIsSentOrFulfilled() {
        PurchaseOrder purchaseOrder = standalonePurchaseOrder(PurchaseOrderStatus.APPROVED);
        ContractInvoice invoice = standaloneInvoice("DRAFT", purchaseOrder);

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Standalone PO invoice requires a sent or fulfilled purchase order");
    }

    @Test
    void rejectsStandalonePoInvoiceWithoutPurchaseRequestAuthority() {
        PurchaseOrder purchaseOrder = standalonePurchaseOrder(PurchaseOrderStatus.SENT);
        purchaseOrder.setPurchaseRequestId(null);
        ContractInvoice invoice = standaloneInvoice("DRAFT", purchaseOrder);

        assertThatThrownBy(() -> service().beforeCreate(invoice, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Standalone PO invoice requires purchase request authority");
    }

    @Test
    void mapperExposesPurchaseOrderTraceability() {
        ContractInvoice invoice = invoice();
        UUID purchaseOrderId = UUID.randomUUID();
        invoice.setPurchaseOrderId(purchaseOrderId);

        ContractInvoiceResponse response = new ContractInvoiceMapper().toResponse(invoice);

        assertThat(response.getPurchaseOrderId()).isEqualTo(purchaseOrderId);
    }

    @Test
    void updateRejectsMovingAnExistingInvoiceToAnotherContract() {
        UUID invoiceId = UUID.randomUUID();
        UUID otherContractId = UUID.randomUUID();
        ContractInvoice invoice = invoice();
        invoice.setId(invoiceId);
        invoice.setTenantId("default");
        invoice.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        invoice.setContract(contract);
        invoice.setSupplierId(supplierId);
        invoice.setCurrencyCode("USD");
        ContractInvoiceUpdateRequest update = new ContractInvoiceUpdateRequest();
        update.setContractId(otherContractId);

        when(repository.findByIdAndIsDeletedFalse(invoiceId)).thenReturn(Optional.of(invoice));
        org.mockito.Mockito.doAnswer(invocation -> {
            invoice.setContractId(otherContractId);
            return null;
        }).when(mapper).updateEntity(invoice, update);

        assertThatThrownBy(() -> service().update(invoiceId, update))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Contract invoice cannot be moved to another contract");
    }

    @Test
    void updateKeepsOriginalPurchaseOrderWhenPayloadChangesPurchaseOrder() {
        UUID invoiceId = UUID.randomUUID();
        UUID otherPurchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = purchaseOrder(PurchaseOrderStatus.SENT);
        UUID purchaseOrderId = purchaseOrder.getId();
        ContractInvoice invoice = invoice();
        invoice.setId(invoiceId);
        invoice.setTenantId("default");
        invoice.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        invoice.setContract(contract);
        invoice.setPurchaseOrderId(purchaseOrderId);
        ContractInvoiceUpdateRequest update = new ContractInvoiceUpdateRequest();
        update.setContractId(contractId);
        update.setPurchaseOrderId(otherPurchaseOrderId);

        when(repository.findByIdAndIsDeletedFalse(invoiceId)).thenReturn(Optional.of(invoice));
        when(repository.save(invoice)).thenReturn(invoice);
        when(mapper.toResponse(invoice)).thenReturn(new ContractInvoiceResponse());
        org.mockito.Mockito.doAnswer(invocation -> {
            invoice.setPurchaseOrderId(otherPurchaseOrderId);
            return null;
        }).when(mapper).updateEntity(invoice, update);

        service().update(invoiceId, update);

        assertThat(invoice.getPurchaseOrderId()).isEqualTo(purchaseOrderId);
    }

    private ContractInvoice invoice() {
        ContractInvoice invoice = new ContractInvoice();
        invoice.setTenantId("default");
        invoice.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        invoice.setContractId(contractId);
        invoice.setSupplierId(supplierId);
        invoice.setAmount(BigDecimal.TEN);
        invoice.setCurrencyCode("USD");
        invoice.setStatus("DRAFT");
        return invoice;
    }

    private ContractInvoice linkedInvoice(String status, PurchaseOrder purchaseOrder) {
        ContractInvoice invoice = invoice();
        invoice.setStatus(status);
        invoice.setPurchaseOrderId(purchaseOrder.getId());
        return invoice;
    }

    private ContractInvoice standaloneInvoice(String status, PurchaseOrder purchaseOrder) {
        ContractInvoice invoice = new ContractInvoice();
        invoice.setCommercialInstrumentType(CommercialInstrumentType.STANDALONE_PO);
        invoice.setPurchaseOrderId(purchaseOrder.getId());
        invoice.setSupplierId(supplierId);
        invoice.setAmount(BigDecimal.TEN);
        invoice.setCurrencyCode("USD");
        invoice.setStatus(status);
        return invoice;
    }

    private PurchaseOrder purchaseOrder(PurchaseOrderStatus status) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(UUID.randomUUID());
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setContractId(contractId);
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setCurrencyCode("USD");
        purchaseOrder.setRequestType(com.enterprise.mm.enums.RequestType.GOODS);
        purchaseOrder.setStatus(status.name());
        org.mockito.Mockito.lenient().when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrder.getId()))
            .thenReturn(Optional.of(purchaseOrder));
        return purchaseOrder;
    }

    private PurchaseOrderItem purchaseOrderItem(PurchaseOrder purchaseOrder, UUID budgetItemId, String quantity, String unitPrice) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setBudgetItemId(budgetItemId);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    private PurchaseOrder standalonePurchaseOrder(PurchaseOrderStatus status) {
        PurchaseOrder purchaseOrder = purchaseOrder(status);
        purchaseOrder.setContractId(null);
        purchaseOrder.setPurchaseRequestId(UUID.randomUUID());
        purchaseOrder.setTotalAmount(BigDecimal.valueOf(100));
        return purchaseOrder;
    }

    private ContractInvoiceService service() {
        return new ContractInvoiceService(
            repository,
            mapper,
            org.mockito.Mockito.mock(PurchaseOrderActionPolicy.class),
            auditService,
            contractRepository,
            purchaseOrderRepository,
            evidenceService,
            payablesNotificationService,
            new PurchaseOrderCommercialReadinessService(),
            new CommercialInstrumentTypeResolver(awardOutcomeRepository),
            purchaseOrderItemRepository,
            budgetItemService
        );
    }
}
