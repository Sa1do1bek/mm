package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.enums.SupplierCorrectiveActionStatus;
import com.enterprise.mm.enums.SupplierKycStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierCorrectiveActionMapper;
import com.enterprise.mm.mapper.SupplierKycCheckMapper;
import com.enterprise.mm.mapper.SupplierRelatedPartyMapper;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionUpdateRequest;
import com.enterprise.mm.payload.SupplierKycCheckCreateRequest;
import com.enterprise.mm.payload.SupplierKycCheckUpdateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartyCreateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartyUpdateRequest;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import com.enterprise.mm.security.AccessScope;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierComplianceLifecycleMutationTest {
    private static final String TENANT = "supplier-compliance-lifecycle";

    @Mock private SupplierKycCheckRepository kycRepository;
    @Mock private SupplierCorrectiveActionRepository correctiveActionRepository;
    @Mock private SupplierRelatedPartyRepository relatedPartyRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private SupplierComplianceService complianceService;
    @Mock private AuditService auditService;

    private UUID companyId;
    private UUID supplierId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        lenient().when(complianceService.normalizeCode(
                nullable(String.class), anyString(), anyString(), any(String[].class)))
            .thenAnswer(invocation -> {
                String value = invocation.getArgument(0);
                String fallback = invocation.getArgument(1);
                return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
            });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
    }

    @Test
    void kycCreateAlwaysStartsAsDraftWithoutForgedDecisionState() {
        Supplier supplier = supplier(supplierId, companyId);
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier);
        when(kycRepository.save(any(SupplierKycCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierKycCheckCreateRequest request = new SupplierKycCheckCreateRequest();
        request.setSupplierId(supplierId);
        request.setCheckType("SANCTIONS");
        request.setStatus(SupplierKycStatus.PASSED.name());
        request.setCheckedAt(LocalDateTime.of(2026, 7, 15, 12, 0));
        request.setRemediationRequired(true);

        var response = kycService().create(request);

        assertThat(response.getStatus()).isEqualTo(SupplierKycStatus.DRAFT.name());
        assertThat(response.getCheckedAt()).isNull();
        assertThat(response.isRemediationRequired()).isFalse();
        assertThat(response.getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void correctiveActionCreateAlwaysStartsAsDraftWithoutForgedCompletionState() {
        Supplier supplier = supplier(supplierId, companyId);
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier);
        when(correctiveActionRepository.save(any(SupplierCorrectiveAction.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SupplierCorrectiveActionCreateRequest request = new SupplierCorrectiveActionCreateRequest();
        request.setSupplierId(supplierId);
        request.setIssueTitle("Forged completed CAPA");
        request.setStatus(SupplierCorrectiveActionStatus.CLOSED.name());
        request.setSubmittedAt(LocalDateTime.of(2026, 7, 10, 9, 0));
        request.setVerifiedAt(LocalDateTime.of(2026, 7, 11, 9, 0));
        request.setClosedAt(LocalDateTime.of(2026, 7, 12, 9, 0));
        request.setVerificationResult("Self-approved");
        request.setRecurrencePrevented(true);

        var response = correctiveActionService().create(request);

        assertThat(response.getStatus()).isEqualTo(SupplierCorrectiveActionStatus.DRAFT.name());
        assertThat(response.getSubmittedAt()).isNull();
        assertThat(response.getVerifiedAt()).isNull();
        assertThat(response.getClosedAt()).isNull();
        assertThat(response.getVerificationResult()).isNull();
        assertThat(response.isRecurrencePrevented()).isFalse();
        assertThat(response.getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void relatedPartyCreateAlwaysStartsActiveInCurrentCompany() {
        Supplier supplier = supplier(supplierId, companyId);
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier);
        when(relatedPartyRepository.save(any(SupplierRelatedParty.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SupplierRelatedPartyCreateRequest request = new SupplierRelatedPartyCreateRequest();
        request.setSupplierId(supplierId);
        request.setPartyType("BENEFICIAL_OWNER");
        request.setStatus("BLOCKED");

        var response = relatedPartyService().create(request);

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void relatedPartyUpdatePreservesSupplierAndLifecycleState() {
        UUID id = UUID.randomUUID();
        SupplierRelatedParty party = relatedParty(id, companyId);
        when(relatedPartyRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(party));
        when(relatedPartyRepository.save(party)).thenReturn(party);
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier(supplierId, companyId));

        SupplierRelatedPartyUpdateRequest request = new SupplierRelatedPartyUpdateRequest();
        request.setSupplierId(UUID.randomUUID());
        request.setStatus("BLOCKED");
        request.setPartyType("DIRECTOR");
        request.setFullName("Updated owner");

        relatedPartyService().update(id, request);

        assertThat(party.getSupplierId()).isEqualTo(supplierId);
        assertThat(party.getStatus()).isEqualTo("ACTIVE");
        assertThat(party.getPartyType()).isEqualTo("DIRECTOR");
        assertThat(party.getFullName()).isEqualTo("Updated owner");
    }

    @Test
    void crossCompanyKycMutationsAreHiddenFromGlobalAdmin() {
        enableGlobalAdminScope();
        UUID id = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        when(kycRepository.findByIdAndIsDeletedFalse(id))
            .thenAnswer(invocation -> Optional.of(kyc(id, otherCompany)));
        when(kycRepository.findAnyById(id))
            .thenAnswer(invocation -> Optional.of(deletedKyc(id, otherCompany)));

        SupplierKycCheckUpdateRequest update = new SupplierKycCheckUpdateRequest();
        update.setCheckType("AML");
        assertNotFound(() -> kycService().update(id, update));
        assertNotFound(() -> kycService().request(id));
        assertNotFound(() -> kycService().pass(id, new SupplierKycDecisionRequest(null, null, null)));
        assertNotFound(() -> kycService().fail(id, new SupplierKycDecisionRequest(null, null, null)));
        assertNotFound(() -> kycService().waive(id, new SupplierKycDecisionRequest(null, null, null)));
        assertNotFound(() -> kycService().expire(id));
        assertNotFound(() -> kycService().softDelete(id));
        assertNotFound(() -> kycService().restore(id));

        verify(kycRepository, never()).save(any());
    }

    @Test
    void crossCompanyCorrectiveActionMutationsAreHiddenFromGlobalAdmin() {
        enableGlobalAdminScope();
        UUID id = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        when(correctiveActionRepository.findByIdAndIsDeletedFalse(id))
            .thenAnswer(invocation -> Optional.of(capa(id, otherCompany)));
        when(correctiveActionRepository.findAnyById(id))
            .thenAnswer(invocation -> Optional.of(deletedCapa(id, otherCompany)));

        assertNotFound(() -> correctiveActionService().update(id, new SupplierCorrectiveActionUpdateRequest()));
        assertNotFound(() -> correctiveActionService().open(id));
        assertNotFound(() -> correctiveActionService().start(id));
        assertNotFound(() -> correctiveActionService().submit(id, new SupplierCapaActionRequest("submit")));
        assertNotFound(() -> correctiveActionService().verify(id, new SupplierCapaActionRequest("verify")));
        assertNotFound(() -> correctiveActionService().close(id));
        assertNotFound(() -> correctiveActionService().cancel(id, new SupplierCapaActionRequest("cancel")));
        assertNotFound(() -> correctiveActionService().softDelete(id));
        assertNotFound(() -> correctiveActionService().restore(id));

        verify(correctiveActionRepository, never()).save(any());
    }

    @Test
    void crossCompanyRelatedPartyMutationsAreHiddenFromGlobalAdmin() {
        enableGlobalAdminScope();
        UUID id = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        when(relatedPartyRepository.findByIdAndIsDeletedFalse(id))
            .thenAnswer(invocation -> Optional.of(relatedParty(id, otherCompany)));
        when(relatedPartyRepository.findAnyById(id))
            .thenAnswer(invocation -> Optional.of(deletedRelatedParty(id, otherCompany)));

        assertNotFound(() -> relatedPartyService().update(id, new SupplierRelatedPartyUpdateRequest()));
        assertNotFound(() -> relatedPartyService().softDelete(id));
        assertNotFound(() -> relatedPartyService().restore(id));

        verify(relatedPartyRepository, never()).save(any());
    }

    @Test
    void kycDeleteAndRestoreRecalculateSupplierAdmission() {
        UUID id = UUID.randomUUID();
        SupplierKycCheck check = kyc(id, companyId);
        when(kycRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(check));
        when(kycRepository.findAnyById(id)).thenReturn(Optional.of(check));
        when(kycRepository.save(check)).thenReturn(check);

        kycService().softDelete(id);
        kycService().restore(id);

        verify(complianceService, times(2)).applySupplierRisk(supplierId);
    }

    @Test
    void correctiveActionDeleteAndRestoreRecalculateSupplierAdmission() {
        UUID id = UUID.randomUUID();
        SupplierCorrectiveAction action = capa(id, companyId);
        when(correctiveActionRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(action));
        when(correctiveActionRepository.findAnyById(id)).thenReturn(Optional.of(action));
        when(correctiveActionRepository.save(action)).thenReturn(action);

        correctiveActionService().softDelete(id);
        correctiveActionService().restore(id);

        verify(complianceService, times(2)).applySupplierRisk(supplierId);
    }

    @Test
    void relatedPartyDeleteAndRestoreRecalculateSupplierAdmission() {
        UUID id = UUID.randomUUID();
        SupplierRelatedParty party = relatedParty(id, companyId);
        when(relatedPartyRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(party));
        when(relatedPartyRepository.findAnyById(id)).thenReturn(Optional.of(party));
        when(relatedPartyRepository.save(party)).thenReturn(party);

        relatedPartyService().softDelete(id);
        relatedPartyService().restore(id);

        verify(complianceService, times(2)).applySupplierRisk(supplierId);
    }

    @Test
    void relatedPartyCreateRejectsSupplierFromAnotherCompanyEvenForGlobalAdmin() {
        enableGlobalAdminScope();
        Supplier supplier = supplier(supplierId, UUID.randomUUID());
        when(complianceService.validateSupplier(supplierId)).thenReturn(supplier);

        SupplierRelatedPartyCreateRequest request = new SupplierRelatedPartyCreateRequest();
        request.setSupplierId(supplierId);
        request.setPartyType("BENEFICIAL_OWNER");

        assertThatThrownBy(() -> relatedPartyService().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current company");

        verify(relatedPartyRepository, never()).save(any());
    }

    private SupplierKycCheckService kycService() {
        return new SupplierKycCheckService(
            kycRepository,
            new SupplierKycCheckMapper(),
            complianceService,
            auditService
        );
    }

    private SupplierCorrectiveActionService correctiveActionService() {
        return new SupplierCorrectiveActionService(
            correctiveActionRepository,
            new SupplierCorrectiveActionMapper(),
            complianceService,
            employeeRepository,
            auditService
        );
    }

    private SupplierRelatedPartyService relatedPartyService() {
        return new SupplierRelatedPartyService(
            auditService,
            relatedPartyRepository,
            new SupplierRelatedPartyMapper(),
            complianceService
        );
    }

    private Supplier supplier(UUID id, UUID owningCompanyId) {
        Supplier supplier = new Supplier();
        scope(supplier, id, owningCompanyId);
        supplier.setCode("SUP-001");
        supplier.setName("Supplier");
        return supplier;
    }

    private SupplierKycCheck kyc(UUID id, UUID owningCompanyId) {
        SupplierKycCheck check = new SupplierKycCheck();
        scope(check, id, owningCompanyId);
        check.setSupplierId(supplierId);
        check.setCode("KYC-001");
        check.setName("KYC check");
        check.setCheckType("SANCTIONS");
        check.setRiskLevel("LOW");
        check.setStatus(SupplierKycStatus.DRAFT.name());
        return check;
    }

    private SupplierKycCheck deletedKyc(UUID id, UUID owningCompanyId) {
        SupplierKycCheck check = kyc(id, owningCompanyId);
        check.softDelete();
        return check;
    }

    private SupplierCorrectiveAction capa(UUID id, UUID owningCompanyId) {
        SupplierCorrectiveAction action = new SupplierCorrectiveAction();
        scope(action, id, owningCompanyId);
        action.setSupplierId(supplierId);
        action.setCode("CAPA-001");
        action.setName("CAPA");
        action.setStatus(SupplierCorrectiveActionStatus.DRAFT.name());
        action.setSeverity("MEDIUM");
        return action;
    }

    private SupplierCorrectiveAction deletedCapa(UUID id, UUID owningCompanyId) {
        SupplierCorrectiveAction action = capa(id, owningCompanyId);
        action.softDelete();
        return action;
    }

    private SupplierRelatedParty relatedParty(UUID id, UUID owningCompanyId) {
        SupplierRelatedParty party = new SupplierRelatedParty();
        scope(party, id, owningCompanyId);
        party.setSupplierId(supplierId);
        party.setCode("PARTY-001");
        party.setName("Beneficial owner");
        party.setStatus("ACTIVE");
        party.setPartyType("BENEFICIAL_OWNER");
        party.setRiskLevel("LOW");
        return party;
    }

    private SupplierRelatedParty deletedRelatedParty(UUID id, UUID owningCompanyId) {
        SupplierRelatedParty party = relatedParty(id, owningCompanyId);
        party.softDelete();
        return party;
    }

    private void scope(BusinessEntity entity, UUID id, UUID owningCompanyId) {
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setCompanyId(owningCompanyId);
    }

    private void enableGlobalAdminScope() {
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void assertNotFound(Runnable mutation) {
        assertThatThrownBy(mutation::run)
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("not found");
    }
}
