package com.enterprise.mm.supplier.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.repository.SupplierGovernanceQueryRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupplierGovernanceQueryRepositoryIntegrationTest {
    @Autowired SupplierGovernanceQueryRepository queryRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierAccreditationApplicationRepository applicationRepository;

    @AfterEach
    void clearContext() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void countAndDrilldownUseTheSameTenantCompanyAndQueuePredicate() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-governance-contract");
        CompanyContext.setCompany(companyId);
        Supplier supplier = supplier("SUP-GOV-CONTRACT", companyId);
        Supplier otherCompanySupplier = supplier("SUP-GOV-CONTRACT-OTHER", UUID.randomUUID());
        application(supplier, companyId);
        application(otherCompanySupplier, otherCompanySupplier.getCompanyId());

        SupplierGovernanceFilter filter = new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.ACCREDITATION_REVIEW, "sup-gov-contract", 30, null);
        var page = queryRepository.search(filter, PageRequest.of(0, 20));

        assertThat(queryRepository.count(filter)).isEqualTo(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(Supplier::getId).containsExactly(supplier.getId());
    }

    @Test
    void globalAdminCurrentCompanyDrilldownKeepsSupplierCountAndListScoped() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-governance-admin-scope");
        CompanyContext.setCompany(companyId);
        Supplier current = supplier("SUP-GOV-ADMIN-CURRENT", companyId);
        Supplier other = supplier("SUP-GOV-ADMIN-OTHER", UUID.randomUUID());
        application(current, current.getCompanyId());
        application(other, other.getCompanyId());
        authenticateGlobalAdmin();
        SupplierGovernanceFilter filter = new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.ACCREDITATION_REVIEW, "sup-gov-admin", 30, null);

        assertThat(queryRepository.search(filter, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);
        var scoped = queryRepository.search(filter, PageRequest.of(0, 20));

        assertThat(queryRepository.count(filter)).isEqualTo(scoped.getTotalElements()).isEqualTo(1);
        assertThat(scoped.getContent()).extracting(Supplier::getId).containsExactly(current.getId());
    }

    @Test
    void allQueueIsTheExactCurrentCompanySupplierRegistry() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-governance-all");
        CompanyContext.setCompany(companyId);
        Supplier first = supplier("SUP-GOV-ALL-1", companyId);
        Supplier second = supplier("SUP-GOV-ALL-2", companyId);
        supplier("SUP-GOV-ALL-OTHER", UUID.randomUUID());

        SupplierGovernanceFilter filter = new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.ALL, null, 30, null);
        var page = queryRepository.searchForCompany(filter, PageRequest.of(0, 20));

        assertThat(queryRepository.countForCompany(filter)).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Supplier::getId)
            .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    private Supplier supplier(String code, UUID companyId) {
        Supplier supplier = new Supplier();
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setLegalName(code);
        supplier.setStatus("ACTIVE");
        return supplierRepository.saveAndFlush(supplier);
    }

    private void application(Supplier supplier, UUID companyId) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setTenantId(TenantContext.currentTenant());
        application.setCompanyId(companyId);
        application.setCode("ACC-" + supplier.getCode());
        application.setName("Review " + supplier.getCode());
        application.setStatus("SUBMITTED");
        application.setSupplierId(supplier.getId());
        application.setAccreditationType("MATERIAL_GROUP");
        applicationRepository.saveAndFlush(application);
    }

    private void authenticateGlobalAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin",
            null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
