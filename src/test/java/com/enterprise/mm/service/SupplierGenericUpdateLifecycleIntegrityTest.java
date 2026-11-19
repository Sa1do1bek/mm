package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.SupplierPortalSubmission;
import com.enterprise.mm.enums.SupplierCorrectiveActionStatus;
import com.enterprise.mm.enums.SupplierKycStatus;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.enums.SupplierPortalSubmissionStatus;
import com.enterprise.mm.mapper.SupplierCorrectiveActionMapper;
import com.enterprise.mm.mapper.SupplierKycCheckMapper;
import com.enterprise.mm.mapper.SupplierPortalAccountMapper;
import com.enterprise.mm.mapper.SupplierPortalSubmissionMapper;
import com.enterprise.mm.payload.SupplierCorrectiveActionUpdateRequest;
import com.enterprise.mm.payload.SupplierKycCheckUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionUpdateRequest;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierPortalSubmissionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierGenericUpdateLifecycleIntegrityTest {
    private static final String TENANT = "supplier-update-integrity";

    @Mock private SupplierPortalAccountRepository accountRepository;
    @Mock private SupplierPortalSubmissionRepository submissionRepository;
    @Mock private SupplierKycCheckRepository kycRepository;
    @Mock private SupplierCorrectiveActionRepository correctiveActionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private SupplierAccreditationService accreditationService;
    @Mock private SupplierAdmissionService admissionService;
    @Mock private AuditService auditService;

    private UUID companyId;
    private SupplierComplianceService complianceService;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        complianceService = new SupplierComplianceService(admissionService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void portalAccountUpdatePreservesLifecycleAndSupplierWhileChangingContactSettings() {
        UUID supplierId = UUID.randomUUID();
        Supplier supplier = supplier(supplierId);
        SupplierPortalAccount account = portalAccount(supplierId);
        LocalDateTime invitedAt = LocalDateTime.of(2026, 1, 2, 9, 30);
        LocalDateTime activatedAt = LocalDateTime.of(2026, 1, 3, 10, 45);
        LocalDateTime lastLoginAt = LocalDateTime.of(2026, 2, 4, 11, 15);
        LocalDateTime lastSubmissionAt = LocalDateTime.of(2026, 2, 5, 12, 20);
        LocalDateTime termsAcceptedAt = LocalDateTime.of(2026, 1, 3, 11, 0);
        account.setInvitedAt(invitedAt);
        account.setActivatedAt(activatedAt);
        account.setLastLoginAt(lastLoginAt);
        account.setLastSubmissionAt(lastSubmissionAt);
        account.setTermsAcceptedAt(termsAcceptedAt);
        account.setInviteTokenHash("a".repeat(64));
        account.setPortalTokenHash("b".repeat(64));
        account.setPortalTokenExpiresAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId())).thenReturn(Optional.of(account));
        when(admissionService.validateSupplier(supplierId)).thenReturn(supplier);
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, companyId, "edited@example.uz"
        )).thenReturn(Optional.empty());
        when(accountRepository.save(account)).thenReturn(account);

        SupplierPortalAccountUpdateRequest request = new SupplierPortalAccountUpdateRequest();
        request.setCode("PORTAL-EDITED");
        request.setName("Edited portal contact");
        request.setDescription("Editable description");
        request.setSupplierId(UUID.randomUUID());
        request.setLoginEmail(" EDITED@EXAMPLE.UZ ");
        request.setContactPerson("Edited Contact");
        request.setPhone("+998 90 000 00 01");
        request.setAllowedIpRange("10.20.0.0/24");
        request.setMfaRequired(false);

        portalAccountService().update(account.getId(), request);

        assertThat(account.getCode()).isEqualTo("PORTAL-EDITED");
        assertThat(account.getName()).isEqualTo("Edited portal contact");
        assertThat(account.getDescription()).isEqualTo("Editable description");
        assertThat(account.getLoginEmail()).isEqualTo("edited@example.uz");
        assertThat(account.getContactPerson()).isEqualTo("Edited Contact");
        assertThat(account.getPhone()).isEqualTo("+998 90 000 00 01");
        assertThat(account.getAllowedIpRange()).isEqualTo("10.10.0.0/24");
        assertThat(account.isMfaRequired()).isTrue();
        assertThat(account.getSupplierId()).isEqualTo(supplierId);
        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.ACTIVE.name());
        assertThat(account.getInvitedAt()).isEqualTo(invitedAt);
        assertThat(account.getActivatedAt()).isEqualTo(activatedAt);
        assertThat(account.getLastLoginAt()).isEqualTo(lastLoginAt);
        assertThat(account.getLastSubmissionAt()).isEqualTo(lastSubmissionAt);
        assertThat(account.getTermsAcceptedAt()).isEqualTo(termsAcceptedAt);
        assertThat(account.getInviteTokenHash()).isEqualTo("a".repeat(64));
        assertThat(account.getPortalTokenHash()).isEqualTo("b".repeat(64));
    }

    @Test
    void portalSubmissionUpdatePreservesLifecycleReviewAndReferencesWhileChangingPayload() {
        UUID supplierId = UUID.randomUUID();
        UUID portalAccountId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        SupplierPortalSubmission submission = portalSubmission(supplierId, portalAccountId, referenceId);
        LocalDateTime submittedAt = LocalDateTime.of(2026, 3, 1, 8, 0);
        LocalDateTime reviewedAt = LocalDateTime.of(2026, 3, 2, 14, 30);
        submission.setSubmittedAt(submittedAt);
        submission.setReviewedAt(reviewedAt);
        submission.setReviewedBy("compliance.manager");
        submission.setReviewComment("Evidence accepted");
        when(submissionRepository.findByIdAndIsDeletedFalse(submission.getId())).thenReturn(Optional.of(submission));
        when(submissionRepository.save(submission)).thenReturn(submission);
        when(accountRepository.findByIdAndIsDeletedFalse(any(UUID.class))).thenAnswer(invocation -> {
            UUID requestedAccountId = invocation.getArgument(0);
            UUID requestedSupplierId = requestedAccountId.equals(portalAccountId) ? supplierId : replacementSupplierId;
            return Optional.of(activePortalAccount(requestedAccountId, requestedSupplierId));
        });

        SupplierPortalSubmissionUpdateRequest request = new SupplierPortalSubmissionUpdateRequest();
        request.setCode("SUBMISSION-EDITED");
        request.setName("Updated KYC package");
        request.setDescription("Updated supplier payload");
        request.setSupplierId(replacementSupplierId);
        request.setPortalAccountId(UUID.randomUUID());
        request.setSubmissionType("LICENSE_DOCUMENT");
        request.setReferenceEntityType("PURCHASE_ORDER");
        request.setReferenceId(UUID.randomUUID());
        request.setPayloadJson("{\"version\":2}");
        request.setAttachmentId(attachmentId);

        portalSubmissionService().update(submission.getId(), request);

        assertThat(submission.getCode()).isEqualTo("SUBMISSION-EDITED");
        assertThat(submission.getName()).isEqualTo("Updated KYC package");
        assertThat(submission.getDescription()).isEqualTo("Updated supplier payload");
        assertThat(submission.getSubmissionType()).isEqualTo("LICENSE_DOCUMENT");
        assertThat(submission.getPayloadJson()).isEqualTo("{\"version\":2}");
        assertThat(submission.getAttachmentId()).isEqualTo(attachmentId);
        assertThat(submission.getSupplierId()).isEqualTo(supplierId);
        assertThat(submission.getPortalAccountId()).isEqualTo(portalAccountId);
        assertThat(submission.getReferenceEntityType()).isEqualTo("SUPPLIER_PROFILE");
        assertThat(submission.getReferenceId()).isEqualTo(referenceId);
        assertThat(submission.getStatus()).isEqualTo(SupplierPortalSubmissionStatus.ACCEPTED.name());
        assertThat(submission.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(submission.getReviewedAt()).isEqualTo(reviewedAt);
        assertThat(submission.getReviewedBy()).isEqualTo("compliance.manager");
        assertThat(submission.getReviewComment()).isEqualTo("Evidence accepted");
    }

    @Test
    void kycUpdatePreservesDecisionStateAndSupplierWhileChangingAssessmentDetails() {
        UUID supplierId = UUID.randomUUID();
        SupplierKycCheck check = kycCheck(supplierId);
        LocalDateTime checkedAt = LocalDateTime.of(2026, 4, 5, 16, 10);
        check.setCheckedAt(checkedAt);
        when(kycRepository.findByIdAndIsDeletedFalse(check.getId())).thenReturn(Optional.of(check));
        when(kycRepository.save(check)).thenReturn(check);

        UUID evidenceAttachmentId = UUID.randomUUID();
        SupplierKycCheckUpdateRequest request = new SupplierKycCheckUpdateRequest();
        request.setCode("KYC-EDITED");
        request.setName("Updated AML screening");
        request.setDescription("Assessment details corrected");
        request.setSupplierId(UUID.randomUUID());
        request.setCheckType("AML");
        request.setProvider("External AML provider");
        request.setExternalCheckId("AML-2026-0042");
        request.setRiskLevel("HIGH");
        request.setScore(new BigDecimal("82.50"));
        request.setValidUntil(LocalDate.of(2027, 12, 31));
        request.setNextReviewDate(LocalDate.of(2027, 6, 30));
        request.setEvidenceAttachmentId(evidenceAttachmentId);
        request.setFindings("Updated assessment findings");
        request.setRemediationRequired(true);

        kycCheckService().update(check.getId(), request);

        assertThat(check.getCode()).isEqualTo("KYC-EDITED");
        assertThat(check.getName()).isEqualTo("Updated AML screening");
        assertThat(check.getDescription()).isEqualTo("Assessment details corrected");
        assertThat(check.getCheckType()).isEqualTo("AML");
        assertThat(check.getProvider()).isEqualTo("External AML provider");
        assertThat(check.getExternalCheckId()).isEqualTo("AML-2026-0042");
        assertThat(check.getRiskLevel()).isEqualTo("HIGH");
        assertThat(check.getScore()).isEqualByComparingTo("82.50");
        assertThat(check.getValidUntil()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(check.getNextReviewDate()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(check.getEvidenceAttachmentId()).isEqualTo(evidenceAttachmentId);
        assertThat(check.getFindings()).isEqualTo("Updated assessment findings");
        assertThat(check.isRemediationRequired()).isTrue();
        assertThat(check.getSupplierId()).isEqualTo(supplierId);
        assertThat(check.getStatus()).isEqualTo(SupplierKycStatus.PASSED.name());
        assertThat(check.getCheckedAt()).isEqualTo(checkedAt);
    }

    @Test
    void correctiveActionUpdatePreservesLifecycleVerificationAndSourceWhileChangingRemediationPlan() {
        UUID supplierId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        SupplierCorrectiveAction action = correctiveAction(supplierId, sourceId);
        LocalDateTime submittedAt = LocalDateTime.of(2026, 5, 10, 10, 0);
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 5, 12, 15, 45);
        LocalDateTime closedAt = LocalDateTime.of(2026, 5, 13, 9, 20);
        action.setSubmittedAt(submittedAt);
        action.setVerifiedAt(verifiedAt);
        action.setClosedAt(closedAt);
        action.setVerificationResult("Effectiveness confirmed by QA");
        action.setRecurrencePrevented(true);
        when(correctiveActionRepository.findByIdAndIsDeletedFalse(action.getId())).thenReturn(Optional.of(action));
        when(correctiveActionRepository.save(action)).thenReturn(action);

        SupplierCorrectiveActionUpdateRequest request = new SupplierCorrectiveActionUpdateRequest();
        request.setCode("CAPA-EDITED");
        request.setName("Updated CAPA plan");
        request.setDescription("Editable CAPA description");
        request.setSupplierId(UUID.randomUUID());
        request.setSourceType("CONTRACT");
        request.setSourceId(UUID.randomUUID());
        request.setIssueTitle("Updated recurring deviation");
        request.setIssueDescription("Updated issue details");
        request.setRootCause("Updated root cause");
        request.setCorrectiveAction("Updated corrective action");
        request.setPreventiveAction("Updated preventive action");
        request.setSupplierResponsibleName("Supplier QA Lead");
        request.setDueDate(LocalDate.of(2026, 12, 1));
        request.setSeverity("CRITICAL");

        correctiveActionService().update(action.getId(), request);

        assertThat(action.getCode()).isEqualTo("CAPA-EDITED");
        assertThat(action.getName()).isEqualTo("Updated CAPA plan");
        assertThat(action.getDescription()).isEqualTo("Editable CAPA description");
        assertThat(action.getIssueTitle()).isEqualTo("Updated recurring deviation");
        assertThat(action.getIssueDescription()).isEqualTo("Updated issue details");
        assertThat(action.getRootCause()).isEqualTo("Updated root cause");
        assertThat(action.getCorrectiveAction()).isEqualTo("Updated corrective action");
        assertThat(action.getPreventiveAction()).isEqualTo("Updated preventive action");
        assertThat(action.getSupplierResponsibleName()).isEqualTo("Supplier QA Lead");
        assertThat(action.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(action.getSeverity()).isEqualTo("CRITICAL");
        assertThat(action.getSupplierId()).isEqualTo(supplierId);
        assertThat(action.getSourceType()).isEqualTo("QUALITY_CLAIM");
        assertThat(action.getSourceId()).isEqualTo(sourceId);
        assertThat(action.getStatus()).isEqualTo(SupplierCorrectiveActionStatus.CLOSED.name());
        assertThat(action.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(action.getVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(action.getClosedAt()).isEqualTo(closedAt);
        assertThat(action.getVerificationResult()).isEqualTo("Effectiveness confirmed by QA");
        assertThat(action.isRecurrencePrevented()).isTrue();
    }

    private final UUID replacementSupplierId = UUID.randomUUID();

    private SupplierPortalAccountService portalAccountService() {
        return new SupplierPortalAccountService(
            accountRepository,
            new SupplierPortalAccountMapper(),
            auditService,
            complianceService,
            null
        );
    }

    private SupplierPortalSubmissionService portalSubmissionService() {
        return new SupplierPortalSubmissionService(
            submissionRepository,
            new SupplierPortalSubmissionMapper(),
            accountRepository,
            complianceService,
            accreditationService,
            auditService
        );
    }

    private SupplierKycCheckService kycCheckService() {
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

    private Supplier supplier(UUID supplierId) {
        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(companyId);
        supplier.setEmail("supplier-" + supplierId.toString().substring(0, 8) + "@example.uz");
        return supplier;
    }

    private SupplierPortalAccount portalAccount(UUID supplierId) {
        SupplierPortalAccount account = activePortalAccount(UUID.randomUUID(), supplierId);
        account.setCode("PORTAL-001");
        account.setName("Portal contact");
        account.setDescription("Original description");
        account.setLoginEmail("original@example.uz");
        account.setContactPerson("Original Contact");
        account.setPhone("+998 90 000 00 00");
        account.setAllowedIpRange("10.10.0.0/24");
        account.setMfaRequired(true);
        return account;
    }

    private SupplierPortalAccount activePortalAccount(UUID id, UUID supplierId) {
        SupplierPortalAccount account = new SupplierPortalAccount();
        scope(account, id);
        account.setSupplierId(supplierId);
        account.setStatus(SupplierPortalStatus.ACTIVE.name());
        return account;
    }

    private SupplierPortalSubmission portalSubmission(UUID supplierId, UUID portalAccountId, UUID referenceId) {
        SupplierPortalSubmission submission = new SupplierPortalSubmission();
        scope(submission, UUID.randomUUID());
        submission.setCode("SUBMISSION-001");
        submission.setName("Original KYC package");
        submission.setStatus(SupplierPortalSubmissionStatus.ACCEPTED.name());
        submission.setSupplierId(supplierId);
        submission.setPortalAccountId(portalAccountId);
        submission.setSubmissionType("KYC_DOCUMENT");
        submission.setReferenceEntityType("SUPPLIER_PROFILE");
        submission.setReferenceId(referenceId);
        submission.setPayloadJson("{\"version\":1}");
        return submission;
    }

    private SupplierKycCheck kycCheck(UUID supplierId) {
        SupplierKycCheck check = new SupplierKycCheck();
        scope(check, UUID.randomUUID());
        check.setCode("KYC-001");
        check.setName("Sanctions screening");
        check.setStatus(SupplierKycStatus.PASSED.name());
        check.setSupplierId(supplierId);
        check.setCheckType("SANCTIONS");
        check.setProvider("Original provider");
        check.setRiskLevel("LOW");
        check.setScore(BigDecimal.TEN);
        return check;
    }

    private SupplierCorrectiveAction correctiveAction(UUID supplierId, UUID sourceId) {
        SupplierCorrectiveAction action = new SupplierCorrectiveAction();
        scope(action, UUID.randomUUID());
        action.setCode("CAPA-001");
        action.setName("Quality deviation CAPA");
        action.setStatus(SupplierCorrectiveActionStatus.CLOSED.name());
        action.setSupplierId(supplierId);
        action.setSourceType("QUALITY_CLAIM");
        action.setSourceId(sourceId);
        action.setIssueTitle("Recurring deviation");
        action.setSeverity("HIGH");
        return action;
    }

    private void scope(com.enterprise.mm.common.BusinessEntity entity, UUID id) {
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
    }
}
