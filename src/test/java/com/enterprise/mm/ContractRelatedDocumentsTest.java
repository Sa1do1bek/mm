package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiAnalyzeRequest;
import com.enterprise.mm.payload.ContractInvoiceSearchRequest;
import com.enterprise.mm.payload.ContractInvoiceResponse;
import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.ContractSearchRequest;
import com.enterprise.mm.payload.DocumentAiDtos.DocumentAiAnalyzeRequest;
import com.enterprise.mm.payload.SupplierLetterSearchRequest;
import com.enterprise.mm.payload.SupplierLetterResponse;
import com.enterprise.mm.payload.SupplierLicenseCreateRequest;
import com.enterprise.mm.payload.SupplierLicenseSearchRequest;
import com.enterprise.mm.payload.SupplierLicenseResponse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.ContractAiAnalysisService;
import com.enterprise.mm.service.ContractService;
import com.enterprise.mm.service.ContractInvoiceService;
import com.enterprise.mm.service.DocumentAiAnalysisService;
import com.enterprise.mm.service.SupplierLetterService;
import com.enterprise.mm.service.SupplierLicenseService;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ContractPowerOfAttorney;
import com.enterprise.mm.entity.ContractReconciliationAct;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierLetter;
import com.enterprise.mm.entity.SupplierLicense;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractPowerOfAttorneyRepository;
import com.enterprise.mm.repository.ContractReconciliationActRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierLetterRepository;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = "ADMIN")
class ContractRelatedDocumentsTest {
    @Autowired SupplierRepository supplierRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired ContractInvoiceRepository invoiceRepository;
    @Autowired ContractPowerOfAttorneyRepository attorneyRepository;
    @Autowired ContractReconciliationActRepository reconciliationActRepository;
    @Autowired ContractPaymentRepository paymentRepository;
    @Autowired SupplierLetterRepository supplierLetterRepository;
    @Autowired SupplierLicenseRepository supplierLicenseRepository;
    @Autowired ContractService contractService;
    @Autowired ContractInvoiceService invoiceService;
    @Autowired SupplierLetterService supplierLetterService;
    @Autowired SupplierLicenseService supplierLicenseService;
    @Autowired ContractAiAnalysisService contractAiAnalysisService;
    @Autowired DocumentAiAnalysisService documentAiAnalysisService;

    @Test
    void contractUniversalSearchFindsContractBySupplierAndPurchaseOrder() {
        Supplier supplier = supplier("SUP-UNIVERSAL-RECEIPT");
        Contract contract = contract("CON-OTHER-CODE", supplier, BigDecimal.valueOf(1_000_000));
        PurchaseOrder purchaseOrder = purchaseOrder(contract, "PO-UNIVERSAL-RECEIPT");

        assertThat(contractService.search(contractsBySearch("SUP-UNIVERSAL-RECEIPT"), PageRequest.of(0, 20)).getContent())
            .extracting(ContractResponse::getId)
            .contains(contract.getId());
        assertThat(contractService.search(contractsBySearch("PO-UNIVERSAL-RECEIPT"), PageRequest.of(0, 20)).getContent())
            .extracting(ContractResponse::getId)
            .contains(contract.getId());
        assertThat(purchaseOrder.getContractId()).isEqualTo(contract.getId());
    }

