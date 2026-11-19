package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import java.math.BigDecimal;
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
class SupplierAdmissionPrecedenceTest {
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierKycCheckRepository kycRepository;
    @Mock private SupplierRelatedPartyRepository relatedPartyRepository;
    @Mock private SupplierCorrectiveActionRepository correctiveActionRepository;
    @Mock private SupplierPortalAccountRepository portalAccountRepository;
    @Mock private SupplierActivationRecordRepository activationRepository;
    @Mock private SupplierAccreditationApplicationRepository accreditationApplicationRepository;
    @Mock private SupplierAccreditationDecisionRepository accreditationDecisionRepository;

    private SupplierAdmissionService service;
    private Supplier supplier;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("supplier-admission-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        service = new SupplierAdmissionService(
            supplierRepository,
            kycRepository,
            relatedPartyRepository,
            correctiveActionRepository,
            portalAccountRepository,
            activationRepository,
            accreditationApplicationRepository,
            accreditationDecisionRepository
        );
        supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setStatus(SupplierStatus.NEW.name());
        supplier.setCategory("C");
        supplier.setRating(BigDecimal.ZERO);
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), supplier.getId())).thenReturn(Optional.of(supplier));
        org.mockito.Mockito.lenient().when(supplierRepository.save(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(accreditationApplicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
                any(), any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(accreditationDecisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(activationRepository
            .existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
                any(), any(), any(), any())).thenReturn(false);
        org.mockito.Mockito.lenient().when(kycRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(relatedPartyRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(portalAccountRepository
            .countByTenantIdAndCompanyIdAndSupplier_IdAndStatusAndIsDeletedFalse(
                any(), any(), any(), any())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void criticalComplianceBlockWinsOverPerfectPerformance() {
        supplier.setRating(new BigDecimal("100.00"));
        supplier.setCategory("A");
        SupplierKycCheck failed = new SupplierKycCheck();
        failed.setStatus("FAILED");
        failed.setRiskLevel("CRITICAL");
        when(kycRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, supplier.getId())).thenReturn(List.of(failed));

        Supplier result = service.recalculate(supplier.getId());

        assertThat(result.getStatus()).isEqualTo(SupplierStatus.BLOCKED.name());
        assertThat(result.getBlockedReason()).contains("compliance");
    }

    @Test
    void manualBlacklistRemainsStickyUntilExplicitUnblock() {
        supplier.setStatus(SupplierStatus.BLOCKED.name());
        supplier.setBlockedReason("Manual blacklist decision BL-42");
        supplier.setRating(new BigDecimal("99.00"));
        supplier.setCategory("A");

        Supplier result = service.recalculate(supplier.getId());

        assertThat(result.getStatus()).isEqualTo(SupplierStatus.BLOCKED.name());
        assertThat(result.getBlockedReason()).isEqualTo("Manual blacklist decision BL-42");
    }

    @Test
    void blockedSupplierIsRejectedByTheSharedTransactionGate() {
        supplier.setStatus(SupplierStatus.BLOCKED.name());
        supplier.setBlockedReason("Manual blacklist decision BL-42");

        assertThat(service.operationalBlockReason(supplier.getId(), "goods receipt"))
            .contains("goods receipt")
            .contains("Manual blacklist decision BL-42");
    }

    @Test
    void complianceLimitedDoesNotDowngradeLifecycle() {
        supplier.setStatus(SupplierStatus.ACTIVE.name());
        supplier.setRating(new BigDecimal("95.00"));
        supplier.setCategory("A");
        SupplierRelatedParty pep = new SupplierRelatedParty();
        pep.setPepFlag(true);
        pep.setRiskLevel("MEDIUM");
        when(relatedPartyRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, supplier.getId())).thenReturn(List.of(pep));

        assertThat(service.recalculate(supplier.getId()).getStatus())
            .isEqualTo(SupplierStatus.ACTIVE.name());
    }

    @Test
    void performanceBlockCanRecoverButNewSupplierWithoutObservationStaysNew() {
        supplier.setStatus(SupplierStatus.BLOCKED.name());
        supplier.setBlockedReason(SupplierAdmissionService.PERFORMANCE_BLOCK_REASON_PREFIX + "42.00");
        supplier.setRating(new BigDecimal("90.00"));
        supplier.setCategory("A");

        Supplier recovered = service.recalculate(supplier.getId());

        assertThat(recovered.getStatus()).isEqualTo(SupplierStatus.NEW.name());
        assertThat(recovered.getBlockedReason()).isNull();

        recovered.setStatus(SupplierStatus.NEW.name());
        recovered.setCategory("C");
        recovered.setRating(BigDecimal.ZERO);
        Supplier untouched = service.recalculate(recovered.getId());
        assertThat(untouched.getStatus()).isEqualTo(SupplierStatus.NEW.name());
        verify(supplierRepository, org.mockito.Mockito.atLeastOnce()).save(supplier);
    }

    @Test
    void activationRecordAllowsHealthyPerformancePromotion() {
        supplier.setRating(new BigDecimal("90.00"));
        supplier.setCategory("A");
        when(activationRepository.existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, supplier.getId(), "RECORDED")).thenReturn(true);

        assertThat(service.recalculate(supplier.getId()).getStatus())
            .isEqualTo(SupplierStatus.ACTIVE.name());
    }
}
