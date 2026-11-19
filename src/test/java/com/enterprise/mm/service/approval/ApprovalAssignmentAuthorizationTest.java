package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ApprovalAssignmentAuthorizationTest {
    private final ApprovalModuleRegistry moduleRegistry = mock(ApprovalModuleRegistry.class);
    private final ApprovalModuleAdapter moduleAdapter = mock(ApprovalModuleAdapter.class);
    private final ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
    private final ApprovalEngineService service = new ApprovalEngineService(
        null, null, instanceRepository, null, null, moduleRegistry
    );

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void specificallyAssignedUserCanActWithoutAWriterOrManagerRole() {
        ApprovalTask task = new ApprovalTask();
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUsername("assigned-worker");
        attachInstance(task);
        authenticate("assigned-worker", "DEPARTMENT_WORKER");

        assertThatNoException().isThrownBy(() -> service.assertCanAct(task));
    }

    @Test
    void authenticatedButUnassignedUserIsRejectedByTheDomainAuthorization() {
        ApprovalTask task = new ApprovalTask();
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUsername("assigned-worker");
        attachInstance(task);
        authenticate("another-worker", "DEPARTMENT_WORKER");

        assertThatThrownBy(() -> service.assertCanAct(task))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not assigned");
    }

    @Test
    void requesterCannotApproveTheirOwnOperationEvenWhenAssigned() {
        ApprovalTask task = new ApprovalTask();
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUsername("requester");
        attachInstance(task);
        ApprovalInstance instance = instanceRepository.findByIdAndIsDeletedFalse(task.getApprovalInstanceId()).orElseThrow();
        when(moduleAdapter.requesterUsername(instance.getEntityId())).thenReturn("requester");
        authenticate("requester", "DEPARTMENT_WORKER");

        assertThatThrownBy(() -> service.assertCanAct(task))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Requester cannot approve");
    }

    @Test
    void currentAssignedTaskExposesApproveRejectAndReturnActions() {
        ApprovalTask task = new ApprovalTask();
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUsername("assigned-worker");
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setStepOrder(2);
        ApprovalInstance instance = new ApprovalInstance();
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        instance.setCurrentStepOrder(2);
        instance.setModuleKey("PURCHASE_REQUEST");
        instance.setEntityId(UUID.randomUUID());
        when(moduleRegistry.adapter("PURCHASE_REQUEST")).thenReturn(moduleAdapter);
        when(moduleAdapter.isApprovalTaskActive(instance.getEntityId())).thenReturn(true);
        authenticate("assigned-worker", "DEPARTMENT_WORKER");

        assertThat(service.allowedActions(task, instance, true))
            .containsExactly("APPROVE", "REJECT", "RETURN");
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private void attachInstance(ApprovalTask task) {
        UUID instanceId = UUID.randomUUID();
        task.setApprovalInstanceId(instanceId);
        ApprovalInstance instance = new ApprovalInstance();
        instance.setTenantId(TenantContext.currentTenant());
        instance.setCompanyId(CompanyContext.currentCompany());
        instance.setModuleKey("PURCHASE_REQUEST");
        instance.setEntityId(UUID.randomUUID());
        when(instanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));
        when(moduleRegistry.adapter("PURCHASE_REQUEST")).thenReturn(moduleAdapter);
    }
}
