package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ApprovalFlowRepository;
import com.enterprise.mm.repository.ApprovalFlowStepRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.ServiceAcceptanceActService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "service-act-approver", roles = {"ADMIN", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER"})
class ServiceAcceptanceActApprovalWorkflowIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private ServiceRequestRepository serviceRequestRepository;
    @Autowired private ServiceOrderRepository serviceOrderRepository;
    @Autowired private ServiceExecutionRepository serviceExecutionRepository;
    @Autowired private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Autowired private ApprovalFlowRepository approvalFlowRepository;
    @Autowired private ApprovalFlowStepRepository approvalFlowStepRepository;
    @Autowired private ServiceAcceptanceActService serviceAcceptanceActService;
    @Autowired private ApprovalEngineService approvalEngineService;
    @Autowired private AttachmentService attachmentService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        ensureApprovalFlow(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, "Service acceptance act technical approval", "PROCUREMENT_MANAGER");
        ensureApprovalFlow(ServiceFinanceApprovalAdapter.MODULE_KEY, "Service finance approval", "FINANCE_CONTROLLER");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void serviceAcceptanceActMovesThroughTechnicalDocumentAndFinanceApproval() throws Exception {
        ServiceOrder order = serviceOrderFixture();

        var draft = serviceAcceptanceActService.accept(
            order.getId(),
            "Commission chair",
            "Supplier service completion accepted",
            new BigDecimal("300000.00"),
            "FINAL",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 1)
        );

        ServiceAcceptanceAct storedDraft = serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(draft.getId()).orElseThrow();
        assertThat(storedDraft.getStatus()).isEqualTo("DRAFT");
        assertThat(storedDraft.getPurchaseOrderId()).isEqualTo(order.getPurchaseOrderId());
        assertThat(storedDraft.getServiceOrderId()).isEqualTo(order.getId());
        assertThat(storedDraft.getAmount()).isEqualByComparingTo("300000.00");

        mockMvc.perform(post("/api/service-acceptance-workspace/acts/{actId}/submit", draft.getId())
                .with(user("service-act-approver").roles("ADMIN", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "legacy-submit-" + shortId())
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedVersion", storedDraft.getVersion(),
                    "reason", "Legacy route must stay unused"
                ))))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/workspaces/services-acceptance/acts/{actId}/submit", draft.getId())
                .with(user("service-act-approver").roles("ADMIN", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "submit-service-act-" + shortId())
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedVersion", storedDraft.getVersion(),
                    "reason", "Ready for technical review"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("TECHNICAL_REVIEW"));

        assertThat(pendingTaskCount(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, draft.getId())).isEqualTo(1);
        assertThatThrownBy(() -> approvePendingTask(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, draft.getId(), "technical approved"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ACT attachment is required");

        attachAct(draft.getId());
        approvePendingTask(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, draft.getId(), "technical approved");
        ServiceAcceptanceAct documentReview = serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(draft.getId()).orElseThrow();
        assertThat(documentReview.getStatus()).isEqualTo("DOCUMENT_REVIEW");

        mockMvc.perform(post("/api/workspaces/services-acceptance/acts/{actId}/review", draft.getId())
                .with(user("service-act-approver").roles("ADMIN", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "review-service-act-" + shortId())
                .content(objectMapper.writeValueAsString(Map.of(
                    "expectedVersion", documentReview.getVersion(),
                    "decision", "APPROVE",
                    "reason", "Document reviewed and accepted"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("FINANCE_REVIEW"));

        assertThat(pendingTaskCount(ServiceFinanceApprovalAdapter.MODULE_KEY, draft.getId())).isEqualTo(1);
        approvePendingTask(ServiceFinanceApprovalAdapter.MODULE_KEY, draft.getId(), "finance approved");

        assertThat(serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(draft.getId()).orElseThrow().getStatus())
            .isEqualTo("APPROVED");
    }

    private ServiceOrder serviceOrderFixture() {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-SAA-" + shortId());
        supplier.setName("Service Supplier");
        supplier.setStatus("ACTIVE");
        supplier = supplierRepository.saveAndFlush(supplier);

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setCode("PO-SAA-" + shortId());
        purchaseOrder.setName("Service purchase order");
        purchaseOrder.setStatus("SENT");
        purchaseOrder.setSupplierId(supplier.getId());
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrder.setTotalAmount(new BigDecimal("300000.00"));
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder = purchaseOrderRepository.saveAndFlush(purchaseOrder);

        ServiceRequest request = new ServiceRequest();
        request.setCode("SR-SAA-" + shortId());
        request.setName("Service request");
        request.setTitle("Service request");
        request.setStatus("WAITING_ACCEPTANCE");
        request.setSupplierId(supplier.getId());
        request.setPurchaseOrderId(purchaseOrder.getId());
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(new BigDecimal("300000.00"));
        request.setAcceptedAmount(BigDecimal.ZERO);
        request.setRejectedAmount(BigDecimal.ZERO);
        request.setCurrencyCode("UZS");
        request = serviceRequestRepository.saveAndFlush(request);

        ServiceOrder order = new ServiceOrder();
        order.setCode("SO-SAA-" + shortId());
        order.setName("Service order");
        order.setStatus("ACTIVE");
        order.setSupplierId(supplier.getId());
        order.setServiceRequestId(request.getId());
        order.setPurchaseOrderId(purchaseOrder.getId());
        order.setServiceType("CONSULTING");
        order.setTotalAmount(new BigDecimal("300000.00"));
        order.setCurrencyCode("UZS");
        order = serviceOrderRepository.saveAndFlush(order);

        ServiceExecution execution = new ServiceExecution();
        execution.setCode("SE-SAA-" + shortId());
        execution.setName("Accepted service execution");
        execution.setStatus(ServiceExecutionStatus.ACCEPTED.name());
        execution.setServiceRequestId(request.getId());
        execution.setServiceOrderId(order.getId());
        execution.setProgressPercent(new BigDecimal("100.00"));
        serviceExecutionRepository.saveAndFlush(execution);
        return order;
    }

    private void ensureApprovalFlow(String moduleKey, String name, String approverRole) {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setCode("FLOW-" + moduleKey + "-" + shortId());
        flow.setName(name);
        flow.setStatus("ACTIVE");
        flow.setModuleKey(moduleKey);
        flow.setActive(true);
        flow = approvalFlowRepository.saveAndFlush(flow);

        ApprovalFlowStep step = new ApprovalFlowStep();
        step.setApprovalFlowId(flow.getId());
        step.setCode("STEP-" + moduleKey + "-" + shortId());
        step.setName(name + " step");
        step.setStatus("ACTIVE");
        step.setStepOrder(1);
        step.setAssigneeType(ApprovalAssigneeType.ROLE);
        step.setAssigneeRole(approverRole);
        step.setCanReturn(true);
        approvalFlowStepRepository.saveAndFlush(step);
    }

    private long pendingTaskCount(String moduleKey, UUID actId) {
        return approvalEngineService.currentInstance(moduleKey, actId).tasks().stream()
            .filter(task -> task.taskStatus() == ApprovalTaskStatus.PENDING)
            .count();
    }

    private void approvePendingTask(String moduleKey, UUID actId, String comment) {
        authenticateApprover();
        var task = approvalEngineService.currentInstance(moduleKey, actId).tasks().stream()
            .filter(candidate -> candidate.taskStatus() == ApprovalTaskStatus.PENDING)
            .findFirst()
            .orElseThrow();
        approvalEngineService.approveTask(task.id(), comment);
    }

    private void authenticateApprover() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "service-act-approver",
                "n/a",
                List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"),
                    new SimpleGrantedAuthority("ROLE_FINANCE_CONTROLLER")
                )
            )
        );
    }

    private void attachAct(UUID actId) {
        attachmentService.saveBytes(
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            actId,
            "ACT",
            "service-act.pdf",
            "application/pdf",
            "service-act".getBytes(StandardCharsets.UTF_8)
        );
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }    @Test
    void serviceAcceptanceActNamingInheritsBaseBusinessName() throws Exception {
        ServiceOrder orderEn = serviceOrderFixture();
        orderEn.setName("Purchase Order - Information Systems Modernization Plan");
        serviceOrderRepository.saveAndFlush(orderEn);

        mockMvc.perform(post("/api/service-orders/{id}/accept", orderEn.getId())
                .header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Service Acceptance Act - Information Systems Modernization Plan"));

        ServiceOrder orderRu = serviceOrderFixture();
        orderRu.setName("Purchase Order - Information Systems Modernization Plan");
        serviceOrderRepository.saveAndFlush(orderRu);

        mockMvc.perform(post("/api/service-orders/{id}/accept", orderRu.getId())
                .header("Accept-Language", "ru")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Акт приемки услуг - Information Systems Modernization Plan"));

        ServiceOrder orderUz = serviceOrderFixture();
        orderUz.setName("Purchase Order - Information Systems Modernization Plan");
        serviceOrderRepository.saveAndFlush(orderUz);

        mockMvc.perform(post("/api/service-orders/{id}/accept", orderUz.getId())
                .header("Accept-Language", "uz")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Xizmat qabul qilish dalolatnomasi - Information Systems Modernization Plan"));
    }
}
