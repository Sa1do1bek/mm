package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ServiceWorkflowSupplierScopeIntegrationTest {
    @Autowired private ServiceWorkflowAccessService accessService;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierPortalAccountRepository accountRepository;
    @Autowired private ServiceRequestRepository requestRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void supplierManagerSeesOnlyRequestsLinkedToOwnSupplierAccount() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        String login = "linked-" + UUID.randomUUID() + "@vendor.test";
        Supplier linkedSupplier = supplier("Linked supplier");
        Supplier unrelatedSupplier = supplier("Unrelated supplier");
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setCode("SPA-" + shortId());
        account.setName("Linked supplier account");
        account.setStatus("ACTIVE");
        account.setSupplierId(linkedSupplier.getId());
        account.setLoginEmail(login);
        accountRepository.save(account);
        ServiceRequest linked = request(linkedSupplier.getId());
        ServiceRequest unrelated = request(unrelatedSupplier.getId());
        authenticate(login, "SUPPLIER_MANAGER");

        assertThat(accessService.canAccessServiceRequest(linked)).isTrue();
        assertThat(accessService.canAccessServiceRequest(unrelated)).isFalse();
    }

    private Supplier supplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-" + shortId());
        supplier.setName(name);
        supplier.setLegalName(name + " LLC");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private ServiceRequest request(UUID supplierId) {
        ServiceRequest request = new ServiceRequest();
        request.setCode("SR-" + shortId());
        request.setName("Supplier scoped request");
        request.setStatus("IN_PROGRESS");
        request.setSupplierId(supplierId);
        return requestRepository.save(request);
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }
}
