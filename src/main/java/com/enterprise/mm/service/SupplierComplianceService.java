package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierCorrectiveActionStatus;
import com.enterprise.mm.enums.SupplierKycStatus;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierComplianceService {
    private final SupplierAdmissionService admissionService;

    @Transactional(readOnly = true)
    public Supplier validateSupplier(UUID supplierId) {
        return admissionService.validateSupplier(supplierId);
    }
    @Transactional(readOnly = true)
    public String operationalBlockReason(UUID supplierId, String source) {
        return admissionService.operationalBlockReason(supplierId, source);
    }
    @Transactional
    public void applySupplierRisk(UUID supplierId) {
        admissionService.recalculate(supplierId);
    }
    @Transactional(readOnly = true)
    public SupplierComplianceSummary summarize(UUID supplierId) {
        return admissionService.summarize(supplierId);
    }
    public String normalizeCode(String value, String fallback, String fieldName, String... allowed) {
        String normalized = value == null || value.isBlank()
            ? fallback
            : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported " + fieldName + ": " + value);
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
        return party.isSanctionsMatch() || (party.isConflictOfInterestFlag() && "CRITICAL".equals(party.getRiskLevel()));
    }
    private boolean isOpenCapa(SupplierCorrectiveAction action) {
        return !SupplierCorrectiveActionStatus.CLOSED.name().equals(action.getStatus())
            && !SupplierCorrectiveActionStatus.CANCELLED.name().equals(action.getStatus());
    }
    private boolean isHighRiskOpenCapa(SupplierCorrectiveAction action) {
        return isOpenCapa(action) && ("HIGH".equals(action.getSeverity()) || "CRITICAL".equals(action.getSeverity()));
    }
    private boolean isBlockingCapa(SupplierCorrectiveAction action) {
        return isOpenCapa(action) && "CRITICAL".equals(action.getSeverity()) && SupplierCorrectiveActionStatus.OVERDUE.name().equals(action.getStatus());
    }
}
