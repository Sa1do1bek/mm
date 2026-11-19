package com.enterprise.mm.supplier.governance.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.SupplierComplianceService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierReadinessServiceTest {
    private static final String TENANT = "readiness-tenant";
    private static final UUID COMPANY = UUID.fromString("1df3c6c8-1d91-4c97-b4a8-5a75fb96290a");
    private static final UUID SUPPLIER_ID = UUID.fromString("18c294d2-3449-49d8-bc13-c41727dd50fc");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);

    @Mock SupplierRepository supplierRepository;
    @Mock SupplierAccreditationApplicationRepository applicationRepository;
    @Mock SupplierAccreditationDecisionRepository decisionRepository;
    @Mock SupplierActivationRecordRepository activationRepository;
    @Mock SupplierLicenseRepository licenseRepository;
    @Mock SupplierCorrectiveActionRepository correctiveActionRepository;
    @Mock SupplierEvidenceChecklistService checklistService;
    @Mock SupplierComplianceService complianceService;

    private SupplierReadinessService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        service = new SupplierReadinessService(
            supplierRepository,
            applicationRepository,
            decisionRepository,
            activationRepository,
            licenseRepository,
            correctiveActionRepository,
            checklistService,
            complianceService,
            new SupplierReadinessPolicy()
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void aggregatesCurrentVerifiedScopeUsingTenantAndCompanySafeQueries() {
        Supplier supplier = supplier(COMPANY);
        SupplierAccreditationApplication application = application();
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        when(applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of(application));
        when(checklistService.checklist(application, TODAY)).thenReturn(List.of(verified(application)));
        when(decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(List.of(decision(application)));
        when(complianceService.summarize(SUPPLIER_ID))
            .thenReturn(new SupplierComplianceSummary(SUPPLIER_ID, 1, 0, 0, 0, "ACTIVE"));
        when(licenseRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());
        when(correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());

        var summary = service.get(SUPPLIER_ID, TODAY);

        assertThat(summary.scopes()).singleElement().satisfies(scope ->
            assertThat(scope.effective()).isTrue());
        assertThat(summary.overallStatus().name()).isEqualTo("READY_TO_ACTIVATE");
        verify(applicationRepository)
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID);
        verify(checklistService).checklist(eq(application), eq(TODAY));
    }

    @Test
    void activeLifecycleStatusDoesNotSubstituteForAnActivationRecord() {
        Supplier supplier = supplier(COMPANY);
        supplier.setStatus("ACTIVE");
        SupplierAccreditationApplication application = application();
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        when(applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of(application));
        when(checklistService.checklist(application, TODAY)).thenReturn(List.of(verified(application)));
        when(decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(List.of(decision(application)));
        when(complianceService.summarize(SUPPLIER_ID))
            .thenReturn(new SupplierComplianceSummary(SUPPLIER_ID, 1, 0, 0, 0, "ACTIVE"));
        when(licenseRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());
        when(correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());

        var summary = service.get(SUPPLIER_ID, TODAY);

        assertThat(summary.scopes()).singleElement().satisfies(scope ->
            assertThat(scope.effective()).isTrue());
        assertThat(summary.overallStatus().name()).isEqualTo("READY_TO_ACTIVATE");
        assertThat(summary.activationEligible()).isTrue();
        assertThat(summary.stages()).filteredOn(stage -> stage.key() == SupplierReadinessEnums.StageKey.ACTIVATION)
            .singleElement().satisfies(stage -> assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.IN_PROGRESS));
        verify(applicationRepository)
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID);
        verify(checklistService).checklist(eq(application), eq(TODAY));
    }

    @Test
    void proposedDecisionDoesNotMakePendingApprovalScopeEffective() {
        Supplier supplier = supplier(COMPANY);
        SupplierAccreditationApplication application = application();
        application.setStatus("PENDING_APPROVAL");
        application.setValidFrom(TODAY);
        application.setValidTo(TODAY.plusYears(6));
        SupplierAccreditationDecision proposed = decision(application);
        proposed.setStatus("PROPOSED");
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        when(applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of(application));
        when(checklistService.checklist(application, TODAY)).thenReturn(List.of(verified(application)));
        when(decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(List.of(proposed));
        when(complianceService.summarize(SUPPLIER_ID))
            .thenReturn(new SupplierComplianceSummary(SUPPLIER_ID, 1, 0, 0, 0, "ACTIVE"));
        when(licenseRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());
        when(correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());

        var summary = service.get(SUPPLIER_ID, TODAY);

        assertThat(summary.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.applicationStatus()).isEqualTo("PENDING_APPROVAL");
            assertThat(scope.decision()).isNull();
            assertThat(scope.effective()).isFalse();
        });
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == SupplierReadinessEnums.StageKey.DECISION)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.IN_PROGRESS);
                assertThat(stage.completedItems()).isZero();
            });
    }

    @Test
    void incompleteComplianceBlocksFinalActivation() {
        Supplier supplier = supplier(COMPANY);
        SupplierAccreditationApplication application = application();
        application.setComplianceRecommendation(null);
        application.setComplianceSnapshotJson(null);
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        when(applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of(application));
        when(checklistService.checklist(application, TODAY)).thenReturn(List.of(verified(application)));
        when(decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(List.of(decision(application)));
        when(complianceService.summarize(SUPPLIER_ID))
            .thenReturn(new SupplierComplianceSummary(SUPPLIER_ID, 1, 0, 0, 0, "ACTIVE"));
        when(licenseRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());
        when(correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());

        var summary = service.get(SUPPLIER_ID, TODAY);

        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == SupplierReadinessEnums.StageKey.COMPLIANCE_KYC)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.IN_PROGRESS);
                assertThat(stage.completedItems()).isZero();
                assertThat(stage.blockers()).extracting(blocker -> blocker.code()).contains("COMPLIANCE_REQUIRED");
            });
        assertThat(summary.overallStatus()).isEqualTo(SupplierReadinessEnums.OverallStatus.NOT_READY);
        assertThat(summary.activationEligible()).isFalse();
        assertThat(summary.nextAction().code()).isEqualTo("RUN_COMPLIANCE_CHECK");
    }

    @Test
    void limitedSupplierWithActivationHistoryIsLifecycleCompatibilityOnly() {
        Supplier supplier = supplier(COMPANY);
        supplier.setStatus("LIMITED");
        SupplierAccreditationApplication application = application();
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        when(applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of(application));
        when(activationRepository
            .existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
                TENANT, COMPANY, SUPPLIER_ID, "RECORDED"))
            .thenReturn(true);
        when(checklistService.checklist(application, TODAY)).thenReturn(List.of(verified(application)));
        when(decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(List.of(decision(application)));
        when(complianceService.summarize(SUPPLIER_ID))
            .thenReturn(new SupplierComplianceSummary(SUPPLIER_ID, 1, 0, 0, 0, "ACTIVE"));
        when(licenseRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());
        when(correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TENANT, COMPANY, SUPPLIER_ID))
            .thenReturn(List.of());

        var summary = service.get(SUPPLIER_ID, TODAY);

        assertThat(summary.overallStatus()).isEqualTo(SupplierReadinessEnums.OverallStatus.ACTIVE);
        assertThat(summary.activationEligible()).isFalse();
        assertThat(summary.nextAction().code()).isEqualTo("NONE");
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == SupplierReadinessEnums.StageKey.ACTIVATION)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.COMPLETED);
                assertThat(stage.completedItems()).isOne();
            });
    }

    @Test
    void rejectsSupplierFromAnotherCompany() {
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID))
            .thenReturn(Optional.of(supplier(UUID.randomUUID())));

        assertThatThrownBy(() -> service.get(SUPPLIER_ID, TODAY))
            .isInstanceOf(NotFoundException.class);
    }

    private Supplier supplier(UUID companyId) {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(companyId);
        supplier.setStatus("NEW");
        supplier.setLegalName("Ready Supplier LLC");
        supplier.setTaxId("123456789");
        supplier.setRegistrationNumber("REG-1");
        supplier.setLegalAddress("Tashkent");
        supplier.setEmail("ready@example.com");
        supplier.setBankAccount("202080001");
        return supplier;
    }

    private SupplierAccreditationApplication application() {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setTenantId(TENANT);
        application.setCompanyId(COMPANY);
        application.setSupplierId(SUPPLIER_ID);
        application.setAccreditationType("GOODS");
        application.setRequestedScope("Electrical materials");
        application.setStatus("APPROVED");
        application.setComplianceRecommendation("ACTIVE");
        application.setComplianceSnapshotJson("{\"recommendedStatus\":\"ACTIVE\"}");
        return application;
    }

    private SupplierEvidenceChecklistItem verified(SupplierAccreditationApplication application) {
        return new SupplierEvidenceChecklistItem(
            UUID.randomUUID(),
            application.getId(),
            "REGISTRATION",
            "REGISTRATION",
            true,
            true,
            UUID.randomUUID(),
            EvidenceChecklistItemState.VERIFIED,
            TODAY.plusYears(1),
            "SUPPLIER_MANAGER",
            TODAY
        );
    }

    private SupplierAccreditationDecision decision(SupplierAccreditationApplication application) {
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setApplicationId(application.getId());
        decision.setStatus("RECORDED");
        decision.setDecision("APPROVED");
        decision.setValidFrom(TODAY.minusDays(1));
        decision.setValidTo(TODAY.plusYears(1));
        return decision;
    }
}
