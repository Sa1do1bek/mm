package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.AssetOpeningImport;
import com.enterprise.mm.service.AssetOpeningImportService;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class AssetOpeningImportApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = AssetOpeningImportService.APPROVAL_MODULE;

    private final AssetOpeningImportService service;

    public AssetOpeningImportApprovalAdapter(@Lazy AssetOpeningImportService service) {
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
        AssetOpeningImport openingImport = find(entityId);
        return openingImport.getCode() == null ? openingImport.getName() : openingImport.getCode();
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

    private AssetOpeningImport find(UUID entityId) {
        return service.findForApproval(entityId);
    }
}
