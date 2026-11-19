package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.AssetHandover;
import com.enterprise.mm.service.AssetHandoverService;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class AssetHandoverApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = AssetHandoverService.APPROVAL_MODULE;

    private final AssetHandoverService service;

    public AssetHandoverApprovalAdapter(@Lazy AssetHandoverService service) {
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
        AssetHandover handover = find(entityId);
        return handover.getCode() == null ? handover.getName() : handover.getCode();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        service.findForApproval(entityId);
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

    private AssetHandover find(UUID entityId) {
        return service.findForApproval(entityId);
    }
}
