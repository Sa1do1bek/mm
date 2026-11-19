package com.enterprise.mm.procurement.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.repository.ProcurementCommandQueryRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import java.time.LocalDate;
import java.util.List;
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

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class ProcurementCommandCurrentCompanyScopeIntegrationTest {
    @Autowired ProcurementCommandQueryRepository queryRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void globalAdminCurrentCompanyDrilldownKeepsProcurementCountAndListScoped() {
        UUID currentCompany = UUID.randomUUID();
        TenantContext.setTenant("tenant-procurement-admin-scope");
        CompanyContext.setCompany(currentCompany);
        PurchaseRequest current = purchaseRequest("PR-SCOPE-CURRENT", currentCompany);
        purchaseRequest("PR-SCOPE-OTHER", UUID.randomUUID());
        purchaseRequestRepository.flush();
        authenticateGlobalAdmin();
        ProcurementCommandSearchRequest filter = ProcurementCommandSearchRequest.builder()
            .queue(ProcurementQueueKey.APPROVED_UNSOURCED)
            .search("pr-scope")
            .build();

        assertThat(queryRepository.search(filter, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);
        var scoped = queryRepository.search(filter, PageRequest.of(0, 20));

        assertThat(queryRepository.count(filter)).isEqualTo(scoped.getTotalElements()).isEqualTo(1);
        assertThat(scoped.getContent()).extracting(PurchaseRequest::getId).containsExactly(current.getId());
    }

    private PurchaseRequest purchaseRequest(String code, UUID companyId) {
        PurchaseRequest request = new PurchaseRequest();
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(companyId);
        request.setCode(code);
        request.setName(code);
        request.setStatus("APPROVED");
        request.setRequestedBy("buyer");
        request.setRequiredDate(LocalDate.now().plusDays(10));
        return purchaseRequestRepository.save(request);
    }

    private void authenticateGlobalAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