    @Test
    void contractRelatedDocumentsAreFilteredByContractId() {
        Supplier supplier = supplier("SUP-RELATED-DOCS");
        Contract first = contract("CON-RELATED-1", supplier, BigDecimal.valueOf(1_000_000));
        Contract second = contract("CON-RELATED-2", supplier, BigDecimal.valueOf(2_000_000));

        PurchaseOrder purchaseOrder = purchaseOrder(first, "PO-RELATED-1");
        ContractInvoice linkedInvoice = invoice(first, "INV-RELATED-1", BigDecimal.valueOf(250_000));
        linkedInvoice.setPurchaseOrderId(purchaseOrder.getId());
        invoiceRepository.save(linkedInvoice);
        invoice(second, "INV-RELATED-2", BigDecimal.valueOf(350_000));
        attorney(first, "POA-RELATED-1");
        reconciliationAct(first, "ACT-RELATED-1");
        payment(first, "PAY-RELATED-1", BigDecimal.valueOf(150_000));
        letter(supplier, "LTR-RELATED-1");
        license(supplier, "LIC-RELATED-1", LocalDate.now().minusDays(10), LocalDate.now().plusYears(1));

        var invoices = invoiceService.search(invoiceByContract(first.getId()), PageRequest.of(0, 20));
        var supplierContracts = contractService.search(contractsBySupplier(supplier.getId()), PageRequest.of(0, 20));
        var purchaseOrderContracts = contractService.search(contractsBySupplierAndPurchaseOrder(supplier.getId(), purchaseOrder.getId()), PageRequest.of(0, 20));
        var supplierInvoices = invoiceService.search(invoicesBySupplier(supplier.getId()), PageRequest.of(0, 20));
        var purchaseOrderInvoices = invoiceService.search(invoicesByPurchaseOrder(purchaseOrder.getId()), PageRequest.of(0, 20));
        var supplierLetters = supplierLetterService.search(lettersBySupplier(supplier.getId()), PageRequest.of(0, 20));
        var supplierLicenses = supplierLicenseService.search(licensesBySupplier(supplier.getId()), PageRequest.of(0, 20));

        assertThat(invoices.getContent()).extracting(ContractInvoiceResponse::getInvoiceNumber).containsExactly("INV-RELATED-1");
        assertThat(supplierContracts.getContent()).extracting(ContractResponse::getContractNumber).containsExactlyInAnyOrder("CON-RELATED-1", "CON-RELATED-2");
        assertThat(purchaseOrderContracts.getContent()).extracting(ContractResponse::getContractNumber).containsExactly("CON-RELATED-1");
        assertThat(supplierInvoices.getContent()).extracting(ContractInvoiceResponse::getInvoiceNumber).containsExactlyInAnyOrder("INV-RELATED-1", "INV-RELATED-2");
        assertThat(purchaseOrderInvoices.getContent()).singleElement().satisfies(invoice -> {
            assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-RELATED-1");
            assertThat(invoice.getPurchaseOrderId()).isEqualTo(purchaseOrder.getId());
        });
        assertThat(supplierLetters.getContent()).extracting(SupplierLetterResponse::getLetterNumber).containsExactly("LTR-RELATED-1");
        assertThat(supplierLicenses.getContent()).extracting(SupplierLicenseResponse::getLicenseNumber).containsExactly("LIC-RELATED-1");
        assertThat(invoiceRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), first.getId())).hasSize(1);
        assertThat(invoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrder.getId())).hasSize(1);
        assertThat(attorneyRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), first.getId())).hasSize(1);
        assertThat(reconciliationActRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), first.getId())).hasSize(1);
        assertThat(paymentRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), first.getId())).hasSize(1);
        assertThat(supplierLetterRepository.findByTenantIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), supplier.getId())).hasSize(1);
        assertThat(supplierLicenseRepository.findByTenantIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), supplier.getId())).hasSize(1);
    }

    @Test
    void supplierLicenseValidatesPeriodAndUniqueNumber() {
        Supplier supplier = supplier("SUP-LICENSE-VALIDATION");
        SupplierLicenseCreateRequest first = licenseDto(supplier, "LIC-VALID-1", LocalDate.now(), LocalDate.now().plusYears(1));
        supplierLicenseService.create(first);

        SupplierLicenseCreateRequest duplicate = licenseDto(supplier, "LIC-VALID-1", LocalDate.now(), LocalDate.now().plusYears(2));
        SupplierLicenseCreateRequest invalidPeriod = licenseDto(supplier, "LIC-VALID-2", LocalDate.now().plusDays(2), LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> supplierLicenseService.create(duplicate))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
        assertThatThrownBy(() -> supplierLicenseService.create(invalidPeriod))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("validTo");
    }

    @Test
    void contractAiAnalysisCalculatesFinancialRisks() {
        Supplier supplier = supplier("SUP-CONTRACT-AI");
        Contract contract = contract("CONTRACT-AI-1", supplier, BigDecimal.valueOf(1_000_000));
        invoice(contract, "INV-CONTRACT-AI-1", BigDecimal.valueOf(250_000));
        payment(contract, "PAY-CONTRACT-AI-1", BigDecimal.valueOf(150_000));

        var response = contractAiAnalysisService.analyze(contract.getId(), new ContractAiAnalyzeRequest("CHATGPT", true, true));

        assertThat(response.providerSystem()).isEqualTo("CHATGPT");
        assertThat(response.providerStatus()).isIn("MOCK_OK", "CONFIG_ONLY", "LIVE_OK", "LIVE_ERROR", "AI_ERROR");
        assertThat(response.totalInvoices()).isEqualByComparingTo("250000");
        assertThat(response.totalPayments()).isEqualByComparingTo("150000");
        assertThat(response.unpaidAmount()).isEqualByComparingTo("100000");
        assertThat(response.findings()).anyMatch(finding -> "Есть неоплаченные суммы".equals(finding.title()));
        assertThat(response.recommendations()).isNotEmpty();
        assertThat(response.context()).isNotNull();
    }

    @Test
    void documentAiAnalysisChecksContractInvoice() {
        Supplier supplier = supplier("SUP-DOCUMENT-AI");
        Contract contract = contract("CON-DOCUMENT-AI", supplier, BigDecimal.valueOf(1_000_000));
        ContractInvoice invoice = invoice(contract, "INV-DOCUMENT-AI", BigDecimal.valueOf(250_000));

        var response = documentAiAnalysisService.analyze(new DocumentAiAnalyzeRequest("CONTRACT_INVOICE", invoice.getId(), "CHATGPT", true, true));

        assertThat(response.providerSystem()).isEqualTo("CHATGPT");
        assertThat(response.entityType()).isEqualTo("CONTRACT_INVOICE");
        assertThat(response.documentNumber()).isEqualTo("INV-DOCUMENT-AI");
        assertThat(response.summary()).contains("CONTRACT INVOICE");
        assertThat(response.findings()).isNotEmpty();
        assertThat(response.recommendations()).isNotEmpty();
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setCategory("A");
        supplier.setRating(BigDecimal.valueOf(90));
        return supplierRepository.save(supplier);
    }

    private ContractInvoiceSearchRequest invoiceByContract(UUID contractId) {
        return new ContractInvoiceSearchRequest(null, null, null, contractId, null, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ContractInvoiceSearchRequest invoicesBySupplier(UUID supplierId) {
        return new ContractInvoiceSearchRequest(null, null, supplierId, null, null, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ContractInvoiceSearchRequest invoicesByPurchaseOrder(UUID purchaseOrderId) {
        return new ContractInvoiceSearchRequest(null, null, null, null, purchaseOrderId, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ContractSearchRequest contractsBySupplier(UUID supplierId) {
        return new ContractSearchRequest(null, null, supplierId, null, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ContractSearchRequest contractsBySearch(String search) {
        return new ContractSearchRequest(search, null, null, null, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private ContractSearchRequest contractsBySupplierAndPurchaseOrder(UUID supplierId, UUID purchaseOrderId) {
        return new ContractSearchRequest(null, null, supplierId, purchaseOrderId, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private SupplierLetterSearchRequest lettersBySupplier(UUID supplierId) {
        return new SupplierLetterSearchRequest(null, null, supplierId, null, null, false);
    }

    private SupplierLicenseSearchRequest licensesBySupplier(UUID supplierId) {
        return new SupplierLicenseSearchRequest(null, null, supplierId, null, null, false, null, null, null, null);
    }

    private Contract contract(String code, Supplier supplier, BigDecimal limit) {
        Contract contract = new Contract();
        contract.setCode(code);
        contract.setName(code);
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setContractNumber(code);
        contract.setStartDate(LocalDate.now().minusDays(1));
        contract.setEndDate(LocalDate.now().plusDays(30));
        contract.setLimitAmount(limit);
        contract.setUsedAmount(BigDecimal.ZERO);
        contract.setCurrencyCode("UZS");
        return contractRepository.save(contract);
    }

    private PurchaseOrder purchaseOrder(Contract contract, String code) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setCode(code);
        purchaseOrder.setName(code);
        purchaseOrder.setStatus("RECEIVED");
        purchaseOrder.setSupplierId(contract.getSupplierId());
        purchaseOrder.setContractId(contract.getId());
        purchaseOrder.setOrderDate(LocalDate.now());
        purchaseOrder.setTotalAmount(BigDecimal.valueOf(250_000));
        purchaseOrder.setCurrencyCode(contract.getCurrencyCode());
        return purchaseOrderRepository.save(purchaseOrder);
    }

    private ContractInvoice invoice(Contract contract, String number, BigDecimal amount) {
        ContractInvoice invoice = new ContractInvoice();
        invoice.setCode(number);
        invoice.setName(number);
        invoice.setStatus("ISSUED");
        invoice.setContractId(contract.getId());
        invoice.setSupplierId(contract.getSupplierId());
        invoice.setInvoiceNumber(number);
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setDueDate(LocalDate.now().plusDays(15));
        invoice.setAmount(amount);
        invoice.setCurrencyCode("UZS");
        return invoiceRepository.save(invoice);
    }

    private void attorney(Contract contract, String number) {
        ContractPowerOfAttorney attorney = new ContractPowerOfAttorney();
        attorney.setCode(number);
        attorney.setName(number);
        attorney.setStatus("ISSUED");
        attorney.setContractId(contract.getId());
        attorney.setSupplierId(contract.getSupplierId());
        attorney.setAttorneyNumber(number);
        attorney.setIssuedTo("Warehouse receiver");
        attorney.setIssueDate(LocalDate.now());
        attorney.setValidTo(LocalDate.now().plusDays(30));
        attorneyRepository.save(attorney);
    }

    private void reconciliationAct(Contract contract, String number) {
        ContractReconciliationAct act = new ContractReconciliationAct();
        act.setCode(number);
        act.setName(number);
        act.setStatus("SIGNED");
        act.setContractId(contract.getId());
        act.setSupplierId(contract.getSupplierId());
        act.setActNumber(number);
        act.setPeriodStart(LocalDate.now().minusMonths(1));
        act.setPeriodEnd(LocalDate.now());
        act.setActDate(LocalDate.now());
        act.setClosingBalance(BigDecimal.ZERO);
        reconciliationActRepository.save(act);
    }

    private void payment(Contract contract, String number, BigDecimal amount) {
        ContractPayment payment = new ContractPayment();
        payment.setCode(number);
        payment.setName(number);
        payment.setStatus("PAID");
        payment.setContractId(contract.getId());
        payment.setSupplierId(contract.getSupplierId());
        payment.setPaymentNumber(number);
        payment.setPaymentDate(LocalDate.now());
        payment.setAmount(amount);
        payment.setCurrencyCode("UZS");
        payment.setPaymentMethod("BANK_TRANSFER");
        paymentRepository.save(payment);
    }

    private void letter(Supplier supplier, String number) {
        SupplierLetter letter = new SupplierLetter();
        letter.setCode(number);
        letter.setName(number);
        letter.setStatus("RECEIVED");
        letter.setSupplierId(supplier.getId());
        letter.setLetterNumber(number);
        letter.setLetterDate(LocalDate.now());
        letter.setDirection("INBOUND");
        letter.setSubject("Supplier delivery confirmation");
        letter.setChannel("EDO");
        supplierLetterRepository.save(letter);
    }

    private void license(Supplier supplier, String number, LocalDate validFrom, LocalDate validTo) {
        SupplierLicense license = new SupplierLicense();
        license.setCode(number);
        license.setName(number);
        license.setStatus("ACTIVE");
        license.setSupplierId(supplier.getId());
        license.setLicenseNumber(number);
        license.setLicenseType("Industrial supply license");
        license.setIssuingAuthority("Ministry of Investment, Industry and Trade");
        license.setIssueDate(validFrom);
        license.setValidFrom(validFrom);
        license.setValidTo(validTo);
        license.setScope("Industrial supply");
        license.setTerritory("Uzbekistan");
        license.setRenewalRequired(true);
        supplierLicenseRepository.save(license);
    }

    private SupplierLicenseCreateRequest licenseDto(Supplier supplier, String number, LocalDate validFrom, LocalDate validTo) {
        SupplierLicenseCreateRequest dto = new SupplierLicenseCreateRequest();
        dto.setSupplierId(supplier.getId());
        dto.setLicenseNumber(number);
        dto.setLicenseType("Industrial supply license");
        dto.setIssuingAuthority("Ministry of Investment, Industry and Trade");
        dto.setIssueDate(validFrom);
        dto.setValidFrom(validFrom);
        dto.setValidTo(validTo);
        dto.setScope("Industrial supply");
        dto.setTerritory("Uzbekistan");
        dto.setRenewalRequired(true);
        return dto;
    }
}
