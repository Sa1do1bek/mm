package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.IntegrationEventDirection;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.payload.WorkCompletionActCreateRequest;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.payload.WorkCompletionActUpdateRequest;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.ContractInvoiceService;
import com.enterprise.mm.service.WorkCompletionActService;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.WorkCompletionActApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "work-approver", roles = {"ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER"})
class WorkCompletionActFlowTest {
    @Autowired private WorkCompletionActService service;
    @Autowired private ContractInvoiceService contractInvoiceService;
    @Autowired private ContractPaymentRepository contractPaymentRepository;
    @Autowired private IntegrationEventRepository integrationEventRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ContractRepository contractRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository itemRepository;
    @Autowired private WorkCompletionActRepository workCompletionActRepository;
    @Autowired private ApprovalFlowRepository approvalFlowRepository;
    @Autowired private ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired private ApprovalEngineService approvalEngineService;
    @Autowired private WorkCompletionActApprovalAdapter workCompletionActApprovalAdapter;
    @Autowired private AttachmentService attachmentService;
    @Autowired private PurchaseOrderService purchaseOrderService;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void ensureWorkCompletionActApprovalFlow() {
        ApprovalFlow flow = approvalFlowRepository
            .findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
                com.enterprise.mm.common.TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                WorkCompletionActApprovalAdapter.MODULE_KEY
            )
            .orElseGet(() -> {
                ApprovalFlow created = new ApprovalFlow();
                created.setCode("TEST-WCA-FLOW-" + shortId());
                created.setName("Work completion act approval flow");
                created.setModuleKey(WorkCompletionActApprovalAdapter.MODULE_KEY);
                created.setStatus("ACTIVE");
                created.setActive(true);
                return approvalFlowRepository.save(created);
            });
        if (approvalFlowStepRepository.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
            com.enterprise.mm.common.TenantContext.currentTenant(), flow.getId()).isEmpty()) {
            ApprovalFlowStep step = new ApprovalFlowStep();
            step.setApprovalFlowId(flow.getId());
            step.setCode("TEST-WCA-STEP-" + shortId());
            step.setName("Work completion act approval");
            step.setStatus("ACTIVE");
            step.setStepOrder(1);
            step.setAssigneeType(ApprovalAssigneeType.ROLE);
            step.setAssigneeRole("PROCUREMENT_MANAGER");
            step.setCanReturn(true);
            approvalFlowStepRepository.save(step);
        }
    }

    @Test
    void validCreateDefaultsDraftAndKeepsGeneratedCodeOutOfName() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActCreateRequest request = request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400));
        request.setCode(null);
        request.setName(null);
        request.setStatus("APPROVED");
        request.setAcceptedAt(java.time.LocalDateTime.now());
        request.setAcceptedBy("client");
        request.setDocumentReviewedAt(java.time.LocalDateTime.now());
        request.setFinanceReviewedAt(java.time.LocalDateTime.now());
        request.setSentToPaymentAt(java.time.LocalDateTime.now());
        request.setPaidAt(java.time.LocalDateTime.now());
        request.setContractConsumedAt(java.time.LocalDateTime.now());
        request.setPaymentReference("CLIENT-PAYMENT");

        WorkCompletionActResponse response = service.create(request);

        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getCode()).startsWith("WORK_COMPLETION_ACT-");
        assertThat(response.getName()).contains("Work Completion Act");
        assertThat(response.getName()).isNotEqualTo(response.getCode());
        assertThat(response.getAcceptedAt()).isNull();
        assertThat(response.getAcceptedBy()).isNull();
        assertThat(response.getDocumentReviewedAt()).isNull();
        assertThat(response.getFinanceReviewedAt()).isNull();
        assertThat(response.getSentToPaymentAt()).isNull();
        assertThat(response.getPaidAt()).isNull();
        assertThat(response.getContractConsumedAt()).isNull();
        assertThat(response.getPaymentReference()).isNull();
        assertThat(response.getPurchaseOrderId()).isEqualTo(fixture.purchaseOrder().getId());
        assertThat(response.getPurchaseOrderItemId()).isEqualTo(fixture.item().getId());
    }

    @Test
    void rejectsNonWorksPurchaseOrder() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.GOODS, ProcurementLineType.WORK);

        assertThatThrownBy(() -> service.create(request(fixture, BigDecimal.ONE, BigDecimal.TEN)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("WORKS");
    }

    @Test
    void rejectsNonWorkPurchaseOrderItem() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.MATERIAL);

        assertThatThrownBy(() -> service.create(request(fixture, BigDecimal.ONE, BigDecimal.TEN)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("WORK");
    }

    @Test
    void rejectsPurchaseOrderItemFromAnotherPurchaseOrder() {
        Fixture first = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        Fixture second = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActCreateRequest request = request(first, BigDecimal.ONE, BigDecimal.TEN);
        request.setPurchaseOrderItemId(second.item().getId());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("belong");
    }

    @Test
    void rejectsContractMismatch() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        Contract otherContract = contract(fixture.supplier());
        WorkCompletionActCreateRequest request = request(fixture, BigDecimal.ONE, BigDecimal.TEN);
        request.setContractId(otherContract.getId());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("contract");
    }

    @Test
    void createDefaultsContractIdFromPurchaseOrderWhenOmitted() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActCreateRequest request = request(fixture, BigDecimal.ONE, BigDecimal.valueOf(100));
        request.setContractId(null);

        WorkCompletionActResponse response = service.create(request);

        assertThat(response.getContractId()).isEqualTo(fixture.contract().getId());
    }

    @Test
    void rejectsInvalidQuantityDateAndRemarks() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);

        WorkCompletionActCreateRequest zeroQuantity = request(fixture, BigDecimal.ZERO, BigDecimal.TEN);
        assertThatThrownBy(() -> service.create(zeroQuantity))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("positive");

        WorkCompletionActCreateRequest invalidDate = request(fixture, BigDecimal.ONE, BigDecimal.valueOf(100));
        invalidDate.setPeriodStart(LocalDate.of(2026, 2, 1));
        invalidDate.setPeriodEnd(LocalDate.of(2026, 1, 31));
        assertThatThrownBy(() -> service.create(invalidDate))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("periodEnd");

        WorkCompletionActCreateRequest missingRemarks = request(fixture, BigDecimal.ONE, BigDecimal.valueOf(100));
        missingRemarks.setInspectionResult("PASSED_WITH_REMARKS");
        missingRemarks.setRemarks(" ");
        assertThatThrownBy(() -> service.create(missingRemarks))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("remarks");
    }

    @Test
    void rejectsCumulativeOverCompletion() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        service.create(request(fixture, BigDecimal.valueOf(6), BigDecimal.valueOf(600)));

        assertThatThrownBy(() -> service.create(request(fixture, BigDecimal.valueOf(5), BigDecimal.valueOf(500))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("quantity");
    }

    @Test
    void rejectsSingleWorkActAmountAbovePurchaseOrderLineValue() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);

        assertThatThrownBy(() -> service.create(request(
            fixture,
            BigDecimal.ONE,
            new BigDecimal("1000.0001")
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("amount");
    }

    @Test
    void rejectsScaleSafeNonProportionalWorkActAmount() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        service.create(request(fixture, BigDecimal.valueOf(4), new BigDecimal("400.0000")));

        assertThatThrownBy(() -> service.create(request(
            fixture,
            BigDecimal.valueOf(4),
            new BigDecimal("400.0001")
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("amount");
    }

    @Test
    void allowsMultiplePartialWorkActsWithinPurchaseOrderLineValue() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);

        WorkCompletionActResponse first = service.create(request(
            fixture,
            BigDecimal.valueOf(3),
            new BigDecimal("300.0000")
        ));
        WorkCompletionActResponse second = service.create(request(
            fixture,
            BigDecimal.valueOf(4),
            new BigDecimal("400.0000")
        ));

        assertThat(first.getAmount()).isEqualByComparingTo("300.0000");
        assertThat(second.getAmount()).isEqualByComparingTo("400.0000");
    }

    @Test
    void rejectsPositiveActAmountWhenPurchaseOrderLineUnitPriceIsMissing() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        fixture.item().setUnitPrice(null);
        itemRepository.save(fixture.item());

        assertThatThrownBy(() -> service.create(request(fixture, BigDecimal.ONE, BigDecimal.TEN)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("unit price");
    }

    @Test
    void excludesCurrentActWhenUpdating() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse created = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        WorkCompletionActUpdateRequest update = new WorkCompletionActUpdateRequest();
        copy(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)), update);
        update.setRemarks("Updated remarks");
        update.setStatus("PAID");
        update.setAcceptedAt(java.time.LocalDateTime.now());
        update.setAcceptedBy("client");
        update.setDocumentReviewedAt(java.time.LocalDateTime.now());
        update.setFinanceReviewedAt(java.time.LocalDateTime.now());
        update.setSentToPaymentAt(java.time.LocalDateTime.now());
        update.setPaidAt(java.time.LocalDateTime.now());
        update.setContractConsumedAt(java.time.LocalDateTime.now());
        update.setPaymentReference("CLIENT-PAYMENT");

        WorkCompletionActResponse updated = service.update(created.getId(), update);

        assertThat(updated.getCompletedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(updated.getRemarks()).isEqualTo("Updated remarks");
        assertThat(updated.getStatus()).isEqualTo("DRAFT");
        assertThat(updated.getAcceptedAt()).isNull();
        assertThat(updated.getAcceptedBy()).isNull();
        assertThat(updated.getDocumentReviewedAt()).isNull();
        assertThat(updated.getFinanceReviewedAt()).isNull();
        assertThat(updated.getSentToPaymentAt()).isNull();
        assertThat(updated.getPaidAt()).isNull();
        assertThat(updated.getContractConsumedAt()).isNull();
        assertThat(updated.getPaymentReference()).isNull();
    }

    @Test
    void rejectsRestoreWhenRestoredActWouldOvercompleteLine() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse oldAct = service.create(request(fixture, BigDecimal.valueOf(6), BigDecimal.valueOf(600)));
        service.softDelete(oldAct.getId());
        service.create(request(fixture, BigDecimal.valueOf(6), BigDecimal.valueOf(600)));

        assertThatThrownBy(() -> service.restore(oldAct.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("quantity");
    }

    @Test
    void searchesByMilestoneAndInspectionResult() throws Exception {
        Fixture first = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActCreateRequest firstRequest = request(first, BigDecimal.ONE, BigDecimal.valueOf(100));
        firstRequest.setMilestone("FOUNDATION-SEARCH");
        firstRequest.setInspectionResult("PASSED");
        service.create(firstRequest);

        Fixture second = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActCreateRequest secondRequest = request(second, BigDecimal.ONE, BigDecimal.valueOf(100));
        secondRequest.setMilestone("ROOF-SEARCH");
        secondRequest.setInspectionResult("FAILED");
        service.create(secondRequest);

        mockMvc.perform(get("/api/work-completion-acts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("milestone", "FOUNDATION-SEARCH")
                .param("inspectionResult", "PASSED")
                .param("page", "0")
                .param("size", "20")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].milestone").value("FOUNDATION-SEARCH"));
    }

    @Test
    void viewerCannotCreateWorkCompletionAct() throws Exception {
        mockMvc.perform(post("/api/work-completion-acts")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void approvalRequiresActAttachment() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400)));

        service.submitForApproval(act.getId());

        assertThatThrownBy(() -> approve(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ACT attachment");
    }

    @Test
    void approvalRejectsFailedInspection() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActCreateRequest request = request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400));
        request.setInspectionResult("FAILED");
        WorkCompletionActResponse act = service.create(request);
        attachAct(act.getId());

        service.submitForApproval(act.getId());

        assertThatThrownBy(() -> approve(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FAILED");
    }

    @Test
    void approvalConsumesContractAndTreasuryPaymentCompletesWorkLinesBeforePurchaseOrderClose() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse first = service.create(request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400)));
        WorkCompletionActResponse second = service.create(request(fixture, BigDecimal.valueOf(6), BigDecimal.valueOf(600)));
        attachAct(first.getId());
        attachAct(second.getId());

        service.submitForApproval(first.getId());
        approve(first.getId());

        assertThat(itemRepository.findById(fixture.item().getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.SENT.name());
        assertThat(contractRepository.findById(fixture.contract().getId()).orElseThrow().getUsedAmount())
            .isEqualByComparingTo(BigDecimal.valueOf(400));
        assertThatThrownBy(() -> purchaseOrderService.close(fixture.purchaseOrder().getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("COMPLETED");

        service.submitForApproval(second.getId());
        approve(second.getId());

        assertThat(itemRepository.findById(fixture.item().getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.SENT.name());
        assertThat(contractRepository.findById(fixture.contract().getId()).orElseThrow().getUsedAmount())
            .isEqualByComparingTo(BigDecimal.valueOf(1000));

        ensurePayableInvoice(fixture, BigDecimal.valueOf(1000));
        sendWorkActToPayment(first.getId(), "PAY-FIRST-PENDING");
        markWorkActPaid(first.getId(), "PAY-FIRST-PAID");
        assertThat(itemRepository.findById(fixture.item().getId()).orElseThrow().getStatus())
            .isEqualTo("PARTIALLY_COMPLETED");
        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.PARTIALLY_COMPLETED.name());

        sendWorkActToPayment(second.getId(), "PAY-SECOND-PENDING");
        markWorkActPaid(second.getId(), "PAY-SECOND-PAID");
        assertThat(itemRepository.findById(fixture.item().getId()).orElseThrow().getStatus())
            .isEqualTo("COMPLETED");
        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.COMPLETED.name());
        assertThat(purchaseOrderService.close(fixture.purchaseOrder().getId()).getStatus())
            .isEqualTo(PurchaseOrderStatus.CLOSED.name());
    }

    @Test
    void approvedWorkActKeepsPurchaseOrderInPaymentUntilTreasuryConfirmsPayment() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(
            fixture,
            BigDecimal.TEN,
            BigDecimal.valueOf(1000)
        ));
        attachAct(act.getId());

        service.submitForApproval(act.getId());
        approve(act.getId());

        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.SENT.name());

        ensurePayableInvoice(fixture, BigDecimal.valueOf(1000));
        sendWorkActToPayment(act.getId(), "TREASURY-PENDING");

        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.SENT.name());

        markWorkActPaid(act.getId(), "TREASURY-PAID");

        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.COMPLETED.name());
    }

    @Test
    void rejectsWorkActWhoseAmountDoesNotMatchCompletedQuantityTimesUnitPrice() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        assertThatThrownBy(() -> service.create(request(
            fixture, BigDecimal.TEN, BigDecimal.valueOf(500)
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("completed quantity");
    }

    @Test
    void repeatedApprovalCallbackDoesNotConsumeContractTwice() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        attachAct(act.getId());

        service.submitForApproval(act.getId());
        approve(act.getId());
        workCompletionActApprovalAdapter.markApproved(act.getId(), "retry");

        assertThat(contractRepository.findById(fixture.contract().getId()).orElseThrow().getUsedAmount())
            .isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void repeatedApprovalCallbackAfterCloseDoesNotReopenPurchaseOrder() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        attachAct(act.getId());
        service.submitForApproval(act.getId());
        approve(act.getId());
        ensurePayableInvoice(fixture, BigDecimal.valueOf(1000));
        sendWorkActToPayment(act.getId(), "PAY-RETRY-PENDING");
        markWorkActPaid(act.getId(), "PAY-RETRY-PAID");
        purchaseOrderService.close(fixture.purchaseOrder().getId());

        workCompletionActApprovalAdapter.markApproved(act.getId(), "retry");

        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.CLOSED.name());
    }

    @Test
    void rejectionFreesReservedCompletedQuantity() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse rejected = service.create(request(fixture, BigDecimal.valueOf(7), BigDecimal.valueOf(700)));

        service.submitForApproval(rejected.getId());
        reject(rejected.getId());

        assertThat(service.get(rejected.getId()).getStatus()).isEqualTo("REJECTED");
        assertThat(service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000))).getCompletedQuantity())
            .isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void rejectedActCanBeResubmittedForApproval() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400)));

        service.submitForApproval(act.getId());
        reject(act.getId());
        service.submitForApproval(act.getId());

        assertThat(service.get(act.getId()).getStatus()).isEqualTo("APPROVAL_PENDING");
        assertThat(approvalEngineService.currentInstance(WorkCompletionActApprovalAdapter.MODULE_KEY, act.getId())
            .tasks().stream()
            .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
            .count()).isEqualTo(1);
    }

    @Test
    void rejectedActCanBeResubmittedWhenActCompanyDriftsFromPurchaseOrder() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400)));
        service.submitForApproval(act.getId());
        reject(act.getId());
        var stored = workCompletionActRepository.findByIdAndIsDeletedFalse(act.getId()).orElseThrow();
        stored.setCompanyId(UUID.randomUUID());
        workCompletionActRepository.save(stored);
        makeGlobalAdminForCompanyDriftProbe();

        assertThat(workCompletionActApprovalAdapter.companyId(act.getId()))
            .isEqualTo(fixture.purchaseOrder().getCompanyId());
        service.submitForApproval(act.getId());

        assertThat(service.get(act.getId()).getStatus()).isEqualTo("APPROVAL_PENDING");
    }

    @Test
    void companyDriftDoesNotBypassOverCompletionOrTerminalDeleteGuard() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400)));
        attachAct(act.getId());
        service.submitForApproval(act.getId());
        approve(act.getId());
        var stored = workCompletionActRepository.findByIdAndIsDeletedFalse(act.getId()).orElseThrow();
        stored.setCompanyId(UUID.randomUUID());
        workCompletionActRepository.save(stored);
        makeGlobalAdminForCompanyDriftProbe();

        assertThatThrownBy(() -> service.create(request(fixture, BigDecimal.valueOf(7), BigDecimal.valueOf(700))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cumulative completed quantity");
        assertThatThrownBy(() -> service.softDelete(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");

        assertThat(itemRepository.findById(fixture.item().getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.SENT.name());
    }

    @Test
    void approvedActCannotBeDeletedOrRestored() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        attachAct(act.getId());
        service.submitForApproval(act.getId());
        approve(act.getId());

        assertThatThrownBy(() -> service.softDelete(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");

        var stored = workCompletionActRepository.findByIdAndIsDeletedFalse(act.getId()).orElseThrow();
        stored.softDelete();
        workCompletionActRepository.save(stored);

        assertThatThrownBy(() -> service.restore(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be restored");
    }

    @Test
    void completedActCannotBeDeletedAfterPurchaseOrderClose() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        attachAct(act.getId());
        service.submitForApproval(act.getId());
        approve(act.getId());
        ensurePayableInvoice(fixture, BigDecimal.valueOf(1000));
        sendWorkActToPayment(act.getId(), "PAY-CLOSE-PENDING");
        markWorkActPaid(act.getId(), "PAY-CLOSE-PAID");
        purchaseOrderService.close(fixture.purchaseOrder().getId());

        assertThatThrownBy(() -> service.softDelete(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");
    }

    @Test
    void rejectedActCannotBeDeletedAfterClose() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse rejected = service.create(request(fixture, BigDecimal.valueOf(4), BigDecimal.valueOf(400)));
        service.submitForApproval(rejected.getId());
        reject(rejected.getId());
        WorkCompletionActResponse approved = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        attachAct(approved.getId());
        service.submitForApproval(approved.getId());
        approve(approved.getId());
        ensurePayableInvoice(fixture, BigDecimal.valueOf(1000));
        sendWorkActToPayment(approved.getId(), "PAY-REJECTED-PENDING");
        markWorkActPaid(approved.getId(), "PAY-REJECTED-PAID");
        purchaseOrderService.close(fixture.purchaseOrder().getId());

        assertThatThrownBy(() -> service.softDelete(rejected.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");

        assertThat(purchaseOrderRepository.findById(fixture.purchaseOrder().getId()).orElseThrow().getStatus())
            .isEqualTo(PurchaseOrderStatus.CLOSED.name());
    }

    @Test
    void approvedActCanBeSentToPaymentAndMarkedPaid() {
        Fixture fixture = fixture(BigDecimal.TEN, RequestType.SERVICE, ProcurementLineType.WORK);
        WorkCompletionActResponse act = service.create(request(fixture, BigDecimal.TEN, BigDecimal.valueOf(1000)));
        attachAct(act.getId());
        service.submitForApproval(act.getId());
        approve(act.getId());

        ensurePayableInvoice(fixture, BigDecimal.valueOf(1000));
        WorkCompletionActResponse sent = sendWorkActToPayment(act.getId(), "PAY-WORK-1");
        WorkCompletionActResponse paid = markWorkActPaid(act.getId(), "PAY-WORK-2");

        assertThat(sent.getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(sent.getSentToPaymentAt()).isNotNull();
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(paid.getPaymentReference()).isEqualTo("PAY-WORK-2");
    }

    private void attachAct(UUID actId) {
        attachmentService.saveBytes(
            "WORK_COMPLETION_ACT",
            actId,
            "ACT",
            "work-act.pdf",
            "application/pdf",
            "work-act".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private void approve(UUID actId) {
        var instance = approvalEngineService.currentInstance(WorkCompletionActApprovalAdapter.MODULE_KEY, actId);
        var task = instance.tasks().stream()
            .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
            .findFirst()
            .orElseThrow();
        approvalEngineService.approveTask(task.id(), "approved");
    }

    private void reject(UUID actId) {
        var instance = approvalEngineService.currentInstance(WorkCompletionActApprovalAdapter.MODULE_KEY, actId);
        var task = instance.tasks().stream()
            .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
            .findFirst()
            .orElseThrow();
        approvalEngineService.rejectTask(task.id(), "rejected");
    }

    private void makeGlobalAdminForCompanyDriftProbe() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof AbstractAuthenticationToken token) {
            token.setDetails(new com.enterprise.mm.security.AccessScope.RequestScope(true));
        }
    }

    private WorkCompletionActCreateRequest request(Fixture fixture, BigDecimal quantity, BigDecimal amount) {
        WorkCompletionActCreateRequest request = new WorkCompletionActCreateRequest();
        request.setPurchaseOrderId(fixture.purchaseOrder().getId());
        request.setPurchaseOrderItemId(fixture.item().getId());
        request.setContractId(fixture.contract().getId());
        request.setWorkLocation("Tashkent site");
        request.setMilestone("FOUNDATION");
        request.setPeriodStart(LocalDate.of(2026, 1, 1));
        request.setPeriodEnd(LocalDate.of(2026, 1, 31));
        request.setCompletedQuantity(quantity);
        request.setAmount(amount);
        request.setInspectionResult("PASSED");
        request.setAcceptedAt(null);
        request.setAcceptedBy(null);
        request.setCommissionNotes("Commission inspected the work");
        request.setRemarks("Initial inspection");
        return request;
    }

    private void copy(WorkCompletionActCreateRequest source, WorkCompletionActUpdateRequest target) {
        target.setPurchaseOrderId(source.getPurchaseOrderId());
        target.setPurchaseOrderItemId(source.getPurchaseOrderItemId());
        target.setContractId(source.getContractId());
        target.setWorkLocation(source.getWorkLocation());
        target.setMilestone(source.getMilestone());
        target.setPeriodStart(source.getPeriodStart());
        target.setPeriodEnd(source.getPeriodEnd());
        target.setCompletedQuantity(source.getCompletedQuantity());
        target.setAmount(source.getAmount());
        target.setInspectionResult(source.getInspectionResult());
        target.setAcceptedAt(source.getAcceptedAt());
        target.setAcceptedBy(source.getAcceptedBy());
        target.setCommissionNotes(source.getCommissionNotes());
        target.setRemarks(source.getRemarks());
    }

    private void ensurePayableInvoice(Fixture fixture, BigDecimal amount) {
        ContractInvoiceCreateRequest invoiceRequest = new ContractInvoiceCreateRequest();
        invoiceRequest.setContractId(fixture.contract().getId());
        invoiceRequest.setPurchaseOrderId(fixture.purchaseOrder().getId());
        invoiceRequest.setSupplierId(fixture.supplier().getId());
        invoiceRequest.setInvoiceNumber("INV-" + shortId());
        invoiceRequest.setInvoiceDate(LocalDate.now());
        invoiceRequest.setAmount(amount);
        invoiceRequest.setCurrencyCode("UZS");
        contractInvoiceService.issue(contractInvoiceService.create(invoiceRequest).getId());
    }

    private WorkCompletionActResponse sendWorkActToPayment(UUID actId, String paymentReference) {
        return service.sendToPayment(actId, paymentReference);
    }

    private WorkCompletionActResponse markWorkActPaid(UUID actId, String paymentReference) {
        var payment = contractPaymentRepository
            .findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
                com.enterprise.mm.common.TenantContext.currentTenant(),
                ProcurementSourceDocumentType.WORK_COMPLETION_ACT.name(),
                actId
            )
            .stream()
            .findFirst()
            .orElseThrow();
        seedTreasuryAck(payment.getId(), paymentReference);
        return service.markPaid(actId, paymentReference);
    }

    private void seedTreasuryAck(UUID paymentId, String externalReference) {
        UUID companyId = com.enterprise.mm.common.CompanyContext.currentCompany();
        String tenant = com.enterprise.mm.common.TenantContext.currentTenant();
        IntegrationEvent callback = new IntegrationEvent();
        callback.setTenantId(tenant);
        callback.setCompanyId(companyId);
        callback.setCode("TREASURY-CALLBACK-" + shortId());
        callback.setName("Treasury payment callback");
        callback.setDirection(IntegrationEventDirection.INBOUND.name());
        callback.setStatus("PUBLISHED");
        callback.setEventType("TREASURY.PAYMENT_STATUS_PAID");
        callback.setAggregateType("TREASURY");
        callback.setAggregateId(paymentId);
        callback.setExternalId(externalReference);
        callback.setDescription("PAID");
        callback.setPayload("{}");
        callback.setCreatedAt(LocalDateTime.now());
        integrationEventRepository.saveAndFlush(callback);
    }

    private Fixture fixture(BigDecimal quantity, RequestType requestType, ProcurementLineType lineType) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-" + shortId());
        supplier.setName("Test works supplier");
        supplier.setLegalName("Test works supplier LLC");
        supplier = supplierRepository.save(supplier);

        Contract contract = contract(supplier);
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setCode("PO-" + shortId());
        purchaseOrder.setName("Works purchase order");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setRequestType(requestType);
        purchaseOrder.setSupplierId(supplier.getId());
        purchaseOrder.setContractId(contract.getId());
        purchaseOrder.setTotalAmount(BigDecimal.valueOf(1000));
        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-" + shortId());
        item.setName("Works line");
        item.setStatus("OPEN");
        item.setPurchaseOrderId(purchaseOrder.getId());
        item.setLineType(lineType);
        item.setQuantity(quantity);
        item.setUnitPrice(BigDecimal.valueOf(100));
        item = itemRepository.save(item);
        return new Fixture(supplier, contract, purchaseOrder, item);
    }

    private Contract contract(Supplier supplier) {
        Contract contract = new Contract();
        contract.setCode("CON-" + shortId());
        contract.setName("Works contract");
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setCurrencyCode("UZS");
        contract.setLimitAmount(BigDecimal.valueOf(100000));
        return contractRepository.save(contract);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private record Fixture(Supplier supplier, Contract contract, PurchaseOrder purchaseOrder, PurchaseOrderItem item) {
    }
}
