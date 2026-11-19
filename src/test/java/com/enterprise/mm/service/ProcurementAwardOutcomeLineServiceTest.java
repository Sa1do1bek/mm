package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementAwardOutcomeLine;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeLineRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
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
class ProcurementAwardOutcomeLineServiceTest {
    private static final String TENANT_ID = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;
    private static final UUID OUTCOME_ID = UUID.randomUUID();
    private static final UUID PROCEDURE_ID = UUID.randomUUID();
    private static final UUID PURCHASE_REQUEST_ID = UUID.randomUUID();
    private static final UUID PURCHASE_REQUEST_ITEM_ID = UUID.randomUUID();
    private static final UUID PURCHASE_ORDER_ID = UUID.randomUUID();
    private static final UUID PURCHASE_ORDER_ITEM_ID = UUID.randomUUID();
    private static final UUID CONTRACT_ID = UUID.randomUUID();
    private static final UUID CONTRACT_SPEC_ID = UUID.randomUUID();

    @Mock private ProcurementAwardOutcomeLineRepository lineRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    @Mock private ContractSpecificationRepository contractSpecificationRepository;
    @Mock private RFQPurchaseRequestSourceRepository rfqSourceRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @InjectMocks private ProcurementAwardOutcomeLineService service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void poBackedOutcomeLinesMarkPurchaseRequestWhenEveryLineIsCovered() {
        List<ProcurementAwardOutcomeLine> savedLines = new ArrayList<>();
        PurchaseRequest request = purchaseRequest(PurchaseRequestStatus.FULLY_SENT_TO_TENDER);
        PurchaseRequestItem requestItem = purchaseRequestItem(BigDecimal.TEN);
        stubLineSave(savedLines);
        stubPurchaseRequestCoverage(request, requestItem, savedLines);
        PurchaseOrderSourceAllocation allocation = allocation(BigDecimal.TEN);
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TENANT_ID, PURCHASE_ORDER_ID)).thenReturn(List.of(purchaseOrderItem()));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            eq(TENANT_ID), eq(COMPANY_ID), anyList())).thenReturn(List.of(allocation));
        when(purchaseRequestItemRepository.findByIdAndIsDeletedFalse(PURCHASE_REQUEST_ITEM_ID))
            .thenReturn(Optional.of(requestItem));

        service.recordLines(poOutcome());

        assertThat(savedLines).singleElement().satisfies(line -> {
            assertThat(line.getPurchaseOrderId()).isEqualTo(PURCHASE_ORDER_ID);
            assertThat(line.getPurchaseOrderItemId()).isEqualTo(PURCHASE_ORDER_ITEM_ID);
            assertThat(line.getPurchaseRequestItemId()).isEqualTo(PURCHASE_REQUEST_ITEM_ID);
            assertThat(line.getAwardedQuantity()).isEqualByComparingTo("10");
            assertThat(line.getAwardedAmount()).isEqualByComparingTo("120.00");
        });
        assertThat(request.getStatus()).isEqualTo(PurchaseRequestStatus.PO_CREATED.name());
        verify(purchaseRequestRepository).save(request);
    }

    @Test
    void partialPoBackedOutcomeDoesNotMarkPurchaseRequestPoCreated() {
        List<ProcurementAwardOutcomeLine> savedLines = new ArrayList<>();
        PurchaseRequest request = purchaseRequest(PurchaseRequestStatus.FULLY_SENT_TO_TENDER);
        PurchaseRequestItem requestItem = purchaseRequestItem(BigDecimal.TEN);
        stubLineSave(savedLines);
        stubPurchaseRequestCoverage(request, requestItem, savedLines);
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TENANT_ID, PURCHASE_ORDER_ID)).thenReturn(List.of(purchaseOrderItem()));
        when(sourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            eq(TENANT_ID), eq(COMPANY_ID), anyList())).thenReturn(List.of(allocation(BigDecimal.valueOf(4))));
        when(purchaseRequestItemRepository.findByIdAndIsDeletedFalse(PURCHASE_REQUEST_ITEM_ID))
            .thenReturn(Optional.of(requestItem));

        service.recordLines(poOutcome());

        assertThat(savedLines).singleElement()
            .satisfies(line -> assertThat(line.getAwardedQuantity()).isEqualByComparingTo("4"));
        assertThat(request.getStatus()).isEqualTo(PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name());
        verify(purchaseRequestRepository, never()).save(any());
    }

    @Test
    void contractOnlyOutcomeRecordsLineButDoesNotMarkPurchaseRequestPoCreated() {
        List<ProcurementAwardOutcomeLine> savedLines = new ArrayList<>();
        PurchaseRequest request = purchaseRequest(PurchaseRequestStatus.FULLY_SENT_TO_TENDER);
        PurchaseRequestItem requestItem = purchaseRequestItem(BigDecimal.TEN);
        stubLineSave(savedLines);
        stubPurchaseRequestCoverage(request, requestItem, savedLines);
        when(contractSpecificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse(TENANT_ID, CONTRACT_ID))
            .thenReturn(List.of(contractSpecification()));
        when(purchaseRequestItemRepository.findByIdAndIsDeletedFalse(PURCHASE_REQUEST_ITEM_ID))
            .thenReturn(Optional.of(requestItem));

        service.recordLines(contractOutcome());

        assertThat(savedLines).singleElement().satisfies(line -> {
            assertThat(line.getContractId()).isEqualTo(CONTRACT_ID);
            assertThat(line.getContractSpecificationId()).isEqualTo(CONTRACT_SPEC_ID);
            assertThat(line.getPurchaseOrderId()).isNull();
            assertThat(line.getAwardedQuantity()).isEqualByComparingTo("10");
        });
        assertThat(request.getStatus()).isEqualTo(PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name());
        verify(purchaseRequestRepository, never()).save(any());
    }

    private void stubLineSave(List<ProcurementAwardOutcomeLine> savedLines) {
        when(lineRepository
            .findByTenantIdAndCompanyIdAndProcurementAwardOutcomeIdAndIdempotencyKeyAndIsDeletedFalse(
                eq(TENANT_ID), eq(COMPANY_ID), eq(OUTCOME_ID), anyString()))
            .thenReturn(Optional.empty());
        when(lineRepository.save(any(ProcurementAwardOutcomeLine.class))).thenAnswer(invocation -> {
            ProcurementAwardOutcomeLine line = invocation.getArgument(0);
            line.setId(UUID.randomUUID());
            savedLines.add(line);
            return line;
        });
    }

    private void stubPurchaseRequestCoverage(
        PurchaseRequest request,
        PurchaseRequestItem item,
        List<ProcurementAwardOutcomeLine> savedLines
    ) {
        when(purchaseRequestRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of(request));
        when(purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            eq(TENANT_ID), eq(COMPANY_ID), anyCollection())).thenReturn(List.of(item));
        when(lineRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            eq(TENANT_ID), eq(COMPANY_ID), anyCollection())).thenAnswer(invocation -> List.copyOf(savedLines));
    }

    private ProcurementAwardOutcome poOutcome() {
        ProcurementAwardOutcome outcome = outcome();
        outcome.setSourceType(ProcurementAwardSourceType.MANAGED_AUCTION);
        outcome.setPurchaseOrderId(PURCHASE_ORDER_ID);
        return outcome;
    }

    private ProcurementAwardOutcome contractOutcome() {
        ProcurementAwardOutcome outcome = outcome();
        outcome.setSourceType(ProcurementAwardSourceType.RFQ_CONTRACT);
        outcome.setContractId(CONTRACT_ID);
        return outcome;
    }

    private ProcurementAwardOutcome outcome() {
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setId(OUTCOME_ID);
        outcome.setTenantId(TENANT_ID);
        outcome.setCompanyId(COMPANY_ID);
        outcome.setCode("PAO-1");
        outcome.setName("Award outcome");
        outcome.setStatus("FINALIZED");
        outcome.setProcurementProcedureId(PROCEDURE_ID);
        outcome.setCurrencyCode("UZS");
        return outcome;
    }

    private PurchaseOrderItem purchaseOrderItem() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(PURCHASE_ORDER_ITEM_ID);
        item.setTenantId(TENANT_ID);
        item.setCompanyId(COMPANY_ID);
        item.setCode("POI-1");
        item.setName("PO line");
        item.setStatus("ACTIVE");
        item.setPurchaseOrderId(PURCHASE_ORDER_ID);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.valueOf(12));
        return item;
    }

    private PurchaseOrderSourceAllocation allocation(BigDecimal quantity) {
        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setId(UUID.randomUUID());
        allocation.setTenantId(TENANT_ID);
        allocation.setCompanyId(COMPANY_ID);
        allocation.setCode("POSA-1");
        allocation.setName("PO source");
        allocation.setStatus("ACTIVE");
        allocation.setPurchaseOrderItemId(PURCHASE_ORDER_ITEM_ID);
        allocation.setPurchaseRequestItemId(PURCHASE_REQUEST_ITEM_ID);
        allocation.setOrderedQuantity(quantity);
        return allocation;
    }

    private ContractSpecification contractSpecification() {
        ContractSpecification specification = new ContractSpecification();
        specification.setId(CONTRACT_SPEC_ID);
        specification.setTenantId(TENANT_ID);
        specification.setCompanyId(COMPANY_ID);
        specification.setCode("CS-1");
        specification.setName("Contract spec");
        specification.setStatus("ACTIVE");
        specification.setContractId(CONTRACT_ID);
        specification.setSourcePurchaseRequestItemId(PURCHASE_REQUEST_ITEM_ID);
        specification.setQuantityLimit(BigDecimal.TEN);
        specification.setPrice(BigDecimal.valueOf(12));
        return specification;
    }

    private PurchaseRequest purchaseRequest(PurchaseRequestStatus status) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(PURCHASE_REQUEST_ID);
        request.setTenantId(TENANT_ID);
        request.setCompanyId(COMPANY_ID);
        request.setCode("PR-1");
        request.setName("Purchase request");
        request.setStatus(status.name());
        return request;
    }

    private PurchaseRequestItem purchaseRequestItem(BigDecimal quantity) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setId(PURCHASE_REQUEST_ITEM_ID);
        item.setTenantId(TENANT_ID);
        item.setCompanyId(COMPANY_ID);
        item.setCode("PRI-1");
        item.setName("Purchase request line");
        item.setStatus("ACTIVE");
        item.setPurchaseRequestId(PURCHASE_REQUEST_ID);
        item.setQuantity(quantity);
        return item;
    }
}
