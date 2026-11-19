package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest;
import com.enterprise.mm.repository.ApprovalFlowParallelAssigneeRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.AuditService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApprovalFlowAdminServiceAuditTest {

    @Test
    void assetAcceptanceFlowIgnoresDepartmentScopeBecauseApprovalLookupIsCompanyLevel() {
        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalFlowParallelAssigneeRepository parallelAssigneeRepository =
            mock(ApprovalFlowParallelAssigneeRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);
        AuditService auditService = mock(AuditService.class);
        UserRepository userRepository = mock(UserRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);

        when(registry.adapter("ASSET_ACCEPTANCE")).thenReturn(adapter);
        when(adapter.usesRequestType()).thenReturn(false);
        when(flowRepository.save(any(ApprovalFlow.class))).thenAnswer(invocation -> {
            ApprovalFlow saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        ApprovalFlowAdminService service = new ApprovalFlowAdminService(
            flowRepository,
            stepRepository,
            parallelAssigneeRepository,
            registry,
            auditService,
            userRepository,
            departmentRepository
        );

        UUID selectedDepartment = UUID.randomUUID();
        service.create(new ApprovalFlowRequest(
            CompanyContext.currentCompany(),
            "AA-FLOW",
            "Asset acceptance flow",
            "ASSET_ACCEPTANCE",
            null,
            selectedDepartment,
            null,
            true,
            "ACTIVE",
            null,
            null,
            null
        ));

        ArgumentCaptor<ApprovalFlow> savedFlow = ArgumentCaptor.forClass(ApprovalFlow.class);
        verify(flowRepository).save(savedFlow.capture());
        assertThat(savedFlow.getValue().getModuleKey()).isEqualTo("ASSET_ACCEPTANCE");
        assertThat(savedFlow.getValue().getDepartmentId()).isNull();
        verify(auditService).log(eq("ApprovalFlow"), any(UUID.class), eq("CREATE"), eq("AA-FLOW"), any(), any());
    }

    @Test
    void auditsEveryApprovalFlowAndStepMutation() {
        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalFlowParallelAssigneeRepository parallelAssigneeRepository =
            mock(ApprovalFlowParallelAssigneeRepository.class);
        ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
        ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);
        AuditService auditService = mock(AuditService.class);
        UserRepository userRepository = mock(UserRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);

        UUID flowId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        ApprovalFlow flow = flow(flowId);
        ApprovalFlowStep step = step(stepId, flowId);

        when(registry.adapter("SERVICE_REQUEST")).thenReturn(adapter);
        when(flowRepository.findByIdAndIsDeletedFalse(flowId)).thenReturn(Optional.of(flow));
        when(stepRepository.findByIdAndIsDeletedFalse(stepId)).thenReturn(Optional.of(step));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), flowId)).thenReturn(List.of());
        when(flowRepository.save(any(ApprovalFlow.class))).thenAnswer(invocation -> {
            ApprovalFlow saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(flowId);
            }
            return saved;
        });
        when(stepRepository.save(any(ApprovalFlowStep.class))).thenAnswer(invocation -> {
            ApprovalFlowStep saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(stepId);
            }
            return saved;
        });

        ApprovalFlowAdminService service = new ApprovalFlowAdminService(
            flowRepository,
            stepRepository,
            parallelAssigneeRepository,
            registry,
            auditService,
            userRepository,
            departmentRepository
        );

        ApprovalFlowRequest flowRequest = new ApprovalFlowRequest(
            CompanyContext.currentCompany(),
            "SR-FLOW",
            "Service request flow",
            "SERVICE_REQUEST",
            null,
            null,
            null,
            true,
            "ACTIVE",
            null,
            null,
            null
        );
        ApprovalFlowStepRequest stepRequest = new ApprovalFlowStepRequest(
            "SR-STEP",
            "Service request approval",
            1,
            null,
            null,
            null,
            ApprovalAssigneeType.ROLE,
            "DEPARTMENT_HEAD",
            null,
            null,
            true,
            true,
            true,
            null
        );


        service.create(flowRequest);
        service.update(flowId, flowRequest);
        service.delete(flowId);
        service.createStep(flowId, stepRequest);
        service.updateStep(stepId, stepRequest);
        service.deleteStep(stepId);

        verify(auditService).log("ApprovalFlow", flowId, "CREATE", "SR-FLOW", null, null);
        verify(auditService).log("ApprovalFlow", flowId, "UPDATE", "SR-FLOW", null, null);
        verify(auditService).log("ApprovalFlow", flowId, "DELETE", "SR-FLOW", null, null);
        verify(auditService).log("ApprovalFlowStep", stepId, "CREATE", "SR-STEP", null, null);
        verify(auditService).log("ApprovalFlowStep", stepId, "UPDATE", "SR-STEP", null, null);
        verify(auditService).log("ApprovalFlowStep", stepId, "DELETE", "SR-STEP", null, null);
    }

    private ApprovalFlow flow(UUID id) {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(id);
        flow.setTenantId(TenantContext.currentTenant());
        flow.setCompanyId(CompanyContext.currentCompany());
        flow.setCode("SR-FLOW");
        flow.setName("Service request flow");
        flow.setModuleKey("SERVICE_REQUEST");
        flow.setStatus("ACTIVE");
        flow.setActive(true);
        return flow;
    }

    private ApprovalFlowStep step(UUID id, UUID flowId) {
        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(id);
        step.setTenantId(TenantContext.currentTenant());
        step.setCompanyId(CompanyContext.currentCompany());
        step.setApprovalFlowId(flowId);
        step.setCode("SR-STEP");
        step.setName("Service request approval");
        step.setStatus("ACTIVE");
        step.setStepOrder(1);
        return step;
    }
}
