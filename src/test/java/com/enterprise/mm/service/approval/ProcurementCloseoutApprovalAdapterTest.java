package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.enums.ProcurementCloseoutLiabilityDisposition;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.ProcurementCloseoutType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ProcurementCloseoutRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ProcurementCloseoutAuditHistoryService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ProcurementCloseoutApprovalAdapterTest {
    private static final UUID CLOSEOUT_ID = UUID.randomUUID();

    @Mock private ProcurementCloseoutRepository repository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private AuditService auditService;
    @Mock private SegregationOfDutyRuleService segregationOfDutyRuleService;
    @Mock private ProcurementCloseoutAuditHistoryService closeoutAuditHistoryService;
    @InjectMocks private ProcurementCloseoutApprovalAdapter adapter;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("closeout-approver", "n/a", List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT")))
        );
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void markSubmittedMovesExceptionalCloseoutToCentralApprovalPending() {
        ProcurementCloseout closeout = exceptionalCloseout(ProcurementCloseoutStatus.READY);
        stubExisting(closeout);
        when(repository.save(any(ProcurementCloseout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.markSubmitted(CLOSEOUT_ID);

        assertThat(closeout.getCloseoutStatus()).isEqualTo(ProcurementCloseoutStatus.APPROVAL_PENDING);
        assertThat(closeout.getStatus()).isEqualTo("APPROVAL_PENDING");
        assertThat(closeout.getSubmittedAt()).isNotNull();
        assertThat(closeout.getSubmittedBy()).isEqualTo("closeout-approver");
    }

    @Test
    void validateCanSubmitRejectsNormalCloseoutBecauseItDoesNotNeedCentralApproval() {
        ProcurementCloseout closeout = normalCloseout(ProcurementCloseoutStatus.READY);
        stubExisting(closeout);

        assertThatThrownBy(() -> adapter.validateCanSubmit(CLOSEOUT_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only exceptional procurement closeouts require approval");
    }

    @Test
    void markApprovedUsesSegregationGateAndRecordsApprovalState() {
        ProcurementCloseout closeout = exceptionalCloseout(ProcurementCloseoutStatus.APPROVAL_PENDING);
        stubExisting(closeout);
        when(repository.save(any(ProcurementCloseout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.markApproved(CLOSEOUT_ID, "approved");

        assertThat(closeout.getCloseoutStatus()).isEqualTo(ProcurementCloseoutStatus.APPROVED);
        assertThat(closeout.getApprovedAt()).isNotNull();
        assertThat(closeout.getApprovedBy()).isEqualTo("closeout-approver");
        verify(segregationOfDutyRuleService).validateApproval("ProcurementCloseout", closeout, "approved");
    }

    @Test
    void onReturnedMovesPendingCloseoutBackToEditableReturnedState() {
        ProcurementCloseout closeout = exceptionalCloseout(ProcurementCloseoutStatus.APPROVAL_PENDING);
        stubExisting(closeout);
        when(repository.save(any(ProcurementCloseout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.onReturned(CLOSEOUT_ID, "fix liability note");

        assertThat(closeout.getCloseoutStatus()).isEqualTo(ProcurementCloseoutStatus.RETURNED);
        assertThat(closeout.getStatus()).isEqualTo("RETURNED");
        assertThat(closeout.getCloseoutNote()).isEqualTo("fix liability note");
    }

    private ProcurementCloseout exceptionalCloseout(ProcurementCloseoutStatus status) {
        ProcurementCloseout closeout = normalCloseout(status);
        closeout.setCloseoutType(ProcurementCloseoutType.EARLY_CLOSE);
        closeout.setEarlyCloseReason("Supplier delivered a partial scope and business accepted early closure");
        closeout.setLiabilityDisposition(ProcurementCloseoutLiabilityDisposition.DISPUTED);
        closeout.setUnresolvedLiabilityAmount(BigDecimal.TEN);
        return closeout;
    }

    private ProcurementCloseout normalCloseout(ProcurementCloseoutStatus status) {
        ProcurementCloseout closeout = new ProcurementCloseout();
        closeout.setId(CLOSEOUT_ID);
        closeout.setTenantId("default");
        closeout.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        closeout.setCode("PROC-CLOSEOUT-1");
        closeout.setName("Procurement closeout");
        closeout.setProcurementProcedureId(UUID.randomUUID());
        closeout.setCloseoutType(ProcurementCloseoutType.NORMAL);
        closeout.setLiabilityDisposition(ProcurementCloseoutLiabilityDisposition.NONE);
        closeout.setCloseoutStatus(status);
        closeout.setStatus(status.name());
        return closeout;
    }

    private void stubExisting(ProcurementCloseout closeout) {
        when(repository.findById(CLOSEOUT_ID)).thenReturn(Optional.of(closeout));
    }
}
