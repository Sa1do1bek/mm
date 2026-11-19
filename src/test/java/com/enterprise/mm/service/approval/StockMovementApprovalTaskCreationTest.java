package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.ApprovalHistoryRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.service.AuditService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockMovementApprovalTaskCreationTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MOVEMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID FLOW_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID STEP_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID INSTANCE_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void stockMovementSubmitCreatesPendingApprovalTask() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        ApprovalFlowRepository flowRepository = mock(ApprovalFlowRepository.class);
        ApprovalFlowStepRepository stepRepository = mock(ApprovalFlowStepRepository.class);
        ApprovalInstanceRepository instanceRepository = mock(ApprovalInstanceRepository.class);
        ApprovalTaskRepository taskRepository = mock(ApprovalTaskRepository.class);
        ApprovalHistoryRepository historyRepository = mock(ApprovalHistoryRepository.class);
        StockMovementRepository movementRepository = mock(StockMovementRepository.class);

        StockMovement movement = draftMovement();
        ApprovalFlow flow = approvalFlow();
        ApprovalFlowStep step = approvalStep();

        when(movementRepository.findByIdAndIsDeletedFalse(MOVEMENT_ID)).thenReturn(Optional.of(movement));
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(instanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TENANT, COMPANY, StockMovementApprovalAdapter.MODULE_KEY, MOVEMENT_ID
        )).thenReturn(Optional.empty());
        when(flowRepository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY, StockMovementApprovalAdapter.MODULE_KEY
        )).thenReturn(Optional.of(flow));
        when(flowRepository.findByIdAndIsDeletedFalse(FLOW_ID)).thenReturn(Optional.of(flow));
        when(stepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(TENANT, FLOW_ID))
            .thenReturn(List.of(step));
        when(instanceRepository.save(any(ApprovalInstance.class))).thenAnswer(invocation -> {
            ApprovalInstance instance = invocation.getArgument(0);
            instance.setId(INSTANCE_ID);
            return instance;
        });
        when(taskRepository.save(any(ApprovalTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementApprovalAdapter adapter = new StockMovementApprovalAdapter(
            movementRepository,
            mock(StockMovementDocumentRepository.class),
            mock(AuditService.class),
            mock(org.springframework.beans.factory.ObjectProvider.class)
        );
        ApprovalEngineService engine = new ApprovalEngineService(
            flowRepository,
            stepRepository,
            instanceRepository,
            taskRepository,
            historyRepository,
            new ApprovalModuleRegistry(List.of(adapter))
        );

        engine.start(StockMovementApprovalAdapter.MODULE_KEY, MOVEMENT_ID);

        ArgumentCaptor<ApprovalTask> taskCaptor = ArgumentCaptor.forClass(ApprovalTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        ApprovalTask task = taskCaptor.getValue();
        assertThat(task.getApprovalInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(task.getApprovalFlowStepId()).isEqualTo(STEP_ID);
        assertThat(task.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        assertThat(task.getAssigneeRole()).isEqualTo("WAREHOUSE_MANAGER");
        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.PENDING.name());
    }

    private StockMovement draftMovement() {
        StockMovement movement = new StockMovement();
        movement.setId(MOVEMENT_ID);
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setCode("SMV-1");
        movement.setName("Stock movement");
        movement.setStatus(StockMovementStatus.DRAFT.name());
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(BigDecimal.ONE);
        movement.setUnitCost(BigDecimal.ZERO);
        movement.setMovementDate(LocalDate.now());
        return movement;
    }

    private ApprovalFlow approvalFlow() {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(FLOW_ID);
        flow.setTenantId(TENANT);
        flow.setCompanyId(COMPANY);
        flow.setModuleKey(StockMovementApprovalAdapter.MODULE_KEY);
        flow.setFlowType(ApprovalFlowType.SEQUENTIAL);
        flow.setActive(true);
        flow.setStatus("ACTIVE");
        return flow;
    }

    private ApprovalFlowStep approvalStep() {
        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setId(STEP_ID);
        step.setTenantId(TENANT);
        step.setCompanyId(COMPANY);
        step.setApprovalFlowId(FLOW_ID);
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole("WAREHOUSE_MANAGER");
        step.setStatus("ACTIVE");
        return step;
    }
}
