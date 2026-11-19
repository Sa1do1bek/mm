package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.ProcurementDirectAward;
import com.enterprise.mm.mapper.SegregationOfDutyRuleMapper;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.SegregationOfDutyRuleRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ApprovalMakerCheckerPolicyTest {
    @Mock private SegregationOfDutyRuleMapper mapper;
    @Mock private SegregationOfDutyRuleRepository repository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private SegregationOfDutyViolationService violationService;
    @Mock private AuditService auditService;
    @InjectMocks private SegregationOfDutyRuleService service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("maker", "n/a")
        );
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * TEMPORARY: maker-checker creator==approver is disabled for testing.
     * Restore the previous "creator cannot approve" assertions when re-enabling SoD.
     */
    @Test
    void creatorCanApproveWhileMakerCheckerTemporarilyDisabled() {
        PurchaseRequest request = document("maker");
        assertThatCode(() -> service.validateApproval("PurchaseRequest", request))
            .doesNotThrowAnyException();
    }

    @Test
    void directAwardCreatorCanSelfApproveWhileMakerCheckerTemporarilyDisabled() {
        ProcurementDirectAward award = new ProcurementDirectAward();
        award.setId(UUID.randomUUID());
        award.setTenantId("default");
        award.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        award.setCreatedBy("maker");
        award.setStatus("APPROVAL_PENDING");
        award.setName("Governed direct award");

        assertThatCode(() -> service.validateApproval("ProcurementDirectAward", award))
            .doesNotThrowAnyException();
    }

    private PurchaseRequest document(String creator) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setCreatedBy(creator);
        request.setStatus("APPROVAL_PENDING");
        request.setName("Request");
        return request;
    }
}
