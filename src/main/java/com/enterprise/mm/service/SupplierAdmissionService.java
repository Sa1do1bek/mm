package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.enums.SupplierCorrectiveActionStatus;
import com.enterprise.mm.enums.SupplierKycStatus;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the operational admission status; performance and compliance services only supply facts. */
@Service
public class SupplierAdmissionService {
    public static final String PERFORMANCE_BLOCK_REASON_PREFIX = "Automatic block by low supplier rating: ";
    public static final String COMPLIANCE_BLOCK_REASON =
        "Supplier blocked by compliance: failed KYC, sanctions/COI or critical CAPA";

    private final SupplierRepository supplierRepository;
    private final SupplierKycCheckRepository kycRepository;
    private final SupplierRelatedPartyRepository relatedPartyRepository;
    private final SupplierCorrectiveActionRepository correctiveActionRepository;
    private final SupplierPortalAccountRepository portalAccountRepository;
    private final SupplierActivationRecordRepository activationRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public SupplierAdmissionService(
        SupplierRepository supplierRepository,
        SupplierKycCheckRepository kycRepository,
        SupplierRelatedPartyRepository relatedPartyRepository,
        SupplierCorrectiveActionRepository correctiveActionRepository,
        SupplierPortalAccountRepository portalAccountRepository,
        SupplierActivationRecordRepository activationRepository
    ) {
        this.supplierRepository = supplierRepository;
        this.kycRepository = kycRepository;
        this.relatedPartyRepository = relatedPartyRepository;
        this.correctiveActionRepository = correctiveActionRepository;
        this.portalAccountRepository = portalAccountRepository;
        this.activationRepository = activationRepository;
    }

    public SupplierAdmissionService(
        SupplierRepository supplierRepository,
        SupplierKycCheckRepository kycRepository,
        SupplierRelatedPartyRepository relatedPartyRepository,
        SupplierCorrectiveActionRepository correctiveActionRepository,
        SupplierPortalAccountRepository portalAccountRepository,
        SupplierActivationRecordRepository activationRepository,
        com.enterprise.mm.repository.SupplierAccreditationApplicationRepository accreditationApplicationRepository,
        com.enterprise.mm.repository.SupplierAccreditationDecisionRepository accreditationDecisionRepository
    ) {
        this(supplierRepository, kycRepository, relatedPartyRepository, correctiveActionRepository, portalAccountRepository, activationRepository);
    }

