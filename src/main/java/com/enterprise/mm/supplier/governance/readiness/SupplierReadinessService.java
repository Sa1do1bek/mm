package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierLicense;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.SupplierComplianceService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessFacts.ScopeFact;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierReadinessService {
    private static final Set<String> CLOSED_CAPA = Set.of("CLOSED", "CANCELLED");

    private final SupplierRepository supplierRepository;
    private final SupplierAccreditationApplicationRepository applicationRepository;
    private final SupplierAccreditationDecisionRepository decisionRepository;
    private final SupplierActivationRecordRepository activationRepository;
    private final SupplierLicenseRepository licenseRepository;
    private final SupplierCorrectiveActionRepository correctiveActionRepository;
    private final SupplierEvidenceChecklistService checklistService;
    private final SupplierComplianceService complianceService;
    private final SupplierReadinessPolicy policy;

    @Transactional(readOnly = true)
    public SupplierReadinessSummary get(UUID supplierId) {
        return get(supplierId, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public SupplierReadinessSummary get(UUID supplierId, LocalDate asOfDate) {
        return evaluate(supplierId, asOfDate, null);
    }

    /** Command readiness must not borrow evidence or compliance from another application. */
    @Transactional(readOnly = true)
    public SupplierReadinessSummary getForApplication(UUID supplierId, UUID applicationId) {
        if (applicationId == null) {
            throw new NotFoundException("Accreditation application is required");
        }
        return evaluate(supplierId, LocalDate.now(), applicationId);
    }

    private SupplierReadinessSummary evaluate(UUID supplierId, LocalDate asOfDate, UUID applicationId) {
        Supplier supplier = requireSupplier(supplierId);
        LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
        List<SupplierAccreditationApplication> applications = applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                supplier.getTenantId(), supplier.getCompanyId(), supplier.getId());
        if (applicationId != null) {
            applications = applications.stream().filter(app -> applicationId.equals(app.getId())).toList();
            if (applications.isEmpty()) {
                throw new NotFoundException("Supplier accreditation application not found: " + applicationId);
            }
        }
        boolean activationExists = activationRepository
            .existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
                supplier.getTenantId(), supplier.getCompanyId(), supplierId, "RECORDED");
        boolean lifecycleActive = SupplierStatus.ACTIVE.name().equals(supplier.getStatus())
            || SupplierStatus.LIMITED.name().equals(supplier.getStatus());
        SupplierReadinessFacts facts = buildFacts(
            supplier,
            applications,
            complianceService.summarize(supplierId),
            today,
            lifecycleActive,
            activationExists
        );
        SupplierReadinessSummary summary = policy.evaluate(facts, today);
        if (lifecycleActive && !activationExists) {
            return withLegacyWarning(summary);
        }
        return summary;
    }

    private SupplierReadinessFacts buildFacts(
        Supplier supplier,
        List<SupplierAccreditationApplication> applications,
        SupplierComplianceSummary compliance,
        LocalDate asOfDate,
        boolean lifecycleActive,
        boolean activationExists
    ) {
        List<SupplierLicense> licenses = licenseRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                supplier.getTenantId(), supplier.getCompanyId(), supplier.getId());
        List<SupplierCorrectiveAction> capas = correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                supplier.getTenantId(), supplier.getCompanyId(), supplier.getId());
        boolean expiredRequiredLicense = licenses.stream().anyMatch(license ->
            license.getValidTo() != null
                && license.getValidTo().isBefore(asOfDate));
        boolean criticalCapa = capas.stream().anyMatch(this::isCriticalOpenCapa);
        List<ScopeFact> scopes = applications.stream()
            .map(application -> scopeFact(application, asOfDate))
            .toList();
        boolean previouslyEffective = activationExists
            || scopes.stream().anyMatch(scope ->
                ("APPROVED".equals(scope.decision())
                    || "CONDITIONALLY_APPROVED".equals(scope.decision()))
                    && scope.validTo() != null);
        boolean manualBlock = "BLOCKED".equals(supplier.getStatus());
        boolean complianceBlocked = "BLOCKED".equals(compliance.recommendedStatus())
            || criticalCapa || expiredRequiredLicense;
        boolean currentApplicationComplianceChecked = currentComplianceApplication(applications)
            .map(this::complianceCheckCompleted)
            .orElse(false);
        return new SupplierReadinessFacts(
            profileComplete(supplier),
            compliance.activePortalAccounts() > 0,
            currentApplicationComplianceChecked
                && !"NEW".equals(compliance.recommendedStatus()),
            complianceBlocked,
            "LIMITED".equals(compliance.recommendedStatus()),
            manualBlock,
            lifecycleActive,
            activationExists,
            previouslyEffective,
            scopes
        );
    }

    private ScopeFact scopeFact(SupplierAccreditationApplication application, LocalDate asOfDate) {
        List<SupplierEvidenceChecklistItem> checklist = checklistService.checklist(application, asOfDate);
        SupplierAccreditationDecision decision = decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                application.getTenantId(), application.getCompanyId(), application.getId())
            .stream()
            .findFirst()
            .orElse(null);
        List<ReadinessBlocker> blockers = new ArrayList<>();
        for (SupplierEvidenceChecklistItem item : checklist) {
            if (!item.mandatory() || item.state() == EvidenceChecklistItemState.VERIFIED
                || item.state() == EvidenceChecklistItemState.NOT_APPLICABLE) {
                continue;
            }
            blockers.add(new ReadinessBlocker(
                evidenceBlockerCode(item.state()),
                StageKey.EVIDENCE,
                application.getId(),
                item.requirementCode(),
                "Evidence requirement " + item.requirementCode() + " is " + item.state(),
                "Provide and verify current evidence",
                item.ownerRole(),
                item.dueDate()
            ));
        }
        int total = (int) checklist.stream().filter(SupplierEvidenceChecklistItem::mandatory).count();
        int completed = (int) checklist.stream()
            .filter(SupplierEvidenceChecklistItem::mandatory)
            .filter(item -> item.state() == EvidenceChecklistItemState.VERIFIED
                || item.state() == EvidenceChecklistItemState.NOT_APPLICABLE)
            .count();
        boolean finalDecision = finalDecision(application, decision);
        boolean submittedForApproval = "PENDING_APPROVAL".equals(application.getStatus())
            && decision != null && "PROPOSED".equals(decision.getStatus());
        boolean reviewComplete = finalDecision || submittedForApproval
            || Set.of("APPROVED", "CONDITIONALLY_APPROVED", "REJECTED")
                .contains(application.getStatus());
        return new ScopeFact(
            application.getId(),
            application.getAccreditationType(),
            application.getRequestedScope(),
            application.getStatus(),
            complianceCheckCompleted(application),
            completed,
            total,
            reviewComplete,
            finalDecision ? decision.getDecision() : null,
            finalDecision ? decision.getValidTo() : application.getValidTo(),
            blockers
        );
    }

    private boolean finalDecision(
        SupplierAccreditationApplication application,
        SupplierAccreditationDecision decision
    ) {
        return decision != null
            && "RECORDED".equals(decision.getStatus())
            && application.getStatus() != null
            && application.getStatus().equals(decision.getDecision())
            && Set.of("APPROVED", "CONDITIONALLY_APPROVED", "REJECTED").contains(decision.getDecision());
    }

    private SupplierReadinessSummary withLegacyWarning(SupplierReadinessSummary summary) {
        ReadinessBlocker warning = new ReadinessBlocker(
            "LEGACY_EVIDENCE_REVIEW_REQUIRED",
            StageKey.ACTIVATION,
            null,
            "LEGACY_VERIFIED_EVIDENCE",
            "Legacy active supplier has no activation record",
            "Review legacy evidence and record a new activation when appropriate",
            "SUPPLIER_MANAGER",
            LocalDate.now().plusDays(90)
        );
        List<ReadinessBlocker> blockers = new ArrayList<>(summary.blockers());
        blockers.add(warning);
        return new SupplierReadinessSummary(
            summary.overallStatus(),
            summary.readinessPercent(),
            summary.activationEligible(),
            summary.stages(),
            summary.scopes(),
            blockers,
            summary.nextAction(),
            summary.calculatedAt(),
            summary.policyVersion()
        );
    }

    private Supplier requireSupplier(UUID supplierId) {
        return supplierRepository.findByIdAndIsDeletedFalse(supplierId)
            .filter(value -> TenantContext.currentTenant().equals(value.getTenantId()))
            .filter(value -> CompanyContext.currentCompany().equals(value.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierId));
    }

    private boolean profileComplete(Supplier supplier) {
        return present(supplier.getLegalName())
            && present(supplier.getTaxId())
            && present(supplier.getRegistrationNumber())
            && present(supplier.getLegalAddress())
            && present(supplier.getEmail());
    }

    private boolean isCriticalOpenCapa(SupplierCorrectiveAction action) {
        return "CRITICAL".equals(action.getSeverity())
            && !CLOSED_CAPA.contains(action.getStatus());
    }

    private boolean complianceCheckCompleted(SupplierAccreditationApplication application) {
        if (application == null
            || application.getComplianceRecommendation() == null
            || application.getComplianceRecommendation().isBlank()
            || application.getComplianceSnapshotJson() == null
            || application.getComplianceSnapshotJson().isBlank()) {
            return false;
        }
        String recommendation = application.getComplianceRecommendation()
            .trim()
            .toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return !"NEW".equals(recommendation);
    }

    private Optional<SupplierAccreditationApplication> currentComplianceApplication(
        List<SupplierAccreditationApplication> applications
    ) {
        Optional<SupplierAccreditationApplication> activeWorkflow = applications.stream()
            .filter(application -> !"DRAFT".equals(application.getStatus()))
            .findFirst();
        return activeWorkflow.isPresent() ? activeWorkflow : applications.stream().findFirst();
    }

    private String evidenceBlockerCode(EvidenceChecklistItemState state) {
        return switch (state) {
            case MISSING -> "EVIDENCE_MISSING";
            case REJECTED -> "EVIDENCE_REJECTED";
            case EXPIRED -> "EVIDENCE_EXPIRED";
            case UNDER_VERIFICATION -> "EVIDENCE_UNDER_VERIFICATION";
            default -> "EVIDENCE_NOT_VERIFIED";
        };
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
