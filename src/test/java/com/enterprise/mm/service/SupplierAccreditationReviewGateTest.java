package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.DecisionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.supplier.governance.readiness.*;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.*;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplierAccreditationReviewGateTest {
    private final UUID companyId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final SupplierAccreditationApplication application = new SupplierAccreditationApplication();
    private final SupplierAccreditationService domain = mock(SupplierAccreditationService.class);
    private final ApprovalEngineService engine = mock(ApprovalEngineService.class);
    private final SupplierAccreditationApplicationRepository applications = mock(SupplierAccreditationApplicationRepository.class);
    private final SupplierAccreditationDecisionRepository decisions = mock(SupplierAccreditationDecisionRepository.class);
    private final SupplierEvidenceChecklistService checklist = mock(SupplierEvidenceChecklistService.class);
    private SupplierReadinessService readiness;
    private SupplierAccreditationCommandService commands;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("review-gate");
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "manager", "unused", List.of(new SimpleGrantedAuthority("ROLE_SUPPLIER_MANAGER"))));
        application.setId(UUID.randomUUID());
        application.setSupplierId(supplierId);
        application.setTenantId("review-gate");
        application.setCompanyId(companyId);
        application.setStatus("COMPLIANCE_CHECK");
        application.setAccreditationType("GOODS");
        application.setComplianceRecommendation("ACTIVE");
        application.setComplianceSnapshotJson("{\"recommendedStatus\":\"ACTIVE\"}");
        var suppliers = mock(SupplierRepository.class);
        var supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId("review-gate");
        supplier.setCompanyId(companyId);
        supplier.setStatus("NEW");
        supplier.setLegalName("Supplier");
        supplier.setTaxId("123456789");
        supplier.setRegistrationNumber("REG-1");
        supplier.setLegalAddress("Tashkent");
        supplier.setEmail("supplier@example.com");
        when(suppliers.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));
        when(applications.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "review-gate", companyId, supplierId)).thenReturn(List.of(application));
        evidence(application, EvidenceChecklistItemState.VERIFIED);
        var compliance = mock(SupplierComplianceService.class);
        when(compliance.summarize(supplierId)).thenReturn(new SupplierComplianceSummary(supplierId, 1, 0, 0, 0, "ACTIVE"));
        readiness = new SupplierReadinessService(suppliers, applications, decisions,
            mock(SupplierActivationRecordRepository.class), mock(SupplierLicenseRepository.class),
            mock(SupplierCorrectiveActionRepository.class), checklist, compliance, new SupplierReadinessPolicy());
        var idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(anyString(), eq(application.getId()), anyString(), any(),
            eq(SupplierAccreditationApplicationResponse.class), any())).thenAnswer(call ->
                ((java.util.function.Supplier<?>) call.getArgument(5)).get());
        when(domain.validateApplicationForMutation(application.getId())).thenReturn(application);
        when(domain.get(application.getId())).thenAnswer(call -> response());
        when(domain.proposeDecision(eq(application.getId()), any())).thenAnswer(call -> response());
        when(engine.start("SUPPLIER_ACCREDITATION", application.getId())).thenAnswer(call -> {
            application.setStatus("PENDING_APPROVAL");
            return null;
        });
        commands = new SupplierAccreditationCommandService(domain, idempotency, engine, readiness,
            new SupplierGovernanceActionPolicy());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOODS", "SERVICES"})
    void readyApplicationCanEnterApprovalWithoutAnAlreadyFinalDecision(String type) {
        application.setAccreditationType(type);
        assertThat(commands.submitForApproval(application.getId(), decision(), "submit").getStatus())
            .isEqualTo("PENDING_APPROVAL");
        verify(domain).proposeDecision(eq(application.getId()), any());
        verify(engine).start("SUPPLIER_ACCREDITATION", application.getId());
    }

    @Test
    void submittedApplicationCanStartReviewBeforeTheComplianceCheck() {
        application.setStatus("SUBMITTED");
        application.setComplianceRecommendation(null);
        application.setComplianceSnapshotJson(null);
        when(domain.startReview(application.getId())).thenAnswer(call -> {
            application.setStatus("UNDER_REVIEW");
            return response();
        });

        assertThat(commands.startReview(application.getId(),
            new com.enterprise.mm.payload.SupplierAccreditationCommandDtos.VersionedCommand(1L, null),
            "start-review").getStatus()).isEqualTo("UNDER_REVIEW");
        verify(domain).startReview(application.getId());
    }

    @Test
    void submittedApplicationStillNeedsVerifiedEvidenceToStartReview() {
        application.setStatus("SUBMITTED");
        application.setComplianceRecommendation(null);
        application.setComplianceSnapshotJson(null);
        evidence(application, EvidenceChecklistItemState.MISSING);

        assertThatThrownBy(() -> commands.startReview(application.getId(),
            new com.enterprise.mm.payload.SupplierAccreditationCommandDtos.VersionedCommand(1L, null),
            "missing-evidence")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("REGISTRATION");
        verify(domain, never()).startReview(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "REJECTED", "EXPIRED", "UNDER_VERIFICATION"})
    void incompleteMandatoryEvidenceCannotEnterApproval(String state) {
        evidence(application, EvidenceChecklistItemState.valueOf(state));
        assertBlocked();
    }

    @Test
    void missingComplianceSnapshotCannotEnterApproval() {
        application.setComplianceSnapshotJson(null);
        assertBlocked();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVIDENCE", "COMPLIANCE"})
    void anotherApprovedApplicationCannotSupplyThisApplicationsEvidenceOrCompliance(String missing) {
        var approved = new SupplierAccreditationApplication();
        approved.setId(UUID.randomUUID());
        approved.setTenantId("review-gate");
        approved.setCompanyId(companyId);
        approved.setSupplierId(supplierId);
        approved.setStatus("APPROVED");
        approved.setAccreditationType("GOODS");
        approved.setComplianceRecommendation("ACTIVE");
        approved.setComplianceSnapshotJson("{}");
        var recorded = new SupplierAccreditationDecision();
        recorded.setStatus("RECORDED");
        recorded.setDecision("APPROVED");
        recorded.setValidTo(LocalDate.now().plusYears(1));
        when(decisions.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "review-gate", companyId, approved.getId())).thenReturn(List.of(recorded));
        evidence(approved, EvidenceChecklistItemState.VERIFIED);
        when(applications.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "review-gate", companyId, supplierId)).thenReturn(List.of(approved, application));
        if ("COMPLIANCE".equals(missing)) {
            application.setComplianceSnapshotJson(null);
        } else {
            evidence(application, EvidenceChecklistItemState.MISSING);
        }
        assertBlocked();
    }

    @Test
    void readyLegacyApplicationCanBeDirectlyApproved() {
        when(domain.approve(eq(application.getId()), any())).thenAnswer(call -> {
            application.setStatus("APPROVED");
            return response();
        });
        assertThat(commands.approve(application.getId(), decision(), "approve").getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void pendingApplicationWithoutProposalDoesNotCompleteReview() {
        application.setStatus("PENDING_APPROVAL");
        assertThat(readiness.get(supplierId).stages()).filteredOn(s -> s.key() == StageKey.REVIEW)
            .singleElement().satisfies(s -> assertThat(s.state()).isEqualTo(StageState.IN_PROGRESS));
    }

    @Test
    void cancelledProposalDoesNotCompleteReviewOrActivateSupplier() {
        application.setStatus("PENDING_APPROVAL");
        var cancelled = new SupplierAccreditationDecision();
        cancelled.setStatus("CANCELLED");
        cancelled.setDecision("APPROVED");
        when(decisions.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "review-gate", companyId, application.getId())).thenReturn(List.of(cancelled));
        var result = readiness.get(supplierId);
        assertThat(result.stages()).filteredOn(s -> s.key() == StageKey.REVIEW)
            .singleElement().satisfies(s -> assertThat(s.state()).isEqualTo(StageState.IN_PROGRESS));
        assertThat(result.activationEligible()).isFalse();
    }

    @Test
    void missingManagerRoleCannotSubmit() {
        SecurityContextHolder.clearContext();
        assertBlocked();
    }

    @Test
    void pendingApplicationCannotSubmitAgain() {
        application.setStatus("PENDING_APPROVAL");
        assertBlocked();
    }

    @Test
    void pendingProposalCompletesReviewButDoesNotActivateOrFinalizeDecision() {
        application.setStatus("PENDING_APPROVAL");
        var proposal = new SupplierAccreditationDecision();
        proposal.setStatus("PROPOSED");
        proposal.setDecision("APPROVED");
        proposal.setValidTo(LocalDate.now().plusYears(1));
        when(decisions.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "review-gate", companyId, application.getId())).thenReturn(List.of(proposal));
        var result = readiness.get(supplierId);
        assertThat(result.stages()).filteredOn(s -> s.key() == StageKey.REVIEW)
            .singleElement().satisfies(s -> assertThat(s.state()).isEqualTo(StageState.COMPLETED));
        assertThat(result.stages()).filteredOn(s -> s.key() == StageKey.DECISION)
            .singleElement().satisfies(s -> {
                assertThat(s.state()).isEqualTo(StageState.IN_PROGRESS);
                assertThat(s.locked()).isFalse();
            });
        assertThat(result.activationEligible()).isFalse();
        assertThat(result.scopes()).singleElement().satisfies(s -> assertThat(s.effective()).isFalse());
        assertThat(result.nextAction().enabled()).isFalse();
    }

    @Test
    void directRejectionRemainsPossibleWhenEvidenceIsMissing() {
        application.setStatus("SUBMITTED");
        evidence(application, EvidenceChecklistItemState.MISSING);
        when(domain.reject(eq(application.getId()), any())).thenAnswer(call -> {
            application.setStatus("REJECTED");
            return response();
        });
        assertThat(commands.reject(application.getId(), decision(), "reject").getStatus()).isEqualTo("REJECTED");
    }

    private void assertBlocked() {
        assertThatThrownBy(() -> commands.submitForApproval(application.getId(), decision(), "blocked"))
            .isInstanceOf(BusinessException.class);
        verify(domain, never()).proposeDecision(any(), any());
        verifyNoInteractions(engine);
    }

    private void evidence(SupplierAccreditationApplication app, EvidenceChecklistItemState state) {
        when(checklist.checklist(eq(app), any())).thenReturn(List.of(new SupplierEvidenceChecklistItem(
            UUID.randomUUID(), app.getId(), "REGISTRATION", "REGISTRATION", true, false,
            UUID.randomUUID(), state, LocalDate.now().plusYears(1), "SUPPLIER_MANAGER", LocalDate.now())));
    }

    private DecisionCommand decision() {
        return new DecisionCommand(1L, "APPROVED", List.of(UUID.randomUUID()),
            LocalDate.now(), LocalDate.now().plusYears(1), "Reviewed");
    }

    private SupplierAccreditationApplicationResponse response() {
        var result = new SupplierAccreditationApplicationResponse();
        result.setId(application.getId());
        result.setVersion(1L);
        result.setStatus(application.getStatus());
        return result;
    }
}
