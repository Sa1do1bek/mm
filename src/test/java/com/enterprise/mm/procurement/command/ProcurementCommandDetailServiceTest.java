package com.enterprise.mm.procurement.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.DispatchReadiness;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AvailableActions;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandAction;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.SourcingCoverageSnapshot;
import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.procurement.command.repository.ProcurementWorkAssignmentRepository;
import com.enterprise.mm.procurement.command.service.ProcurementBlockerResolver;
import com.enterprise.mm.procurement.command.service.ProcurementCommandDetailService;
import com.enterprise.mm.procurement.command.service.ProcurementNextActionResolver;
import com.enterprise.mm.procurement.command.service.PurchaseOrderDispatchReadinessService;
import com.enterprise.mm.procurement.command.service.SourcingCoverageReader;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementCommandDetailServiceTest {
    @Mock private PurchaseRequestRepository requestRepository;
    @Mock private RFQRepository rfqRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private RFQPurchaseRequestSourceRepository sourceRepository;
    @Mock private SourcingCoverageReader coverageReader;
    @Mock private PurchaseOrderDispatchReadinessService readinessService;
    @Mock private ProcurementBlockerResolver blockerResolver;
    @Mock private ProcurementNextActionResolver nextActionResolver;
    @Mock private ProcurementWorkAssignmentRepository assignmentRepository;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private ApprovalTaskRepository approvalTaskRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private IntegrationEventRepository integrationEventRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @InjectMocks private ProcurementCommandDetailService service;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-command");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void detailConsolidatesLifecycleOwnershipApprovalsEvidenceAndIntegrationImpact() {
        UUID requestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        PurchaseRequest request = entity(new PurchaseRequest(), requestId, "PR-100", "Pump package", "PO_CREATED");
        request.setRequestedBy("buyer.one");
        request.setRequiredDate(LocalDate.now().plusDays(5));
        RFQ rfq = entity(new RFQ(), UUID.randomUUID(), "RFQ-100", "Pump sourcing", "AWARDED");
        rfq.setPurchaseRequestId(requestId);
        PurchaseOrder order = entity(new PurchaseOrder(), orderId, "PO-100", "Pump order", "APPROVED");
        order.setPurchaseRequestId(requestId);
        order.setSupplierId(supplierId);
        Supplier supplier = entity(new Supplier(), supplierId, "SUP-100", "Industrial Pumps", "ACTIVE");
        ProcurementWorkAssignment assignment = new ProcurementWorkAssignment();
        assignment.setOwnerUsername("buyer.two");
        assignment.setOwnerRole("PROCUREMENT_SPECIALIST");
        assignment.setDueDate(LocalDate.now().plusDays(2));
        assignment.setSeverity("WARNING");
        assignment.setVersion(3L);
        ApprovalInstance approval = entity(new ApprovalInstance(), UUID.randomUUID(), "APR-100", "PO approval", "ACTIVE");
        approval.setModuleKey("PROCUREMENT_PURCHASE_ORDER");
        approval.setEntityId(orderId);
        approval.setApprovalStatus(ApprovalInstanceStatus.PENDING);
        approval.setCurrentStepOrder(2);
        ApprovalTask task = entity(new ApprovalTask(), UUID.randomUUID(), "TASK-100", "Finance approval", "PENDING");
        task.setApprovalInstanceId(approval.getId());
        task.setStepOrder(2);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setAssigneeRole("FINANCE_CONTROLLER");
        Attachment attachment = entity(new Attachment(), UUID.randomUUID(), "ATT-100", "Commercial offer", "ACTIVE");
        attachment.setOriginalFileName("offer.pdf");
        attachment.setAttachmentType("COMMERCIAL_OFFER");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(1200L);
        attachment.setUploadedAt(LocalDateTime.now());
        IntegrationEvent event = entity(new IntegrationEvent(), UUID.randomUUID(), "EVT-100", "FI posting", "FAILED");
        event.setEventType("PURCHASE_ORDER_APPROVED");
        event.setAggregateId(orderId);
        event.setRetryCount(1);
        event.setLastError("FI unavailable");

        when(requestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(rfqRepository.findByTenantIdAndPurchaseRequestIdAndIsDeletedFalse("tenant-command", requestId)).thenReturn(List.of(rfq));
        when(sourceRepository.findByTenantIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse("tenant-command", requestId, "ACTIVE")).thenReturn(List.of());
        when(purchaseOrderRepository.findByTenantIdAndPurchaseRequestIdIsNotNullAndIsDeletedFalse("tenant-command")).thenReturn(List.of(order));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-command", supplierId)).thenReturn(Optional.of(supplier));
        when(coverageReader.read(requestId)).thenReturn(new SourcingCoverageSnapshot(
            com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode.AUTHORITATIVE, List.of(), LocalDateTime.now()));
        when(readinessService.evaluate(orderId)).thenReturn(new DispatchReadiness(true, List.of(), List.of()));
        when(nextActionResolver.resolveActions(any(), anyList())).thenReturn(new AvailableActions(
            new CommandAction("DISPATCH_PURCHASE_ORDER", true, null, java.util.Map.of(), false, "COMMAND", null, null),
            List.of()));
        when(assignmentRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalse("tenant-command", companyId, requestId)).thenReturn(Optional.of(assignment));
        when(approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            "tenant-command", companyId, "PURCHASE_REQUEST", requestId)).thenReturn(Optional.empty());
        when(approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            "tenant-command", companyId, "PROCUREMENT_PURCHASE_ORDER", orderId)).thenReturn(Optional.of(approval));
        when(approvalTaskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder("tenant-command", approval.getId())).thenReturn(List.of(task));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-command", companyId, "PURCHASEREQUEST", requestId)).thenReturn(List.of());
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-command", companyId, "PURCHASEORDER", orderId)).thenReturn(List.of(attachment));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-command", companyId, requestId)).thenReturn(List.of());
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "tenant-command", companyId, orderId)).thenReturn(List.of(event));

        var detail = service.get(requestId);

        assertThat(detail.lifecycle()).extracting(item -> item.documentType()).containsExactly("PURCHASE_REQUEST", "RFQ", "PURCHASE_ORDER");
        assertThat(detail.assignment().ownerUsername()).isEqualTo("buyer.two");
        assertThat(detail.approvals()).singleElement().satisfies(item -> {
            assertThat(item.documentCode()).isEqualTo("PO-100");
            assertThat(item.tasks()).singleElement().satisfies(approvalTask ->
                assertThat(approvalTask.assigneeRole()).isEqualTo("FINANCE_CONTROLLER"));
        });
        assertThat(detail.evidence()).singleElement().satisfies(item -> assertThat(item.displayName()).isEqualTo("offer.pdf"));
        assertThat(detail.integrationImpact()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("FAILED");
            assertThat(item.businessDocumentCode()).isEqualTo("PO-100");
        });
        assertThat(detail.purchaseOrders()).singleElement().satisfies(item ->
            assertThat(item.supplier().displayName()).isEqualTo("Industrial Pumps"));
        assertThat(detail.availableActions().primary()).satisfies(action -> {
            assertThat(action.documentType()).isEqualTo("PURCHASE_ORDER");
            assertThat(action.documentId()).isEqualTo(orderId);
        });
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T entity(
        T entity, UUID id, String code, String name, String status
    ) {
        entity.setId(id);
        entity.setTenantId("tenant-command");
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus(status);
        return entity;
    }
}
