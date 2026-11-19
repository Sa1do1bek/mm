package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ApprovalOperationRequestRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApprovalOperationExecutionServiceTest {
    private final ApprovalOperationRequestRepository repository = mock(ApprovalOperationRequestRepository.class);
    private final ApprovalOperationHandler handler = mock(ApprovalOperationHandler.class);
    private final ApprovalOperationExecutionService service;

    ApprovalOperationExecutionServiceTest() {
        when(handler.moduleKey()).thenReturn(TransferApproveOperationHandler.MODULE_KEY);
        service = new ApprovalOperationExecutionService(repository, new ApprovalOperationRegistry(List.of(handler)));
    }

    @AfterEach
    void clearContext() {
        CompanyContext.clear();
        TenantContext.clear();
    }

    @Test
    void pendingRequestCannotApplyAndApprovedRequestAppliesOnce() {
        ApprovalOperationRequest request = request("PENDING");
        when(repository.findForUpdateByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.apply(request.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not approved");
        verify(handler, never()).apply(any());

        request.setStatus("APPROVED_NOT_APPLIED");
        service.apply(request.getId());
        service.apply(request.getId());

        assertThat(request.getStatus()).isEqualTo("APPLIED");
        verify(handler, times(1)).apply(request);
    }

    @Test
    void onlyRequesterCanAbandonFailedOperation() {
        ApprovalOperationRequest request = request("APPLY_FAILED");
        when(repository.findForUpdateByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.abandonFailed(request.getId(), "other-user"))
            .isInstanceOf(BusinessException.class);
        service.abandonFailed(request.getId(), "requester");

        assertThat(request.getStatus()).isEqualTo("ABANDONED");
        verify(handler, never()).apply(any());
    }

    private ApprovalOperationRequest request(String status) {
        ApprovalOperationRequest request = new ApprovalOperationRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(CompanyContext.currentCompany());
        request.setModuleKey(TransferApproveOperationHandler.MODULE_KEY);
        request.setTargetEntityId(UUID.randomUUID());
        request.setRequesterUsername("requester");
        request.setStatus(status);
        return request;
    }
}
