package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ContractPaymentMapper;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentMarkPaidRequest;
import com.enterprise.mm.payload.ContractPaymentUpdateRequest;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
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
class ContractPaymentMatchingTest {
    @Mock private ContractPaymentRepository repository;
    @Mock private ContractPaymentMapper mapper;
    @Mock private AuditService auditService;
    @Mock private ContractRepository contractRepository;
    @Mock private ContractInvoiceRepository contractInvoiceRepository;
    @Mock private ProcurementPaymentReadinessService paymentReadinessService;
    @Mock private ProcurementInvoiceEvidenceService evidenceService;
    @Mock private com.enterprise.mm.repository.PurchaseOrderRepository purchaseOrderRepository;
    @Mock private TreasuryPaymentAcknowledgementPolicy treasuryPaymentAcknowledgementPolicy;
    @Mock private TreasuryMockPaymentAcknowledgementService treasuryMockPaymentAcknowledgementService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private ProcurementAwardOutcomeRepository awardOutcomeRepository;

    private UUID contractId;
    private UUID supplierId;
    private UUID invoiceId;
    private Contract contract;
    private ContractInvoice invoice;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        contractId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        contract = new Contract();
        contract.setId(contractId);
        contract.setTenantId("default");
        contract.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        contract.setSupplierId(supplierId);
        contract.setCurrencyCode("USD");
        contract.setStatus(ContractStatus.ACTIVE.name());
        invoice = new ContractInvoice();
        invoice.setId(invoiceId);
        invoice.setTenantId("default");
        invoice.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        invoice.setContractId(contractId);
        invoice.setSupplierId(supplierId);
        invoice.setCurrencyCode("USD");
        invoice.setStatus(ContractInvoiceStatus.ISSUED.name());
        invoice.setAmount(BigDecimal.TEN);
        org.mockito.Mockito.lenient().when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        org.mockito.Mockito.lenient().when(contractInvoiceRepository.findByIdAndIsDeletedFalse(invoiceId)).thenReturn(Optional.of(invoice));
        org.mockito.Mockito.lenient().when(contractInvoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(invoice));
        org.mockito.Mockito.lenient().doNothing().when(paymentReadinessService).requirePaymentSubmit(any(), any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void rejectsPaymentWithoutInvoice() {
        ContractPayment payment = payment();
        payment.setContractInvoiceId(null);

        assertThatThrownBy(() -> service().beforeCreate(payment, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("contractInvoiceId is required");
    }

    @Test
    void rejectsCurrencyMismatchAgainstContract() {
        invoice.setCurrencyCode("EUR");
        org.mockito.Mockito.lenient().when(contractInvoiceRepository.findByIdAndIsDeletedFalse(invoiceId)).thenReturn(Optional.of(invoice));
        ContractPayment payment = payment();
        payment.setCurrencyCode("EUR");

        assertThatThrownBy(() -> service().beforeCreate(payment, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Payment currency must match contract currency");
    }

    @Test
    void derivesSupplierAndCurrencyFromInvoiceLineage() {
        ContractPayment payment = payment();

        service().beforeCreate(payment, null);

        assertThat(payment.getSupplierId()).isEqualTo(supplierId);
        assertThat(payment.getCurrencyCode()).isEqualTo("USD");
        assertThat(payment.getContractId()).isEqualTo(contractId);
    }

    @Test
    void createIgnoresCallerSuppliedPaidStatus() {
        ContractPayment payment = payment();
        payment.setStatus(ContractPaymentStatus.PAID.name());

        service().beforeCreate(payment, null);

        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.DRAFT.name());
    }

    @Test
    void standalonePoPaymentInheritsNullableContractFromInvoice() {
        UUID purchaseOrderId = UUID.randomUUID();
        invoice.setContractId(null);
        invoice.setCommercialInstrumentType(CommercialInstrumentType.STANDALONE_PO);
        invoice.setPurchaseOrderId(purchaseOrderId);
        ContractPayment payment = payment();

        service().beforeCreate(payment, null);

        assertThat(payment.getContractId()).isNull();
        assertThat(payment.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.STANDALONE_PO);
        assertThat(payment.getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(payment.getSupplierId()).isEqualTo(supplierId);
        assertThat(payment.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void frameworkCallOffPaymentInheritsCommercialInstrumentTypeFromInvoiceLineage() {
        UUID purchaseOrderId = UUID.randomUUID();
        invoice.setPurchaseOrderId(purchaseOrderId);
        invoice.setCommercialInstrumentType(null);
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setSourceType(ProcurementAwardSourceType.FRAMEWORK_CALLOFF);
        when(awardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId))
            .thenReturn(Optional.of(outcome));
        ContractPayment payment = payment();

        service().beforeCreate(payment, null);

        assertThat(payment.getCommercialInstrumentType()).isEqualTo(CommercialInstrumentType.FRAMEWORK_CALLOFF);
        assertThat(payment.getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(payment.getContractId()).isEqualTo(contractId);
    }

    @Test
    void updateKeepsOriginalContractWhenPayloadChangesContract() {
        UUID paymentId = UUID.randomUUID();
        UUID otherContractId = UUID.randomUUID();
        ContractPayment payment = payment();
        payment.setId(paymentId);
        payment.setTenantId("default");
        payment.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        payment.setStatus("DRAFT");
        payment.setContract(contract);
        payment.setSupplierId(supplierId);
        payment.setCurrencyCode("USD");
        ContractPaymentUpdateRequest update = new ContractPaymentUpdateRequest();
        update.setContractId(otherContractId);

        when(repository.findByIdAndIsDeletedFalse(paymentId)).thenReturn(Optional.of(payment));
        when(repository.save(payment)).thenReturn(payment);
        org.mockito.Mockito.doAnswer(invocation -> {
            payment.setContractId(otherContractId);
            return null;
        }).when(mapper).updateEntity(payment, update);

        service().update(paymentId, update);

        assertThat(payment.getContractId()).isEqualTo(contractId);
    }

    @Test
    void markPaidRejectsOverpaymentAtConfirmationTime() {
        ContractPayment payment = payment();
        payment.setId(UUID.randomUUID());
        payment.setTenantId("default");
        payment.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        payment.setStatus(ContractPaymentStatus.SENT_TO_TREASURY.name());
        payment.setAmount(new BigDecimal("60"));
        payment.setTreasuryReference("PAY-1");
        ContractPayment paid = payment();
        paid.setId(UUID.randomUUID());
        paid.setTenantId("default");
        paid.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        paid.setStatus(ContractPaymentStatus.PAID.name());
        paid.setAmount(new BigDecimal("60"));
        invoice.setAmount(new BigDecimal("100"));
        invoice.setStatus(ContractInvoiceStatus.PARTIALLY_PAID.name());

        when(repository.findByIdAndIsDeletedFalse(payment.getId())).thenReturn(Optional.of(payment));
        when(repository.findByTenantIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc("default", invoiceId))
            .thenReturn(List.of(paid, payment));
        doNothing().when(treasuryPaymentAcknowledgementPolicy)
            .requireSuccessfulAcknowledgement(payment.getId(), "PAY-1");

        assertThatThrownBy(() -> service().markPaid(payment.getId(), new PaymentMarkPaidRequest("PAY-1", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Payment amount must be greater than zero and not exceed invoice remaining amount");
        verify(repository, never()).save(payment);
    }

    @Test
    void markPaidRejectsPaymentWhenInvoiceNoLongerPayable() {
        ContractPayment payment = payment();
        payment.setId(UUID.randomUUID());
        payment.setTenantId("default");
        payment.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        payment.setStatus(ContractPaymentStatus.SENT_TO_TREASURY.name());
        payment.setAmount(new BigDecimal("10"));
        payment.setTreasuryReference("PAY-1");
        invoice.setStatus(ContractInvoiceStatus.PAID.name());

        when(repository.findByIdAndIsDeletedFalse(payment.getId())).thenReturn(Optional.of(payment));
        doNothing().when(treasuryPaymentAcknowledgementPolicy)
            .requireSuccessfulAcknowledgement(payment.getId(), "PAY-1");

        assertThatThrownBy(() -> service().markPaid(payment.getId(), new PaymentMarkPaidRequest("PAY-1", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Invoice must be ISSUED or PARTIALLY_PAID before payment confirmation");
        verify(repository, never()).save(payment);
    }

    private ContractPayment payment() {
        ContractPayment payment = new ContractPayment();
        payment.setContractId(contractId);
        payment.setContractInvoiceId(invoiceId);
        payment.setAmount(BigDecimal.TEN);
        payment.setCurrencyCode(null);
        payment.setStatus("DRAFT");
        return payment;
    }

    private ContractPaymentService service() {
        return new ContractPaymentService(
            repository,
            mapper,
            auditService,
            contractRepository,
            contractInvoiceRepository,
            paymentReadinessService,
            evidenceService,
            purchaseOrderRepository,
            treasuryPaymentAcknowledgementPolicy,
            treasuryMockPaymentAcknowledgementService,
            integrationEventService,
            new CommercialInstrumentTypeResolver(awardOutcomeRepository),
            new PurchaseOrderActionPolicy()
        );
    }
}
