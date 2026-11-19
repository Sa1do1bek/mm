package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.AssetAcceptanceService;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class AssetAcceptanceApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = AssetAcceptanceService.APPROVAL_MODULE;

    private final AssetAcceptanceService service;

    public AssetAcceptanceApprovalAdapter(@Lazy AssetAcceptanceService service) {
        this.service = service;
    }

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
        AssetAcceptance acceptance = find(entityId);
        return acceptance.getCode() == null ? acceptance.getName() : acceptance.getCode();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        if (!service.allUnitsResolved(entityId)) {
            throw new BusinessException(
                "All asset acceptance units must be accepted or rejected before approval",
                "ASSET_ACCEPTANCE_UNRESOLVED_UNITS"
            );
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        service.markSubmittedFromApproval(entityId);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        service.markApprovedFromApproval(entityId);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        service.markRejectedFromApproval(entityId);
    }

    private AssetAcceptance find(UUID entityId) {
        return service.findForApproval(entityId);
    }
}
