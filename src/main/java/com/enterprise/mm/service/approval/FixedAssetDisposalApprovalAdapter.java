package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.service.FixedAssetService;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class FixedAssetDisposalApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = FixedAssetService.DISPOSAL_APPROVAL_MODULE;

    private final FixedAssetService service;

    public FixedAssetDisposalApprovalAdapter(@Lazy FixedAssetService service) {
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
        FixedAsset asset = find(entityId);
        return asset.getCode() == null ? asset.getName() : asset.getCode();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        service.findForApproval(entityId);
    }

    @Override
    public void markSubmitted(UUID entityId) {
        service.findForApproval(entityId);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        service.disposeFromApproval(entityId, comment);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        service.rejectDisposalFromApproval(entityId, comment);
    }

    private FixedAsset find(UUID entityId) {
        return service.findForApproval(entityId);
    }
}
