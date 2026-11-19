package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.repository.ApprovalOperationRequestRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApprovalOperationSubmissionServiceTest {
    private static final String KEY = TransferApproveOperationHandler.MODULE_KEY;
    private final ApprovalOperationRequestRepository repository = mock(ApprovalOperationRequestRepository.class);
    private final ApprovalOperationHandler handler = mock(ApprovalOperationHandler.class);
    private final ApprovalEngineService engine = mock(ApprovalEngineService.class);
    private final ApprovalOperationLifecycleService lifecycle = mock(ApprovalOperationLifecycleService.class);
    private final ApprovalOperationExecutionService execution = mock(ApprovalOperationExecutionService.class);
    private final ApprovalOperationSubmissionService service;

    ApprovalOperationSubmissionServiceTest() {
        when(handler.moduleKey()).thenReturn(KEY);
        service = new ApprovalOperationSubmissionService(repository,
            new ApprovalOperationRegistry(List.of(handler)), engine, lifecycle, execution);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        CompanyContext.clear();
        TenantContext.clear();
    }

    @Test
    void submissionStartsExistingApprovalAndDoesNotApplyBusinessEffect() {
        UUID targetId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        authenticate("requester");
        when(handler.companyId(targetId)).thenReturn(CompanyContext.currentCompany());
        when(handler.displayName(targetId)).thenReturn("Transfer 123");
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ApprovalOperationRequest request = invocation.getArgument(0);
            request.setId(UUID.randomUUID());
            return request;
        });
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.start(eq(KEY), any())).thenAnswer(invocation -> {
            UUID requestId = invocation.getArgument(1);
            return new ApprovalInstanceResponse(approvalId, null, null, KEY, requestId,
                null, null, null, null, null, List.of());
        });

        var result = service.submit(KEY, targetId, 3L, "{}", "request-123");

        assertThat(result.operationRequestId()).isNotNull();
        assertThat(result.approvalInstanceId()).isEqualTo(approvalId);
        verify(handler).validate(targetId, 3L, "{}");
        verify(handler, never()).apply(any());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadIsRejected() {
        UUID targetId = UUID.randomUUID();
        authenticate("requester");
        when(handler.companyId(targetId)).thenReturn(CompanyContext.currentCompany());
        ApprovalOperationRequest existing = new ApprovalOperationRequest();
        existing.setPayloadHash("different-hash");
        when(repository.findByTenantIdAndCompanyIdAndRequesterUsernameAndIdempotencyKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "requester", "request-123"
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(KEY, targetId, 3L, "{}", "request-123"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("different operation");
        verify(engine, never()).start(any(), any());
    }

    @Test
    void domainCommandRequiresMatchingApprovedOperationContext() {
        UUID targetId = UUID.randomUUID();
        assertThatThrownBy(() -> ApprovedOperationContext.require(KEY, targetId))
            .isInstanceOf(BusinessException.class);
        ApprovedOperationContext.run(KEY, targetId, () ->
            org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(
                () -> ApprovedOperationContext.require(KEY, targetId)
            )
        );
        assertThatThrownBy(() -> ApprovedOperationContext.require(KEY, targetId))
            .isInstanceOf(BusinessException.class);
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of())
        );
    }
}
