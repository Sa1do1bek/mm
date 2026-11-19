package com.enterprise.mm.integration.faktura;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FakturaReceiptLinkingPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 27);
    private final ObjectMapper json = new ObjectMapper();
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private String tenant;
    private UUID company, supplier, warehouse;
    private Contract first, second;
    private FakturaSourceStore store;
    private FakturaDocumentQuery query;
    private FakturaReceiptEvidenceService evidence;

    @BeforeAll
    static void database() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void setup() {
        tenant = "faktura-receipt-" + UUID.randomUUID();
        company = UUID.randomUUID(); supplier = UUID.randomUUID(); warehouse = UUID.randomUUID();
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
        for (var entry : List.of(new Object[]{"companies", company}, new Object[]{"suppliers", supplier}, new Object[]{"warehouses", warehouse})) {
            jdbc.update("INSERT INTO public." + entry[0] + "(id,tenant_id,company_id,created_at,name,status) VALUES (?,?,?,now(),'Test','ACTIVE')",
                entry[1], tenant, company);
        }
        Company organization = new Company(); organization.setTaxId("123456789");
        when(companies.findByTenantIdAndIdAndIsDeletedFalse(tenant, company)).thenReturn(Optional.of(organization));
        Supplier vendor = new Supplier(); vendor.setCompanyId(company); vendor.setTaxId("987654321");
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse(tenant, supplier)).thenReturn(Optional.of(vendor));
        first = contract("A"); second = contract("B");
        store = new FakturaSourceStore(jdbc, contracts, suppliers, companies);
        query = new FakturaDocumentQuery(jdbc, contracts, companies, suppliers);
        evidence = new FakturaReceiptEvidenceService(query, jdbc, mock(EntityManager.class));
    }

    @AfterEach
    void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test
    void importedDocumentsAppearOnlyUnderTheirContractAndCompany() {
        UUID power = importDocument(20, "A-power", "A", 24, DATE.plusDays(10));
        UUID invoice = importDocument(28, "A-invoice", "A", 24, null);
        UUID other = importDocument(29, "B-invoice-act", "B", 24, null);
        var selected = query.candidates(first.getId(), DATE);
        assertThat(selected.powersOfAttorney()).extracting(FakturaDocumentQuery.Document::id).containsExactly(power);
        assertThat(selected.invoices()).extracting(FakturaDocumentQuery.Document::id).containsExactly(invoice);
        assertThat(selected.recommendedPowerOfAttorneyId()).isEqualTo(power);
        assertThat(selected.recommendedInvoiceId()).isEqualTo(invoice);
        assertThat(query.candidates(second.getId(), DATE).invoices()).extracting(FakturaDocumentQuery.Document::id).containsExactly(other);
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> query.candidates(first.getId(), DATE)).isInstanceOf(BusinessException.class);
    }

    @Test
    void manuallyLinkedDocumentBecomesAvailableForTheSelectedContract() {
        UUID source = importDocument(28, "unmatched-invoice", "unknown", 24, null);
        assertThat(query.candidates(first.getId(), DATE).invoices()).isEmpty();
        tx.executeWithoutResult(status -> query.linkToContract(source, first.getId()));
        assertThat(query.candidates(first.getId(), DATE).recommendedInvoiceId()).isEqualTo(source);
    }

    @Test
    void receiptStoresBothChosenDocumentsAndRechecksChangedProviderStatus() {
        UUID power = importDocument(20, "power", "A", 24, DATE.plusDays(10));
        importDocument(28, "invoice-1", "A", 24, null);
        UUID chosenInvoice = importDocument(29, "invoice-2", "A", 24, null);
        UUID receipt = receipt();
        tx.executeWithoutResult(status -> evidence.attach(first.getId(), FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT,
            receipt, DATE, power, chosenInvoice, null));
        var linked = evidence.evidence(FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt);
        assertThat(linked).extracting(FakturaReceiptEvidenceService.Evidence::fakturaDocumentId).containsExactlyInAnyOrder(power, chosenInvoice);
        assertThat(linked).allSatisfy(row -> assertThat(row.statusAtSelection()).isEqualTo(24));
        tx.executeWithoutResult(status -> evidence.recheck(FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt, DATE));
        jdbc.update("UPDATE public.faktura_source_documents SET source_status=34 WHERE id=?", chosenInvoice);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
            evidence.recheck(FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt, DATE)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("changed or expired");
    }

    @Test
    void rejectsAnotherContractsInvoiceWithoutSavingPartialEvidence() {
        UUID power = importDocument(20, "power", "A", 24, DATE.plusDays(10));
        UUID foreignInvoice = importDocument(28, "other-invoice", "B", 24, null);
        UUID receipt = receipt();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> evidence.attach(first.getId(),
            FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt, DATE, power, foreignInvoice, null)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not eligible");
        assertThat(evidence.evidence(FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt)).isEmpty();
    }

    @Test
    void expiredDocumentsRemainVisibleButCannotBeSelectedAndAmbiguousInvoicesRequireAChoice() {
        UUID expired = importDocument(20, "expired-power", "A", 24, DATE.minusDays(1));
        importDocument(28, "invoice-1", "A", 24, null);
        importDocument(29, "invoice-2", "A", 24, null);
        var selected = query.candidates(first.getId(), DATE);
        assertThat(selected.powersOfAttorney()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(expired); assertThat(row.eligible()).isFalse();
        });
        assertThat(selected.recommendedPowerOfAttorneyId()).isNull();
        assertThat(selected.recommendedInvoiceId()).isNull();
        UUID receipt = receipt();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> evidence.attach(first.getId(),
            FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt, DATE, expired, null, null)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not eligible");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> evidence.attach(first.getId(),
            FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt, DATE, null, null, null)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Select a Faktura INVOICE");
        assertThat(evidence.evidence(FakturaReceiptEvidenceService.ReceiptKind.STOCK_MOVEMENT, receipt)).isEmpty();
    }

    private Contract contract(String number) {
        Contract value = new Contract(); value.setId(UUID.randomUUID()); value.setTenantId(tenant);
        value.setCompanyId(company); value.setSupplierId(supplier); value.setContractNumber(number);
        jdbc.update("INSERT INTO public.contracts(id,tenant_id,company_id,supplier_id,contract_number,created_at,name,status) "
            + "VALUES (?,?,?,?,?,now(),'Test contract','ACTIVE')", value.getId(), tenant, company, supplier, number);
        when(contracts.findByIdAndIsDeletedFalse(value.getId())).thenReturn(Optional.of(value));
        when(contracts.findFakturaContractCandidates(tenant, company, number)).thenReturn(List.of(value));
        return value;
    }

    private UUID importDocument(int type, String uniqueId, String contractNumber, int status, LocalDate validTo) {
        var header = json.createObjectNode().put("uniqueId", uniqueId).put("status", status);
        var content = json.createObjectNode();
        content.put(type == 20 ? "documentNumber" : "invoiceNumber", uniqueId);
        content.put(type == 20 ? "documentDate" : "invoiceDate", DATE.minusDays(5).toString());
        content.put(type == 20 ? "contractNumber" : "invoiceContractNumber", contractNumber);
        content.put("ownerInn", type == 20 ? "123456789" : "987654321");
        content.put("contractorInn", type == 20 ? "987654321" : "123456789");
        if (validTo != null) content.put("documentValidDate", validTo.toString());
        tx.executeWithoutResult(transaction -> store.upsert(type, header, content));
        return jdbc.queryForObject("SELECT id FROM public.faktura_source_documents WHERE tenant_id=? AND source_unique_id=?",
            UUID.class, tenant, uniqueId);
    }

    private UUID receipt() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.stock_movement_documents(id,tenant_id,company_id,warehouse_id,movement_type,movement_date,created_at,name,status) "
            + "VALUES (?,?,?,?,'RECEIPT',?,now(),'Test receipt','DRAFT')", id, tenant, company, warehouse, java.sql.Date.valueOf(DATE));
        return id;
    }
}
