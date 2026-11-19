package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.SupplierQuotationMapper;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.payload.SupplierQuotationUpdateRequest;
import com.enterprise.mm.payload.SupplierQuotationResponse;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierQuotationServiceTest {
    @Mock private SupplierQuotationRepository repository;
    @Mock private SupplierQuotationMapper mapper;
    @Mock private AuditService auditService;
    @Mock private SupplierQuotationItemRepository quotationItemRepository;
    @Mock private SupplierQuotationItemService quotationItemService;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private TenderProcessService tenderProcessService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private MaterialRepository materialRepository;
    @Mock private ProcurementAwardFinalizationService awardFinalizationService;
    @Mock private PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void beforeCreateRejectsAmountsSubmittedAsScores() {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setTechnicalScore(BigDecimal.valueOf(300_000));

        assertThatThrownBy(() -> service().beforeCreate(quotation, null))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasMessage("technicalScore must be between 0 and 100");
    }

    @Test
    void beforeCreateReplacesSubmittedQuotationNameWithParentRfqBusinessName() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setName("RFQ - Chemical Procurement for Production");
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setRfqId(rfqId);
        quotation.setName("Supplier-specific quotation");

        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));

        service().beforeCreate(quotation, null);

        assertThat(LocalizedText.translate(quotation.getName(), Locale.ENGLISH))
            .isEqualTo("Supplier Quotation - Chemical Procurement for Production");
    }

    @Test
    void updatePreservesWorkflowControlledQuotationFields() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        LocalDateTime submittedAt = LocalDateTime.now().minusDays(1);
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(id);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setStatus("SUBMITTED");
        quotation.setSelected(false);
        quotation.setSubmittedAt(submittedAt);
        quotation.setSelectionReason("original reason");
        SupplierQuotationUpdateRequest update = new SupplierQuotationUpdateRequest();
        update.setName("Updated quotation");
        update.setStatus("SELECTED");
        update.setSelected(true);
        update.setSubmittedAt(LocalDateTime.now());
        update.setSelectionReason("client override");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(quotation));
        org.mockito.Mockito.doAnswer(invocation -> {
            quotation.setName(update.getName());
            quotation.setStatus(update.getStatus());
            quotation.setSelected(update.isSelected());
            quotation.setSubmittedAt(update.getSubmittedAt());
            quotation.setSelectionReason(update.getSelectionReason());
            return null;
        }).when(mapper).updateEntity(quotation, update);
        when(repository.save(quotation)).thenReturn(quotation);

        service().update(id, update);

        assertThat(quotation.getName()).isEqualTo("Updated quotation");
        assertThat(quotation.getStatus()).isEqualTo("SUBMITTED");
        assertThat(quotation.isSelected()).isFalse();
        assertThat(quotation.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(quotation.getSelectionReason()).isEqualTo("original reason");
    }

    @Test
    void updateRejectsSelectedQuotation() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(id);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setStatus("SELECTED");
        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(quotation));

        assertThatThrownBy(() -> service().update(id, new SupplierQuotationUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Selected or rejected quotation cannot be edited");
    }

    @Test
    void selectAndCreatePoBlocksOutsideApprovedDemandCycleBeforeMutation() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(quotationId);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfqId);
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setPurchaseRequestId(purchaseRequestId);
        when(repository.findByTenantIdAndIdAndIsDeletedFalse("default", quotationId))
            .thenReturn(Optional.of(quotation));
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        org.mockito.Mockito.doThrow(new BusinessException(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON))
            .when(purchaseOrderDemandCyclePolicy)
            .requireApprovedCycleForPurchaseRequest(purchaseRequestId);

        assertThatThrownBy(() -> service().selectAndCreatePo(quotationId, "best"))
            .isInstanceOf(BusinessException.class)
            .hasMessage(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON);

        org.mockito.Mockito.verify(purchaseOrderRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void selectAndCreatePoPreservesServiceRequestType() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID rfqItemId = UUID.randomUUID();

        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(purchaseRequestId);
        purchaseRequest.setRequestType(RequestType.SERVICE);

        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setPurchaseRequestId(purchaseRequestId);
        rfq.setPurchaseRequest(purchaseRequest);
        rfq.setTenderProcedureType("SINGLE_SUPPLIER");

        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId("default");
        supplier.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        supplier.setStatus("ACTIVE");

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(quotationId);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setCode("SQ-1");
        quotation.setRfqId(rfqId);
        quotation.setSupplierId(supplierId);
        quotation.setTotalAmount(BigDecimal.valueOf(200));
        quotation.setCurrencyCode("UZS");

        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setCode("SQI-1");
        item.setRfqItemId(rfqItemId);
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("EA");
        item.setUnitPrice(BigDecimal.valueOf(200));
        item.setLineType(ProcurementLineType.SERVICE);
        item.setMaterialId(null);

        PurchaseOrderResponse response = new PurchaseOrderResponse();
        ArgumentCaptor<PurchaseOrder> purchaseOrderCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        ArgumentCaptor<com.enterprise.mm.entity.PurchaseOrderItem> purchaseOrderItemCaptor =
            ArgumentCaptor.forClass(com.enterprise.mm.entity.PurchaseOrderItem.class);
        when(repository.findByTenantIdAndIdAndIsDeletedFalse("default", quotationId)).thenReturn(Optional.of(quotation));
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));
        when(repository.findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc("default", rfqId)).thenReturn(List.of(quotation));
        when(quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse("default", quotationId)).thenReturn(List.of(item));
        when(rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse("default", rfqId))
            .thenReturn(List.of(rfqItem(rfqId, rfqItemId, BigDecimal.ONE, "EA")));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder po = invocation.getArgument(0);
            po.setId(UUID.randomUUID());
            return po;
        });
        when(purchaseOrderMapper.toResponse(any(PurchaseOrder.class))).thenReturn(response);

        SupplierQuotationService service = service();

        service.selectAndCreatePo(quotationId, "best");

        verify(supplierAccreditationService)
            .assertSupplierEligibleForDemand(supplierId, AccreditationDemand.services(), "supplier quotation award");
        org.mockito.Mockito.verify(purchaseOrderRepository).save(purchaseOrderCaptor.capture());
        org.mockito.Mockito.verify(purchaseOrderItemRepository).save(purchaseOrderItemCaptor.capture());
        assertThat(purchaseOrderCaptor.getValue().getRequestType()).isEqualTo(RequestType.SERVICE);
        assertThat(purchaseOrderItemCaptor.getValue().getLineType()).isEqualTo(ProcurementLineType.SERVICE);
        assertThat(purchaseOrderItemCaptor.getValue().getMaterialId()).isNull();
    }

    @Test
    void selectAndCreatePoPreservesWorksRequestTypeAndUsesWorkLines() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID rfqItemId = UUID.randomUUID();

        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(purchaseRequestId);
        purchaseRequest.setRequestType(RequestType.SERVICE);

        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setPurchaseRequestId(purchaseRequestId);
        rfq.setPurchaseRequest(purchaseRequest);
        rfq.setTenderProcedureType("SINGLE_SUPPLIER");

        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId("default");
        supplier.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        supplier.setStatus("ACTIVE");

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(quotationId);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setCode("SQ-WORKS-1");
        quotation.setRfqId(rfqId);
        quotation.setSupplierId(supplierId);
        quotation.setTotalAmount(BigDecimal.valueOf(200));
        quotation.setCurrencyCode("UZS");

        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setCode("SQI-WORKS-1");
        item.setName("Foundation works");
        item.setRfqItemId(rfqItemId);
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("LOT");
        item.setUnitPrice(BigDecimal.valueOf(200));
        item.setLineType(ProcurementLineType.WORK);
        item.setMaterialId(null);

        PurchaseOrderResponse response = new PurchaseOrderResponse();
        ArgumentCaptor<PurchaseOrder> purchaseOrderCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        ArgumentCaptor<com.enterprise.mm.entity.PurchaseOrderItem> purchaseOrderItemCaptor =
            ArgumentCaptor.forClass(com.enterprise.mm.entity.PurchaseOrderItem.class);
        when(repository.findByTenantIdAndIdAndIsDeletedFalse("default", quotationId)).thenReturn(Optional.of(quotation));
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));
        when(repository.findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc("default", rfqId)).thenReturn(List.of(quotation));
        when(quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse("default", quotationId)).thenReturn(List.of(item));
        when(rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse("default", rfqId))
            .thenReturn(List.of(rfqItem(rfqId, rfqItemId, BigDecimal.ONE, "LOT")));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> {
            PurchaseOrder po = invocation.getArgument(0);
            po.setId(UUID.randomUUID());
            return po;
        });
        when(purchaseOrderMapper.toResponse(any(PurchaseOrder.class))).thenReturn(response);

        service().selectAndCreatePo(quotationId, "best");

        verify(supplierAccreditationService)
            .assertSupplierEligibleForDemand(supplierId, AccreditationDemand.works(), "supplier quotation award");
        org.mockito.Mockito.verify(purchaseOrderRepository).save(purchaseOrderCaptor.capture());
        org.mockito.Mockito.verify(purchaseOrderItemRepository).save(purchaseOrderItemCaptor.capture());
        assertThat(purchaseOrderCaptor.getValue().getRequestType()).isEqualTo(RequestType.SERVICE);
        assertThat(purchaseOrderItemCaptor.getValue().getLineType()).isEqualTo(ProcurementLineType.WORK);
        assertThat(purchaseOrderItemCaptor.getValue().getMaterialId()).isNull();
    }

    @Test
    void getMarksQuotationUnavailableWhenDemandCycleIsNotApproved() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(quotationId);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfqId);
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setPurchaseRequestId(purchaseRequestId);
        SupplierQuotationResponse response = new SupplierQuotationResponse();
        response.setId(quotationId);
        when(repository.findByIdAndIsDeletedFalse(quotationId)).thenReturn(Optional.of(quotation));
        when(mapper.toResponse(quotation)).thenReturn(response);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(repository.findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc("default", rfqId))
            .thenReturn(List.of(quotation));
        when(quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse("default", quotationId))
            .thenReturn(List.of(scopedQuotationItem()));
        when(purchaseOrderDemandCyclePolicy.readinessForPurchaseRequest(purchaseRequestId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(false, PurchaseOrderDemandCyclePolicy.BLOCKED_REASON));

        SupplierQuotationResponse result = service().get(quotationId);

        assertThat(result.isSelectCreatePoReady()).isFalse();
        assertThat(result.getSelectCreatePoBlockedReason())
            .isEqualTo(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON);
    }

    @Test
    void getMarksQuotationUnavailableWhenTenderProtocolIsNotReady() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();

        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setTenderProcedureType("OPEN_COMPETITION");
        rfq.setSelectionRule("LOWEST_PRICE");
        rfq.setMinQuotationsRequired(2);

        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(quotationId);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfqId);
        quotation.setTotalAmount(BigDecimal.valueOf(100));

        SupplierQuotation rival = new SupplierQuotation();
        rival.setId(UUID.randomUUID());
        rival.setTenantId("default");
        rival.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rival.setStatus("SUBMITTED");
        rival.setRfqId(rfqId);
        rival.setTotalAmount(BigDecimal.valueOf(200));

        SupplierQuotationResponse response = new SupplierQuotationResponse();
        response.setId(quotationId);

        when(repository.findByIdAndIsDeletedFalse(quotationId)).thenReturn(Optional.of(quotation));
        when(mapper.toResponse(quotation)).thenReturn(response);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(repository.findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc("default", rfqId)).thenReturn(List.of(quotation, rival));
        org.mockito.Mockito.doThrow(new com.enterprise.mm.error.BusinessException("Tender opening protocol does not contain enough accepted quotations"))
            .when(tenderProcessService)
            .validateReadyForSelection(rfq, quotation);

        SupplierQuotationResponse result = service().get(quotationId);

        assertThat(result.isSelectCreatePoReady()).isFalse();
        assertThat(result.getSelectCreatePoBlockedReason())
            .isEqualTo("Tender opening protocol does not contain enough accepted quotations");
    }

    private SupplierQuotationItem scopedQuotationItem() {
        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        return item;
    }

    private SupplierQuotationService service() {
        return new SupplierQuotationService(
            repository,
            mapper,
            auditService,
            quotationItemRepository,
            quotationItemService,
            rfqRepository,
            rfqItemRepository,
            supplierRepository,
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            purchaseOrderMapper,
            integrationEventService,
            tenderProcessService,
            roleDataScopeService,
            new QuotationSelectionValidationService(tenderProcessService),
            supplierAccreditationService,
            materialRepository,
            awardFinalizationService,
            purchaseOrderDemandCyclePolicy
        );
    }

    private RFQItem rfqItem(UUID rfqId, UUID id, BigDecimal quantity, String unit) {
        RFQItem item = new RFQItem();
        item.setId(id);
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setRfqId(rfqId);
        item.setQuantity(quantity);
        item.setUnit(unit);
        return item;
    }
}
