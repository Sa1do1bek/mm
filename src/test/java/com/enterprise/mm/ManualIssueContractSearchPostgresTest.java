package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.ManualWarehouseOperationService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false",
    "mm.integrations.cbu.enabled=false"
})
@ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers
@Transactional
class ManualIssueContractSearchPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired ContractRepository contracts;
    @Autowired SupplierRepository suppliers;
    @Autowired WarehouseRepository warehouses;
    @Autowired ManualWarehouseOperationService service;
    @Autowired EntityManager entityManager;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("manual-contract-query-test");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void blankAndFilteredLookupsExecuteOnPostgres() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-01");
        supplier.setName("Steel Supplier");
        supplier.setLegalName("Steel Supplier LLC");
        supplier.setStatus("ACTIVE");
        supplier = suppliers.saveAndFlush(supplier);

        Contract contract = new Contract();
        contract.setCode("CTR-001");
        contract.setContractNumber("SH-45/2026");
        contract.setName("Steel contract");
        contract.setSupplierId(supplier.getId());
        contract.setStatus("ACTIVE");
        contract.setEndDate(LocalDate.now().plusDays(30));
        contract = contracts.saveAndFlush(contract);

        var firstPage = PageRequest.of(0, 1);
        var blank = contracts.searchContractsForManualIssue(TenantContext.currentTenant(), companyId,
            "", "", "", null, List.of(), "ACTIVE", true, LocalDate.now(), firstPage);
        assertThat(blank.getContent()).extracting(Contract::getId).containsExactly(contract.getId());
        assertThat(blank.getTotalElements()).isEqualTo(1);

        var searched = contracts.searchContractsForManualIssue(TenantContext.currentTenant(), companyId,
            "supplier", "ctr-001", "sh-45/2026", null, List.of(), "ACTIVE", true, LocalDate.now(), firstPage);
        assertThat(searched.getContent()).extracting(Contract::getId).containsExactly(contract.getId());
        assertThat(searched.getTotalElements()).isEqualTo(1);
    }

    @Test
    void searchesEncryptedSupplierInnAndKeepsPaginationAndActiveContractFilters() {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode("WH-INN");
        warehouse.setName("Receipt warehouse");
        warehouse.setStatus("ACTIVE");
        warehouse = warehouses.saveAndFlush(warehouse);

        Supplier supplier = supplierWithTaxId("SUP-INN", "309876543");
        Contract first = contractForSupplier("CTR-FIRST", supplier.getId(), "ACTIVE");
        Contract second = contractForSupplier("CTR-SECOND", supplier.getId(), "ACTIVE");
        contractForSupplier("CTR-DRAFT", supplier.getId(), "DRAFT");
        Contract expired = contractForSupplier("CTR-EXPIRED", supplier.getId(), "ACTIVE");
        expired.setEndDate(LocalDate.now().minusDays(1));
        contracts.saveAndFlush(expired);
        contractForSupplier("CTR-OTHER", supplierWithTaxId("SUP-OTHER", "301234567").getId(), "ACTIVE");
        contractForSupplier("CTR-NULL", supplierWithTaxId("SUP-NULL", null).getId(), "ACTIVE");
        Contract otherCompany = contractForSupplier("CTR-FOREIGN", supplier.getId(), "ACTIVE");
        otherCompany.setCompanyId(UUID.randomUUID());
        contracts.saveAndFlush(otherCompany);

        assertThat((String) entityManager.createNativeQuery("select tax_id from suppliers where id = :id")
            .setParameter("id", supplier.getId()).getSingleResult()).startsWith("sec:v1:");
        entityManager.clear();

        var full = service.searchContractsForIssue(warehouse.getId(), " 309876543 ", null, null, null, PageRequest.of(0, 10));
        assertThat(full.content()).extracting(row -> row.id()).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(full.totalElements()).isEqualTo(2);

        var firstPage = service.searchContractsForIssue(warehouse.getId(), "987654", null, null, null, PageRequest.of(0, 1));
        var secondPage = service.searchContractsForIssue(warehouse.getId(), "987654", null, null, null, PageRequest.of(1, 1));
        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(secondPage.totalElements()).isEqualTo(2);
        assertThat(List.of(firstPage.content().get(0).id(), secondPage.content().get(0).id()))
            .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(service.searchContractsForIssue(warehouse.getId(), "999999999", null, null, null, PageRequest.of(0, 1)).content()).isEmpty();
    }

    private Supplier supplierWithTaxId(String code, String taxId) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setTaxId(taxId);
        supplier.setStatus("ACTIVE");
        return suppliers.saveAndFlush(supplier);
    }

    private Contract contractForSupplier(String code, UUID supplierId, String status) {
        Contract contract = new Contract();
        contract.setCode(code);
        contract.setContractNumber(code);
        contract.setName(code);
        contract.setSupplierId(supplierId);
        contract.setStatus(status);
        contract.setEndDate(LocalDate.now().plusDays(30));
        return contracts.saveAndFlush(contract);
    }
}