    @Transactional(readOnly = true)
    public Supplier validateSupplier(UUID supplierId) {
        if (supplierId == null) {
            throw new BusinessException("supplierId is required");
        }
        return supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .filter(supplier -> com.enterprise.mm.security.AccessScope.canAccessCompany(supplier.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
    }

    @Transactional(readOnly = true)
    public String operationalBlockReason(UUID supplierId, String source) {
        Supplier supplier = validateSupplier(supplierId);
        if (!SupplierStatus.BLOCKED.name().equals(supplier.getStatus())) {
            return null;
        }
        String context = source == null || source.isBlank() ? "this transaction" : source;
        String reason = supplier.getBlockedReason() == null || supplier.getBlockedReason().isBlank()
            ? "Operational supplier block is active"
            : supplier.getBlockedReason();
        return "Supplier is blocked for " + context + ": " + reason;
    }

    @Transactional(readOnly = true)
    public SupplierComplianceSummary summarize(UUID supplierId) {
        return summarize(validateSupplier(supplierId));
    }

    @Transactional
    public Supplier recalculate(UUID supplierId) {
        Supplier supplier = validateSupplier(supplierId);
        SupplierComplianceSummary summary = summarize(supplier);

        if (SupplierStatus.BLOCKED.name().equals(summary.recommendedStatus())) {
            supplier.setStatus(SupplierStatus.BLOCKED.name());
            supplier.setBlockedReason(COMPLIANCE_BLOCK_REASON);
            return supplierRepository.save(supplier);
        }
        if (isStickyBlock(supplier)) {
            return supplierRepository.save(supplier);
        }
        boolean activated = activationRepository
            .existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
                supplier.getTenantId(), supplier.getCompanyId(), supplier.getId(), "RECORDED");
        boolean grandfatheredActive = SupplierStatus.ACTIVE.name().equals(supplier.getStatus())
            || SupplierStatus.LIMITED.name().equals(supplier.getStatus());
        if (hasNoPerformanceObservation(supplier)) {
            supplier.setStatus(activated || grandfatheredActive
                ? SupplierStatus.ACTIVE.name()
                : SupplierStatus.NEW.name());
            supplier.setBlockedReason(null);
            return supplierRepository.save(supplier);
        }

        if (!activated && !grandfatheredActive) {
            supplier.setStatus(SupplierStatus.NEW.name());
            supplier.setBlockedReason(null);
            return supplierRepository.save(supplier);
        }

        applyPerformanceStatus(supplier);
        return supplierRepository.save(supplier);
    }

    private SupplierComplianceSummary summarize(Supplier supplier) {
        String tenantId = supplier.getTenantId();
        UUID companyId = supplier.getCompanyId();
        UUID supplierId = supplier.getId();
        List<SupplierKycCheck> kycChecks = kycRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(tenantId, companyId, supplierId);
        List<SupplierRelatedParty> relatedParties = relatedPartyRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(tenantId, companyId, supplierId);
        List<SupplierCorrectiveAction> correctiveActions = correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(tenantId, companyId, supplierId);

        long failedKyc = kycChecks.stream().filter(this::isFailedKyc).count();
        long highRiskParties = relatedParties.stream().filter(this::isHighRiskParty).count();
        long openCorrectiveActions = correctiveActions.stream().filter(this::isOpenCapa).count();
        boolean blocked = kycChecks.stream().anyMatch(this::isBlockingKyc)
            || relatedParties.stream().anyMatch(this::isBlockingParty)
            || correctiveActions.stream().anyMatch(this::isBlockingCapa);
        boolean limited = blocked
            || failedKyc > 0
            || highRiskParties > 0
            || correctiveActions.stream().anyMatch(this::isHighRiskOpenCapa);
        String recommendedStatus = blocked
            ? SupplierStatus.BLOCKED.name()
            : limited ? SupplierStatus.LIMITED.name() : SupplierStatus.ACTIVE.name();
        long activePortalAccounts = portalAccountRepository
            .countByTenantIdAndCompanyIdAndSupplier_IdAndStatusAndIsDeletedFalse(
                tenantId, companyId, supplierId, "ACTIVE");
        return new SupplierComplianceSummary(
            supplierId,
            activePortalAccounts,
            failedKyc,
            highRiskParties,
            openCorrectiveActions,
            recommendedStatus
        );
    }

    private boolean isStickyBlock(Supplier supplier) {
        return SupplierStatus.BLOCKED.name().equals(supplier.getStatus())
            && (supplier.getBlockedReason() == null
                || !supplier.getBlockedReason().startsWith(PERFORMANCE_BLOCK_REASON_PREFIX));
    }

    private boolean hasNoPerformanceObservation(Supplier supplier) {
        return SupplierStatus.NEW.name().equals(supplier.getStatus())
            && nvl(supplier.getRating()).signum() == 0
            && (supplier.getCategory() == null || supplier.getCategory().isBlank() || "C".equals(supplier.getCategory()));
    }

    private void applyPerformanceStatus(Supplier supplier) {
        String category = supplier.getCategory() == null ? "" : supplier.getCategory().trim().toUpperCase();
        if (category.isBlank()) {
            category = categoryFor(nvl(supplier.getRating()));
            supplier.setCategory(category);
        }
        switch (category) {
            case "A", "B", "C", "D" -> {
                supplier.setStatus(SupplierStatus.ACTIVE.name());
                supplier.setBlockedReason(null);
            }
            default -> {
                supplier.setStatus(SupplierStatus.ACTIVE.name());
                supplier.setBlockedReason(null);
            }
        }
    }

    private String categoryFor(BigDecimal rating) {
        if (rating.compareTo(BigDecimal.valueOf(85)) >= 0) return "A";
        if (rating.compareTo(BigDecimal.valueOf(70)) >= 0) return "B";
        if (rating.compareTo(BigDecimal.valueOf(55)) >= 0) return "C";
        return "D";
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isFailedKyc(SupplierKycCheck check) {
        return SupplierKycStatus.FAILED.name().equals(check.getStatus()) || check.isRemediationRequired();
    }

    private boolean isBlockingKyc(SupplierKycCheck check) {
        return SupplierKycStatus.FAILED.name().equals(check.getStatus()) && "CRITICAL".equals(check.getRiskLevel());
    }

    private boolean isHighRiskParty(SupplierRelatedParty party) {
        return party.isPepFlag()
            || party.isSanctionsMatch()
            || party.isConflictOfInterestFlag()
            || "HIGH".equals(party.getRiskLevel())
            || "CRITICAL".equals(party.getRiskLevel());
    }

    private boolean isBlockingParty(SupplierRelatedParty party) {
        return party.isSanctionsMatch()
            || (party.isConflictOfInterestFlag() && "CRITICAL".equals(party.getRiskLevel()));
    }

    private boolean isOpenCapa(SupplierCorrectiveAction action) {
        return !SupplierCorrectiveActionStatus.CLOSED.name().equals(action.getStatus())
            && !SupplierCorrectiveActionStatus.CANCELLED.name().equals(action.getStatus());
    }

    private boolean isHighRiskOpenCapa(SupplierCorrectiveAction action) {
        return isOpenCapa(action) && ("HIGH".equals(action.getSeverity()) || "CRITICAL".equals(action.getSeverity()));
    }

    private boolean isBlockingCapa(SupplierCorrectiveAction action) {
        return isOpenCapa(action) && "CRITICAL".equals(action.getSeverity());
    }

}
