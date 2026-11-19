package com.enterprise.mm.procurement.command;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.SourcingCoverageSnapshot;
import com.enterprise.mm.procurement.command.service.CompatibilitySourcingCoverageReader;
import com.enterprise.mm.procurement.command.service.ProcurementBlockerResolver;
import com.enterprise.mm.procurement.command.service.ProcurementCommandQueryService;
import com.enterprise.mm.procurement.command.service.ProcurementNextActionResolver;
import com.enterprise.mm.procurement.command.service.ProcurementStageResolver;
import com.enterprise.mm.procurement.command.repository.ProcurementCommandQueryRepository;
import com.enterprise.mm.procurement.command.repository.ProcurementWorkAssignmentRepository;
import com.enterprise.mm.procurement.command.entity.ProcurementWorkAssignment;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcurementCommandQueryServiceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void coverageReportsActiveRfqAllocationAuthoritatively() {
        UUID purchaseRequestId = UUID.randomUUID();
        UUID purchaseRequestItemId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID rfqItemId = UUID.randomUUID();
        PurchaseRequestItemRepository repository = mock(PurchaseRequestItemRepository.class);
        RFQPurchaseRequestSourceRepository sourceRepository = mock(RFQPurchaseRequestSourceRepository.class);
        RFQRepository rfqRepository = mock(RFQRepository.class);
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setId(purchaseRequestItemId);
        item.setCompanyId(companyId);
        item.setMaterialId(UUID.randomUUID());
        item.setName("Industrial pump");
        item.setUnit("pcs");
        item.setQuantity(new BigDecimal("12.0000"));
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCompanyId(companyId);
        source.setPurchaseRequestId(purchaseRequestId);
        source.setPurchaseRequestItemId(purchaseRequestItemId);
        source.setRfqId(rfqId);
        source.setRfqItemId(rfqItemId);
        source.setAllocatedQuantity(new BigDecimal("12.0000"));
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setCompanyId(companyId);
        rfq.setStatus("OPEN");
        when(repository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse("tenant-a", purchaseRequestId))
            .thenReturn(List.of(item));
        when(sourceRepository.findByTenantIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
            "tenant-a", purchaseRequestId, RfqSourceAllocationStatus.ACTIVE.name()))
            .thenReturn(List.of(source));
        when(rfqRepository.findByTenantIdAndIdInAndIsDeletedFalse("tenant-a", List.of(rfqId)))
            .thenReturn(List.of(rfq));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        CompatibilitySourcingCoverageReader reader = new CompatibilitySourcingCoverageReader(
            repository, sourceRepository, rfqRepository);

        SourcingCoverageSnapshot snapshot = reader.read(purchaseRequestId);

        assertThat(snapshot.allocationMode()).isEqualTo(ProcurementAllocationMode.AUTHORITATIVE);
        assertThat(snapshot.lines()).allSatisfy(line -> {
            assertThat(line.requestedQuantity()).isNotNull();
            assertThat(line.allocatedQuantity()).isEqualByComparingTo("12.0000");
            assertThat(line.orderedQuantity()).isZero();
            assertThat(line.remainingQuantity()).isZero();
            assertThat(line.coveragePercent()).isEqualByComparingTo("100.00");
            assertThat(line.branches()).singleElement().satisfies(branch -> {
                assertThat(branch.documentType()).isEqualTo("RFQ");
                assertThat(branch.documentId()).isEqualTo(rfqId);
                assertThat(branch.documentLineId()).isEqualTo(rfqItemId);
                assertThat(branch.allocatedQuantity()).isEqualByComparingTo("12.0000");
                assertThat(branch.status()).isEqualTo("OPEN");
            });
        });
        verify(repository).findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse("tenant-a", purchaseRequestId);
    }

    @Test
    void caseRowUsesRelatedDocumentsForStageCountsAndNextAction() {
        UUID purchaseRequestId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        PurchaseRequest request = new PurchaseRequest();
        request.setId(purchaseRequestId);
        request.setCompanyId(companyId);
        request.setStatus("PO_CREATED");
        PurchaseOrder order = new PurchaseOrder();
        order.setCompanyId(companyId);
        order.setPurchaseRequestId(purchaseRequestId);
        order.setStatus("APPROVED");
        RFQ rfq = new RFQ();
        rfq.setCompanyId(companyId);
        rfq.setPurchaseRequestId(purchaseRequestId);
        rfq.setStatus("EVALUATED");
        ProcurementCommandQueryRepository queryRepository = mock(ProcurementCommandQueryRepository.class);
        RFQRepository rfqRepository = mock(RFQRepository.class);
        PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
        PageRequest page = PageRequest.of(0, 20);
        when(queryRepository.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(page)))
            .thenReturn(new PageImpl<>(List.of(request), page, 1));
        when(rfqRepository.findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of(rfq));
        when(orderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of(order));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        ProcurementCommandQueryService service = new ProcurementCommandQueryService(
            queryRepository, rfqRepository, orderRepository,
            new ProcurementStageResolver(), new ProcurementBlockerResolver(), new ProcurementNextActionResolver());
        ProcurementWorkAssignmentRepository assignmentRepository = mock(ProcurementWorkAssignmentRepository.class);
        ProcurementWorkAssignment assignment = new ProcurementWorkAssignment();
        UUID ownerId = UUID.randomUUID();
        LocalDate dueDate = LocalDate.now().plusDays(2);
        assignment.setPurchaseRequestId(purchaseRequestId);
        assignment.setOwnerUserId(ownerId);
        assignment.setOwnerUsername("buyer.one");
        assignment.setDueDate(dueDate);
        assignment.setSeverity("WARNING");
        when(assignmentRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            "tenant-a", companyId, List.of(purchaseRequestId))).thenReturn(List.of(assignment));
        service.setAssignmentRepository(assignmentRepository);

        var row = service.search(ProcurementCommandSearchRequest.builder().build(), page).getContent().getFirst();

        assertThat(row.stage()).isEqualTo(ProcurementCommandStage.QUOTATION_EVALUATION);
        assertThat(row.nextActionKey()).isEqualTo("EVALUATE_QUOTATIONS");
        assertThat(row.rfqCount()).isEqualTo(1);
        assertThat(row.purchaseOrderCount()).isEqualTo(1);
        assertThat(row.ownerUserId()).isEqualTo(ownerId);
        assertThat(row.ownerUsername()).isEqualTo("buyer.one");
        assertThat(row.dueDate()).isEqualTo(dueDate);
        assertThat(row.severity()).isEqualTo(com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity.WARNING);
    }

    @Test
    void cancelledPurchaseRequestDominatesRelatedDocumentStages() {
        UUID purchaseRequestId = UUID.randomUUID();
        PurchaseRequest request = new PurchaseRequest();
        request.setId(purchaseRequestId);
        request.setStatus("CANCELLED");
        PurchaseOrder order = new PurchaseOrder();
        order.setStatus("APPROVED");
        ProcurementCommandQueryRepository queryRepository = mock(ProcurementCommandQueryRepository.class);
        RFQRepository rfqRepository = mock(RFQRepository.class);
        PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
        PageRequest page = PageRequest.of(0, 20);
        when(queryRepository.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(page)))
            .thenReturn(new PageImpl<>(List.of(request), page, 1));
        when(rfqRepository.findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of());
        when(orderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of(order));
        TenantContext.setTenant("tenant-a");
        ProcurementCommandQueryService service = new ProcurementCommandQueryService(
            queryRepository, rfqRepository, orderRepository,
            new ProcurementStageResolver(), new ProcurementBlockerResolver(), new ProcurementNextActionResolver());

        var row = service.search(ProcurementCommandSearchRequest.builder().build(), page).getContent().getFirst();

        assertThat(row.stage()).isEqualTo(ProcurementCommandStage.CANCELLED);
        assertThat(row.blockerCode()).isNull();
        assertThat(row.nextActionKey()).isNull();
    }

    @Test
    void earliestActiveBranchControlsBothStageAndAction() {
        UUID purchaseRequestId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        PurchaseRequest request = new PurchaseRequest();
        request.setId(purchaseRequestId);
        request.setStatus("PO_CREATED");
        PurchaseOrder draft = new PurchaseOrder();
        draft.setCompanyId(companyId);
        draft.setPurchaseRequestId(purchaseRequestId);
        draft.setStatus("DRAFT");
        PurchaseOrder approved = new PurchaseOrder();
        approved.setCompanyId(companyId);
        approved.setPurchaseRequestId(purchaseRequestId);
        approved.setStatus("APPROVED");
        RFQ openRfq = new RFQ();
        openRfq.setCompanyId(companyId);
        openRfq.setPurchaseRequestId(purchaseRequestId);
        PurchaseRequest rfqRequest = new PurchaseRequest();
        rfqRequest.setId(purchaseRequestId);
        openRfq.setPurchaseRequest(rfqRequest);
        openRfq.setStatus("OPEN");
        ProcurementCommandQueryRepository queryRepository = mock(ProcurementCommandQueryRepository.class);
        RFQRepository rfqRepository = mock(RFQRepository.class);
        PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
        PageRequest page = PageRequest.of(0, 20);
        when(queryRepository.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(page)))
            .thenReturn(new PageImpl<>(List.of(request), page, 1));
        when(rfqRepository.findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of(openRfq));
        when(orderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of(draft, approved));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        ProcurementCommandQueryService service = new ProcurementCommandQueryService(
            queryRepository, rfqRepository, orderRepository,
            new ProcurementStageResolver(), new ProcurementBlockerResolver(), new ProcurementNextActionResolver());

        var row = service.search(ProcurementCommandSearchRequest.builder().build(), page).getContent().getFirst();

        assertThat(row.stage()).isEqualTo(ProcurementCommandStage.RFQ_IN_PROGRESS);
        assertThat(row.nextActionKey()).isEqualTo("MONITOR_RFQ");
    }

    @Test
    void blockedPurchaseOrderProducesMatchingBlockerAndRecoveryAction() {
        UUID purchaseRequestId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        PurchaseRequest request = new PurchaseRequest();
        request.setId(purchaseRequestId);
        request.setStatus("PO_CREATED");
        PurchaseOrder blocked = new PurchaseOrder();
        blocked.setCompanyId(companyId);
        blocked.setPurchaseRequestId(purchaseRequestId);
        blocked.setStatus("BLOCKED");
        ProcurementCommandQueryRepository queryRepository = mock(ProcurementCommandQueryRepository.class);
        RFQRepository rfqRepository = mock(RFQRepository.class);
        PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
        PageRequest page = PageRequest.of(0, 20);
        when(queryRepository.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(page)))
            .thenReturn(new PageImpl<>(List.of(request), page, 1));
        when(rfqRepository.findByTenantIdAndPurchaseRequest_IdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of());
        when(orderRepository.findByTenantIdAndPurchaseRequestIdInAndIsDeletedFalse("tenant-a", List.of(purchaseRequestId)))
            .thenReturn(List.of(blocked));
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        ProcurementCommandQueryService service = new ProcurementCommandQueryService(
            queryRepository, rfqRepository, orderRepository,
            new ProcurementStageResolver(), new ProcurementBlockerResolver(), new ProcurementNextActionResolver());

        var row = service.search(ProcurementCommandSearchRequest.builder().build(), page).getContent().getFirst();

        assertThat(row.stage()).isEqualTo(ProcurementCommandStage.BLOCKED);
        assertThat(row.blockerCode()).isEqualTo("PROCUREMENT_PO_BLOCKED");
        assertThat(row.nextActionKey()).isEqualTo("OPEN_PURCHASE_ORDER");
    }
}
