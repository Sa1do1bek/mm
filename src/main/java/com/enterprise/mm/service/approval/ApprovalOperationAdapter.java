package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApprovalOperationAdapter implements ApprovalModuleAdapter {
    private final ObjectProvider<ApprovalOperationLifecycleService> lifecycleProvider;
    private final ObjectProvider<ApprovalOperationRegistry> operationsProvider;

    private ApprovalOperationLifecycleService lifecycle() {
        return lifecycleProvider.getObject();
    }

    private ApprovalOperationRegistry operations() {
        return operationsProvider.getObject();
    }

    @Override
    public String moduleKey() {
        return TransferApproveOperationHandler.MODULE_KEY;
    }

    @Override
    public List<String> additionalModuleKeys() {
        return List.of(InventoryPeriodCloseOperationHandler.MODULE_KEY);
    }

    @Override
    public UUID companyId(UUID entityId) {
        return lifecycle().require(entityId).getCompanyId();
    }

    @Override
    public String requesterUsername(UUID entityId) {
        return lifecycle().require(entityId).getRequesterUsername();
    }

    @Override
    public String displayName(UUID entityId) {
        ApprovalOperationRequest request = lifecycle().require(entityId);
        return request.getName();
    }

    @Override
    public void requireDetailRead(UUID entityId) {
        ApprovalOperationRequest request = lifecycle().require(entityId);
        operations().handler(request.getModuleKey()).requireDetailRead(request.getTargetEntityId());
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ApprovalOperationRequest request = lifecycle().require(entityId);
        if (!"DRAFT".equals(request.getStatus()) && !"RETURNED".equals(request.getStatus())) {
            throw new BusinessException("Operation request is not ready for submission");
        }
        operations().handler(request.getModuleKey()).validate(
            request.getTargetEntityId(), request.getTargetVersion(), request.getPayloadJson()
        );
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        ApprovalOperationRequest request = lifecycle().require(entityId);
        return operations().handler(request.getModuleKey()).reviewItems(request);
    }

    @Override
    public void markSubmitted(UUID entityId) {
        lifecycle().submitted(entityId);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        lifecycle().approved(entityId);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        lifecycle().rejected(entityId);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        lifecycle().returned(entityId);
    }

    @Override
    public void onCancelled(UUID entityId, String comment) {
        lifecycle().cancelled(entityId);
    }
}
