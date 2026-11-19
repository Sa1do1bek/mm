package com.enterprise.mm.integration.faktura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FakturaContractMatchingTest {
    private final UUID companyId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final FakturaSourceStore store = new FakturaSourceStore(mock(JdbcTemplate.class), contracts, suppliers, companies);

    @BeforeEach
    void context() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        Company company = new Company();
        company.setTaxId("123456789");
        when(companies.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", companyId)).thenReturn(Optional.of(company));
        Supplier supplier = new Supplier();
        supplier.setTaxId("987654321");
        supplier.setCompanyId(companyId);
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", supplierId)).thenReturn(Optional.of(supplier));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void matchesOnlyPurchaseDirectionForEachDocumentType() {
        Contract contract = contract();
        when(contracts.findFakturaContractCandidates("tenant-a", companyId, "C-1"))
            .thenReturn(List.of(contract));
        assertThat(store.match(20, "C-1", "123456789", "987654321").contractId()).isEqualTo(contract.getId());
        assertThat(store.match(28, "C-1", "987654321", "123456789").contractId()).isEqualTo(contract.getId());
        assertThat(store.match(29, "C-1", "987654321", "123456789").contractId()).isEqualTo(contract.getId());
        assertThat(store.match(20, "C-1", "987654321", "123456789").state()).isEqualTo("UNMATCHED");
        assertThat(store.match(28, "C-1", "123456789", "987654321").state()).isEqualTo("UNMATCHED");
        assertThat(store.match(20, "C-1", "555555555", "987654321").state()).isEqualTo("UNMATCHED");
    }

    @Test
    void refusesAmbiguousContractNumber() {
        when(contracts.findFakturaContractCandidates("tenant-a", companyId, "C-1"))
            .thenReturn(List.of(contract(), contract()));
        assertThat(store.match(20, "C-1", "123456789", "987654321").state()).isEqualTo("AMBIGUOUS");
    }

    @Test
    void matchesBranchSupplierByBaseInnWithoutItsInternalSuffix() {
        Supplier branch = new Supplier();
        branch.setTaxId("987654321_1");
        branch.setBaseTaxId("987654321");
        branch.setSupplierType("BRANCH");
        branch.setCompanyId(companyId);
        when(suppliers.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", supplierId)).thenReturn(Optional.of(branch));
        when(contracts.findFakturaContractCandidates("tenant-a", companyId, "C-1"))
            .thenReturn(List.of(contract()));

        assertThat(store.match(20, "C-1", "123456789", "987654321").state()).isEqualTo("AUTO_MATCHED");
        assertThat(store.match(28, "C-1", "987654321", "123456789").state()).isEqualTo("AUTO_MATCHED");
        branch.setBaseTaxId(null);
        assertThat(store.match(20, "C-1", "123456789", "987654321").state()).isEqualTo("AUTO_MATCHED");
    }

    @Test
    void contractWithoutSupplierCannotAutoMatch() {
        Contract contract = contract();
        contract.setSupplierId(null);
        when(contracts.findFakturaContractCandidates("tenant-a", companyId, "C-1"))
            .thenReturn(List.of(contract));

        assertThat(store.match(20, "C-1", "123456789", "987654321").state()).isEqualTo("UNMATCHED");
    }

    @Test
    void onlyPublishedAndUnexpiredDocumentsCanBeRecommended() {
        LocalDate today = LocalDate.of(2026, 10, 26);
        assertThat(FakturaDocumentQuery.ineligibleReason("AUTO_MATCHED", null, 24, 20, today, today, today)).isNull();
        assertThat(FakturaDocumentQuery.ineligibleReason("AUTO_MATCHED", null, 23, 28, null, null, today)).isNotNull();
        assertThat(FakturaDocumentQuery.ineligibleReason("AUTO_MATCHED", null, 34, 28, null, null, today)).isNotNull();
        assertThat(FakturaDocumentQuery.ineligibleReason("AUTO_MATCHED", null, 24, 20, today, today.minusDays(1), today)).isNotNull();
        assertThat(FakturaDocumentQuery.ineligibleReason("AUTO_MATCHED", null, 24, 20, today.plusDays(1), null, today)).isNotNull();
        assertThat(FakturaDocumentQuery.ineligibleReason("UNMATCHED", "No contract", 24, 28, null, null, today)).isEqualTo("No contract");
    }

    @Test
    void parsesFakturaAndIsoDates() {
        assertThat(FakturaSourceStore.date("26.10.2026")).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(FakturaSourceStore.date("2026-10-26T12:30:00")).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    private Contract contract() {
        Contract contract = new Contract();
        contract.setId(UUID.randomUUID());
        contract.setSupplierId(supplierId);
        return contract;
    }
}
