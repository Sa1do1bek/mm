package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.SupplierAccreditationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Routes the final approve/reject decision of a supplier accreditation application through the
 * generic approval engine. The "rich" decision payload (approvedScope, allowedMaterialGroupIds,
 * validFrom/validTo, conditions, commissionProtocolNo, APPROVED vs CONDITIONALLY_APPROVED) is
 * fixed by the initiator at proposal time via {@code SupplierAccreditationService#proposeDecision}
 * — the approver only sees it and approves/rejects/returns through the generic worklist.
 */
@Component
@RequiredArgsConstructor
public class SupplierAccreditationApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "SUPPLIER_ACCREDITATION";
    private static final String DECISION_STATUS_PROPOSED = "PROPOSED";

    private final SupplierAccreditationApplicationRepository applicationRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierAccreditationDecisionRepository decisionRepository;
    private final SupplierAccreditationService accreditationService;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return find(entityId).getCompanyId();
    }

    @Override
    public String displayName(UUID entityId) {
        SupplierAccreditationApplication application = find(entityId);
        String supplierName = supplierRepository.findByIdAndIsDeletedFalse(application.getSupplierId())
            .map(Supplier::getName)
            .orElse(null);
        String code = application.getCode() == null || application.getCode().isBlank()
            ? application.getId().toString()
            : application.getCode();
        return supplierName == null || supplierName.isBlank() ? code : code + " — " + supplierName;
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        SupplierAccreditationApplication application = find(entityId);
        if (!SupplierAccreditationStatus.COMPLIANCE_CHECK.name().equals(application.getStatus())
                && !SupplierAccreditationStatus.COMMITTEE_REVIEW.name().equals(application.getStatus())) {
            throw new BusinessException(
                "Supplier accreditation must be COMPLIANCE_CHECK or COMMITTEE_REVIEW, current status is " + application.getStatus());
        }
        proposedDecision(application).orElseThrow(
            () -> new BusinessException("No proposed accreditation decision found for application " + entityId));
    }

    @Override
    public void markSubmitted(UUID entityId) {
        SupplierAccreditationApplication application = find(entityId);
        application.setStatus(SupplierAccreditationStatus.PENDING_APPROVAL.name());
        applicationRepository.save(application);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        accreditationService.finalizeApprovedDecision(entityId, comment);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        accreditationService.cancelProposedDecision(entityId, comment);
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        SupplierAccreditationApplication application = find(entityId);
        SupplierAccreditationDecision decision = proposedDecision(application)
            .or(() -> decisionRepository
                .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), application.getCompanyId(), entityId)
                .stream()
                .findFirst())
            .orElse(null);
        if (decision == null) {
            return List.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("decision", decision.getDecision());
        values.put("approvedScope", decision.getApprovedScope());
        values.put("allowedMaterialGroupIds", decision.getAllowedMaterialGroupIds());
        values.put("validFrom", decision.getValidFrom());
        values.put("validTo", decision.getValidTo());
        values.put("conditions", decision.getConditions());
        values.put("commissionProtocolNo", decision.getCommissionProtocolNo());
        values.put("reviewComment", decision.getReviewComment());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return List.of(new ApprovalDetailItem(
            decision.getId() == null ? null : decision.getId().toString(),
            "Proposed accreditation decision",
            values
        ));
    }

    private java.util.Optional<SupplierAccreditationDecision> proposedDecision(SupplierAccreditationApplication application) {
        return decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), application.getCompanyId(), application.getId(), DECISION_STATUS_PROPOSED);
    }

    private SupplierAccreditationApplication find(UUID id) {
        return applicationRepository.findByIdAndIsDeletedFalse(id)
            .filter(application -> TenantContext.currentTenant().equals(application.getTenantId()))
            .orElseThrow(() -> new NotFoundException("SupplierAccreditationApplication not found: " + id));
    }
}
