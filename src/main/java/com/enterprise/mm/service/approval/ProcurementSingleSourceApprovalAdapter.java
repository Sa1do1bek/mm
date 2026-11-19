package com.enterprise.mm.service.approval;

import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Distinct approval-flow registration for the SINGLE_SOURCE governed method. */
@Component
@RequiredArgsConstructor
public class ProcurementSingleSourceApprovalAdapter implements ApprovalModuleAdapter {
    private final ProcurementDirectAwardApprovalAdapter delegate;

    @Override
    public String moduleKey() {
        return ProcurementDirectAwardApprovalAdapter.SINGLE_SOURCE_MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return delegate.companyId(entityId);
    }

    @Override
    public String displayName(UUID entityId) {
        return delegate.displayName(entityId);
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        return delegate.documentLines(entityId);
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        delegate.validateCanSubmit(entityId);
    }

    @Override
    public void markSubmitted(UUID entityId) {
        delegate.markSubmitted(entityId);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        delegate.beforeApproveTask(entityId, step, request);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        delegate.markApproved(entityId, comment);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        delegate.markRejected(entityId, comment);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        delegate.onReturned(entityId, comment);
    }
}
