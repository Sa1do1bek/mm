package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalFlowType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskResponse;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ApprovalFlowAdminService;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = "ADMIN")
class ApprovalFlowIsApproverTest {

    @Autowired
    private ApprovalFlowAdminService adminService;

    @Autowired
    private ApprovalEngineService engineService;

    @Autowired
    private com.enterprise.mm.repository.ApprovalFlowRepository flowRepository;




    @Test
    void sequentialFlowFailsWithLessThanTwoSteps() {
        ApprovalFlowRequest request = new ApprovalFlowRequest(
            null,
            "AF-TEST-1STEP-" + UUID.randomUUID().toString().substring(0, 6),
            "One step flow",
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ApprovalFlowType.SEQUENTIAL,
            null,
            ApprovalRequestType.GOODS,
            true,
            "ACTIVE",
            "Single step flow",
            List.of(
                new ApprovalFlowStepRequest(
                    "STEP-1", "Final Approver", 1, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, true, true, "Description"
                )
            ),
            null
        );

        assertThatThrownBy(() -> adminService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("require at least 2 steps");
    }

    @Test
    void sequentialFlowFailsWhenLastStepIsNotApprover() {
        ApprovalFlowRequest request = new ApprovalFlowRequest(
            null,
            "AF-TEST-NO-APPROVER-" + UUID.randomUUID().toString().substring(0, 6),
            "No approver flow",
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ApprovalFlowType.SEQUENTIAL,
            null,
            ApprovalRequestType.GOODS,
            true,
            "ACTIVE",
            "No approver flow",
            List.of(
                new ApprovalFlowStepRequest(
                    "STEP-1", "Executor Step", 1, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, false, true, "Description"
                ),
                new ApprovalFlowStepRequest(
                    "STEP-2", "Executor Step 2", 2, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, false, true, "Description"
                )
            ),
            null
        );

        assertThatThrownBy(() -> adminService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must be the final approver");
    }

    @Test
    void sequentialFlowFailsWhenEarlierStepIsApprover() {
        ApprovalFlowRequest request = new ApprovalFlowRequest(
            null,
            "AF-TEST-EARLY-APPROVER-" + UUID.randomUUID().toString().substring(0, 6),
            "Early approver flow",
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ApprovalFlowType.SEQUENTIAL,
            null,
            ApprovalRequestType.GOODS,
            true,
            "ACTIVE",
            "Early approver flow",
            List.of(
                new ApprovalFlowStepRequest(
                    "STEP-1", "Approver 1", 1, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, true, true, "Description"
                ),
                new ApprovalFlowStepRequest(
                    "STEP-2", "Approver 2", 2, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, true, true, "Description"
                )
            ),
            null
        );

        assertThatThrownBy(() -> adminService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only the last step");
    }

    @Test
    void nonApproverStepCannotHaveCanReturn() {
        ApprovalFlowStepRequest executorStep = new ApprovalFlowStepRequest(
            "STEP-1", "Executor Step", 1, null, null, null,
            ApprovalAssigneeType.ROLE, "ADMIN", null, null, true, false, true, "Description"
        );

        ApprovalFlowRequest request = new ApprovalFlowRequest(
            null,
            "AF-TEST-RETURN-ERR-" + UUID.randomUUID().toString().substring(0, 6),
            "Return error flow",
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ApprovalFlowType.SEQUENTIAL,
            null,
            ApprovalRequestType.GOODS,
            true,
            "ACTIVE",
            "Return error flow",
            List.of(
                executorStep,
                new ApprovalFlowStepRequest(
                    "STEP-2", "Final Approver", 2, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, true, true, true, "Description"
                )
            ),
            null
        );

        assertThatThrownBy(() -> adminService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only the final approver step can return");
    }

    @Autowired
    private com.enterprise.mm.repository.PurchaseRequestRepository purchaseRequestRepository;

    @Autowired
    private com.enterprise.mm.repository.PurchaseRequestItemRepository purchaseRequestItemRepository;

    @Test
    void transferDraftCanPreviewApprovalRouteBeforeOperationRequestExists() {
        flowRepository.findAll().stream()
            .filter(flow -> TransferApproveOperationHandler.MODULE_KEY.equals(flow.getModuleKey()))
            .forEach(flow -> {
                flow.setActive(false);
                flowRepository.save(flow);
            });

        ApprovalFlowResponse flow = adminService.create(new ApprovalFlowRequest(
            null,
            "AF-TRANSFER-PREVIEW-" + UUID.randomUUID().toString().substring(0, 6),
            "Transfer approval preview",
            TransferApproveOperationHandler.MODULE_KEY,
            ApprovalFlowType.SEQUENTIAL,
            null,
            null,
            true,
            "ACTIVE",
            "Transfer approval route",
            List.of(
                new ApprovalFlowStepRequest("STEP-1", "Warehouse review", 1, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, false, true, "Review"),
                new ApprovalFlowStepRequest("STEP-2", "Final approval", 2, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, true, true, true, "Approve")
            ),
            null
        ));

        var preview = engineService.previewFlow(TransferApproveOperationHandler.MODULE_KEY, null);
        assertThat(preview.approvalFlowName()).isEqualTo(flow.name());
        assertThat(preview.steps()).hasSize(2).allMatch(step -> step.status() == null);
    }


    @Test
    void validSequentialFlowSavesAndExecutesWithExecutorAndApproverSemantics() {
        flowRepository.findAll().stream()
            .filter(f -> PurchaseRequestApprovalAdapter.MODULE_KEY.equals(f.getModuleKey()))
            .forEach(f -> {
                f.setActive(false);
                flowRepository.save(f);
            });

        ApprovalFlowRequest request = new ApprovalFlowRequest(

            null,
            "AF-TEST-VALID-" + UUID.randomUUID().toString().substring(0, 6),
            "Valid 2-step flow",
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            ApprovalFlowType.SEQUENTIAL,
            null,
            ApprovalRequestType.GOODS,
            true,
            "ACTIVE",
            "Valid flow",
            List.of(
                new ApprovalFlowStepRequest(
                    "STEP-1", "Executor Step", 1, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, false, false, true, "Executor"
                ),
                new ApprovalFlowStepRequest(
                    "STEP-2", "Final Approver", 2, null, null, null,
                    ApprovalAssigneeType.ROLE, "ADMIN", null, null, true, true, true, "Approver"
                )
            ),
            null
        );

        ApprovalFlowResponse flow = adminService.create(request);
        assertThat(flow.steps()).hasSize(2);
        assertThat(flow.steps().get(0).isApprover()).isFalse();
        assertThat(flow.steps().get(1).isApprover()).isTrue();

        com.enterprise.mm.entity.PurchaseRequest pr = new com.enterprise.mm.entity.PurchaseRequest();
        pr.setCode("PR-TEST-" + UUID.randomUUID().toString().substring(0, 6));
        pr.setName("Test purchase request");
        pr.setStatus("DRAFT");
        pr.setRequestType(RequestType.GOODS);
        pr.setTotalAmount(java.math.BigDecimal.valueOf(100));
        pr = purchaseRequestRepository.save(pr);

        com.enterprise.mm.entity.PurchaseRequestItem item = new com.enterprise.mm.entity.PurchaseRequestItem();
        item.setCode("PRI-TEST-" + UUID.randomUUID().toString().substring(0, 6));
        item.setName("Test item");
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setQuantity(java.math.BigDecimal.ONE);
        item.setEstimatedPrice(java.math.BigDecimal.valueOf(100));
        purchaseRequestItemRepository.save(item);

        UUID previewDocumentId = pr.getId();
        var preview = engineService.previewFlow(PurchaseRequestApprovalAdapter.MODULE_KEY, previewDocumentId);
        assertThat(preview.approvalFlowName()).isEqualTo(flow.name());
        assertThat(preview.steps()).hasSize(2);
        assertThat(preview.steps()).allMatch(step -> step.status() == null);
        assertThat(preview.steps().get(0).isApprover()).isFalse();
        assertThat(preview.steps().get(1).isApprover()).isTrue();
        assertThatThrownBy(() -> engineService.currentInstance(PurchaseRequestApprovalAdapter.MODULE_KEY, previewDocumentId))
            .hasMessageContaining("Approval instance not found");

        var instance = engineService.start(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());

        assertThat(instance.approvalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        assertThat(instance.currentStepOrder()).isEqualTo(1);

        ApprovalTaskResponse step1Task = engineService.currentInstance(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId()).tasks()
            .stream().filter(t -> t.taskStatus() == ApprovalTaskStatus.PENDING).findFirst().orElseThrow();
        assertThat(step1Task.isApprover()).isFalse();
        assertThat(step1Task.nextAction()).isEqualTo("COMPLETE_EXECUTION");

        var afterStep1 = engineService.approveTask(step1Task.id(), new ApprovalTaskDecisionRequest("Completed executor step", null));
        assertThat(afterStep1.approvalStatus()).isEqualTo(ApprovalInstanceStatus.PENDING);
        assertThat(afterStep1.currentStepOrder()).isEqualTo(2);

        ApprovalTaskResponse step2Task = engineService.currentInstance(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId()).tasks()
            .stream().filter(t -> t.taskStatus() == ApprovalTaskStatus.PENDING).findFirst().orElseThrow();
        assertThat(step2Task.isApprover()).isTrue();
        assertThat(step2Task.nextAction()).isEqualTo("REVIEW_AND_DECIDE");


        var finalInstance = engineService.approveTask(step2Task.id(), new ApprovalTaskDecisionRequest("Approved final step", null));
        assertThat(finalInstance.approvalStatus()).isEqualTo(ApprovalInstanceStatus.APPROVED);

        adminService.createStep(flow.id(), new ApprovalFlowStepRequest(
            "STEP-3-" + UUID.randomUUID().toString().substring(0, 6), "New final approver", 3,
            null, null, null, ApprovalAssigneeType.ROLE, "ADMIN", null, null,
            false, true, true, "Added after this document was approved"
        ));
        var completed = engineService.currentInstance(PurchaseRequestApprovalAdapter.MODULE_KEY, pr.getId());
        assertThat(completed.approvalStatus()).isEqualTo(ApprovalInstanceStatus.APPROVED);
        assertThat(completed.tasks()).hasSize(2);
        assertThat(completed.tasks().getFirst().steps()).hasSize(2)
            .allMatch(step -> step.status() == ApprovalTaskStatus.APPROVED);
        assertThat(completed.tasks().getFirst().steps().getLast().isApprover()).isTrue();
        assertThat(completed.tasks().stream().filter(task -> task.stepOrder() == 2).findFirst().orElseThrow().isApprover())
            .isTrue();
    }


}
