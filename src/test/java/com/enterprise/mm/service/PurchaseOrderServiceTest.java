package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ContractMapper;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.PurchaseOrderUpdateRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoBatchRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoLineRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.payload.SupplierEligibilityDtos.SupplierEligibilityReason;
import com.enterprise.mm.payload.SupplierEligibilityDtos.UnifiedSupplierEligibility;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderRevisionRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingResult;
import com.enterprise.mm.procurement.command.service.PurchaseOrderDispatchReadinessService;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.supplier.po.service.SupplierPoCancellationService;
import com.enterprise.mm.supplier.po.service.SupplierPoCollaborationQueryService;
import com.enterprise.mm.supplier.po.service.SupplierPoDispatchService;
import com.enterprise.mm.supplier.po.service.SupplierPoSupersessionService;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.service.SupplierEligibilityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {
    @Mock private PurchaseOrderRepository repository;
    @Mock private PurchaseOrderMapper mapper;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private PurchaseOrderItemService itemService;
    @Mock private PurchaseOrderRevisionRepository revisionRepository;
    @Mock private PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Mock private ServiceClaimRepository serviceClaimRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private ProcurementStockPostingPort stockPostingPort;
    @Mock private SupplierEligibilityService supplierEligibilityService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private SupplierEvaluationService supplierEvaluationService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private ContractService contractService;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private PurchaseOrderDispatchReadinessService dispatchReadinessService;
    @Mock private ObjectMapper objectMapper;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private WarehouseReceiptPostingService warehouseReceiptPostingService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private SupplierPoDispatchService supplierPoDispatchService;
    @Mock private SupplierPoSupersessionService supplierPoSupersessionService;
    @Mock private SupplierPoCancellationService supplierPoCancellationService;
    @Mock private SupplierPoCollaborationQueryService supplierPoCollaborationQueryService;
    @Mock private SupplierPoResponseRepository supplierPoResponseRepository;
    @Mock private SupplierPoCommitmentRepository supplierPoCommitmentRepository;
    @Mock private DeliveryScheduleRepository deliveryScheduleRepository;
    @Mock private ProcurementPayablesNotificationService payablesNotificationService;
    @Mock private PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    @Mock private ContractInvoiceRepository contractInvoiceRepository;
    @Mock private ContractPaymentRepository contractPaymentRepository;
    @Mock private com.enterprise.mm.repository.ProcurementProcedureRepository procurementProcedureRepository;
    @Mock private com.enterprise.mm.repository.ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private BudgetItemService budgetItemService;
    @Mock private PurchaseOrderServiceOrderLinkService serviceOrderLinkService;
    @Mock private com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeReceiveActionPolicy goodsIncomeReceiveActionPolicy;
    @Mock private PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private com.enterprise.mm.security.pilot.PilotCapabilityPolicy pilotCapabilityPolicy;
    @InjectMocks private PurchaseOrderService service;
    @Mock private com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService fakturaEvidence;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
            .when(pilotCapabilityPolicy.enabled(com.enterprise.mm.security.pilot.PilotCapability.MM_FINANCE_EXECUTION))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(procurementLineageEnricher.enrichPurchaseOrder(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient()
            .when(purchaseOrderSourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                any(), any(), any()))
            .thenReturn(List.of());
        org.mockito.Mockito.lenient()
            .when(contractInvoiceRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                any(), any()))
            .thenReturn(List.of());
        org.mockito.Mockito.lenient()
            .when(contractPaymentRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void receiptQualityKeepsAcceptedQuantityStockEligibleWhenSeparateRejectedQuantityExists() throws Exception {
        java.lang.reflect.Method resolveQualityStatus = PurchaseOrderService.class.getDeclaredMethod(
            "resolveQualityStatus", BigDecimal.class, BigDecimal.class, BigDecimal.class, String.class);
        resolveQualityStatus.setAccessible(true);
        java.lang.reflect.Method acceptedReceiptQuantity = PurchaseOrderService.class.getDeclaredMethod(
            "acceptedReceiptQuantity", BigDecimal.class, String.class);
        acceptedReceiptQuantity.setAccessible(true);

        String qualityStatus = (String) resolveQualityStatus.invoke(
            service, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN, null);
        BigDecimal acceptedQuantity = (BigDecimal) acceptedReceiptQuantity.invoke(
            service, BigDecimal.TEN, qualityStatus);

        assertThat(qualityStatus).isEqualTo("NOT_REQUIRED");
        assertThat(acceptedQuantity).isEqualByComparingTo("10");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createBlocksPurchaseRequestOutsideApprovedDemandCycleBeforeMutation() {
        UUID purchaseRequestId = UUID.randomUUID();
        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest();
        request.setPurchaseRequestId(purchaseRequestId);
        doThrow(new BusinessException(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON))
            .when(purchaseOrderDemandCyclePolicy)
            .requireApprovedCycleForPurchaseRequest(purchaseRequestId);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON);

        verifyNoInteractions(mapper, repository);
    }

    @Test
    void createServicePurchaseOrderEnsuresLinkedServiceOrder() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest();
        request.setContractId(UUID.randomUUID());
        request.setRequestType(RequestType.SERVICE.name());

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(UUID.randomUUID());
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-SVC");
        purchaseOrder.setName("Service purchase order");
        purchaseOrder.setSupplierId(UUID.randomUUID());
        purchaseOrder.setContractId(request.getContractId());
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrder.setTotalAmount(new BigDecimal("300000"));
        purchaseOrder.setCurrencyCode("UZS");
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(mapper.toEntity(request)).thenReturn(purchaseOrder);
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.create(request);

        verify(serviceOrderLinkService).ensureForPurchaseOrder(purchaseOrder, "CREATE_FROM_SERVICE_PURCHASE_ORDER");
    }

    @Test
    void sendServicePurchaseOrderEnsuresLinkedServiceOrderBeforeDispatch() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-SVC");
        purchaseOrder.setName("Service purchase order");
        purchaseOrder.setStatus(PurchaseOrderStatus.APPROVED.name());
        purchaseOrder.setSupplierId(UUID.randomUUID());
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrder.setTotalAmount(new BigDecimal("300000"));
        purchaseOrder.setCurrencyCode("UZS");
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of());
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.send(purchaseOrderId);

        verify(serviceOrderLinkService).ensureForPurchaseOrder(purchaseOrder, "ENSURE_ON_PURCHASE_ORDER_SEND");
        verify(supplierPoDispatchService).createOrReuseDispatch(purchaseOrderId);
        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());
    }

    @Test
    void updatePreservesWorkflowOwnedFieldsAndWorksLineClassification() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.DRAFT.name(), supplierId);
        PurchaseOrderItem existingItem = worksItem(purchaseOrderId);
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        PurchaseOrderUpdateRequest request = new PurchaseOrderUpdateRequest();
        request.setCode("PO-WORKS-UPDATED");
        request.setName("Updated works PO");
        request.setStatus(PurchaseOrderStatus.COMPLETED.name());
        request.setRequestType(RequestType.GOODS.name());
        request.setCurrencyCode("UZS");
        PurchaseOrderItemCreateRequest replacementLine = new PurchaseOrderItemCreateRequest();
        replacementLine.setLineType(ProcurementLineType.MATERIAL.name());
        replacementLine.setMaterialId(materialId);
        replacementLine.setQuantity(BigDecimal.ONE);
        replacementLine.setUnitPrice(BigDecimal.TEN);
        request.setItems(List.of(replacementLine));

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(objectMapper.createObjectNode()).thenAnswer(invocation -> new ObjectMapper().createObjectNode());
        org.mockito.Mockito.doAnswer(invocation -> {
            PurchaseOrder entity = invocation.getArgument(0);
            PurchaseOrderUpdateRequest dto = invocation.getArgument(1);
            entity.setCode(dto.getCode());
            entity.setName(dto.getName());
            entity.setStatus(dto.getStatus());
            entity.setRequestType(RequestType.valueOf(dto.getRequestType()));
            entity.setCurrencyCode(dto.getCurrencyCode());
            return null;
        }).when(mapper).updateEntity(purchaseOrder, request);
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(existingItem));
        when(revisionRepository.countByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId))
            .thenReturn(0L);
        PurchaseOrderItemResponse createdItem = new PurchaseOrderItemResponse();
        createdItem.setId(UUID.randomUUID());
        when(itemService.create(any(PurchaseOrderItemCreateRequest.class))).thenReturn(createdItem);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.update(purchaseOrderId, request);

        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT.name());
        assertThat(purchaseOrder.getRequestType()).isEqualTo(RequestType.SERVICE);
        ArgumentCaptor<PurchaseOrderItemCreateRequest> itemCaptor = ArgumentCaptor.forClass(PurchaseOrderItemCreateRequest.class);
        verify(itemService).create(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(itemCaptor.getValue().getLineType()).isEqualTo(ProcurementLineType.WORK.name());
        assertThat(itemCaptor.getValue().getMaterialId()).isNull();
    }

    @Test
    void approvedServicePurchaseOrderReadinessUsesCanonicalTypedDemand() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setStatus(PurchaseOrderStatus.APPROVED.name());
        PurchaseOrderItem serviceLine = new PurchaseOrderItem();
        serviceLine.setLineType(ProcurementLineType.SERVICE);
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(serviceLine));
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);
        when(dispatchReadinessService.accreditationDemand(List.of(serviceLine)))
            .thenReturn(AccreditationDemand.services());
        when(supplierEligibilityService.evaluateNewBusiness(
            supplierId, AccreditationDemand.services(), "PO_DISPATCH"))
            .thenReturn(new UnifiedSupplierEligibility(
                supplierId,
                LocalDate.now(),
                "PO_DISPATCH",
                SupplierEligibilityService.STATUS_ELIGIBLE,
                true,
                false,
                false,
                List.of(),
                List.of()
            ));

        PurchaseOrderResponse result = service.get(purchaseOrderId);

        assertThat(result.getSendReady()).isTrue();
        verify(supplierEligibilityService).evaluateNewBusiness(
            supplierId, AccreditationDemand.services(), "PO_DISPATCH");
        verify(supplierAccreditationService, never()).supplierEligibilityBlockedReasonForDemand(
            any(), any(), any());
    }

    @Test
    void updateRejectsApprovedPurchaseOrder() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = worksPurchaseOrder(
            purchaseOrderId,
            PurchaseOrderStatus.APPROVED.name(),
            UUID.randomUUID()
        );

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.update(purchaseOrderId, new PurchaseOrderUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order can be edited only in DRAFT or RETURNED status");
        verifyNoInteractions(mapper);
    }

    @Test
    void sendWorksPurchaseOrderRequiresActiveContractBeforeSending() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.APPROVED.name(), supplierId);
        PurchaseOrderItem item = worksItem(purchaseOrderId);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        doThrow(new BusinessException("WORKS purchase order requires an ACTIVE contract before sending"))
            .when(contractService).requireWorksContract(null, supplierId, "UZS");

        assertThatThrownBy(() -> service.send(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessage("WORKS purchase order requires an ACTIVE contract before sending");
    }

    @Test
    void sendWorksPurchaseOrderValidatesContractButDoesNotConsumeContractAmount() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.APPROVED.name(), supplierId);
        PurchaseOrderItem item = worksItem(purchaseOrderId);
        purchaseOrder.setContractId(contractId);
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);
        when(contractService.requireWorksContract(contractId, supplierId, "UZS")).thenReturn(contractId);

        service.send(purchaseOrderId);

        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());
        verify(contractService).requireWorksContract(contractId, supplierId, "UZS");
        verify(contractService, never()).consumeLimit(any(), any(), eq(false), any());
        verify(supplierAccreditationService, never()).assertSupplierEligible(any(), any(), any());
    }

    @Test
    void sendIsIdempotentWhenAlreadySentAndCurrentDispatchMatchesPoVersion() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.SENT.name(), supplierId);
        purchaseOrder.setVersion(3L);
        com.enterprise.mm.entity.SupplierPoDispatch currentDispatch = new com.enterprise.mm.entity.SupplierPoDispatch();
        currentDispatch.setPurchaseOrderVersion(3L);
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(supplierPoDispatchService.findCurrent(purchaseOrderId)).thenReturn(Optional.of(currentDispatch));
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.send(purchaseOrderId);

        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.SENT.name());
        verify(integrationEventService, never()).emit(
                eq("MM_PO_SENT"),
                any(),
                any(),
                ArgumentMatchers.<Map<
                        String, ?>>any()
        ); verify(auditService, never()).log(any(), any(), eq("SEND"), any(), any(), any());
        verify(contractService, never()).consumeLimit(any(), any(), eq(false), any());
        verify(repository, never()).save(any());
    }

    @Test
    void sendRejectsAlreadySentPurchaseOrderWhenCurrentDispatchIsForAnOlderVersion() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.SENT.name(), supplierId);
        purchaseOrder.setVersion(4L);
        com.enterprise.mm.entity.SupplierPoDispatch staleDispatch = new com.enterprise.mm.entity.SupplierPoDispatch();
        staleDispatch.setPurchaseOrderVersion(3L);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(supplierPoDispatchService.findCurrent(purchaseOrderId)).thenReturn(Optional.of(staleDispatch));

        assertThatThrownBy(() -> service.send(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Only APPROVED purchase order can be sent");
    }

    @Test
    void legacyPurchaseOrderNeverDispatchedReportsCollaborationStatusNotSent() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.RECEIVED.name(), supplierId);
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);
        when(supplierPoDispatchService.findCurrent(purchaseOrderId)).thenReturn(Optional.empty());
        when(supplierPoDispatchService.neverDispatched(purchaseOrderId)).thenReturn(true);

        PurchaseOrderResponse result = service.get(purchaseOrderId);

        assertThat(result.getCollaborationStatus()).isEqualTo("NOT_SENT");
        assertThat(result.getLegacy()).isTrue();
    }

    @Test
    void approvedPurchaseOrderReadinessUsesEveryMaterialGroupFromItsLines() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(
            purchaseOrderId,
            PurchaseOrderStatus.APPROVED.name(),
            supplierId
        );
        purchaseOrder.setRequestType(RequestType.GOODS);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setLineType(ProcurementLineType.MATERIAL);
        Material material = new Material();
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        AccreditationDemand demand = new AccreditationDemand(
            Set.of(materialGroupId), true, true, false, false);
        when(dispatchReadinessService.accreditationDemand(List.of(item))).thenReturn(demand);
        when(supplierEligibilityService.evaluateNewBusiness(supplierId, demand, "PO_DISPATCH"))
            .thenReturn(new UnifiedSupplierEligibility(
                supplierId,
                LocalDate.now(),
                "PO_DISPATCH",
                SupplierEligibilityService.STATUS_NOT_ELIGIBLE,
                false,
                false,
                false,
                List.of(new SupplierEligibilityReason(
                    "SUPPLIER_MATERIAL_GROUP_NOT_COVERED",
                    "Supplier accreditation does not cover every material group")),
                List.of()
            ));

        PurchaseOrderResponse result = service.get(purchaseOrderId);

        assertThat(result.getSendReady()).isFalse();
        assertThat(result.getSendBlockedReason()).contains("every material group");
    }

    @Test
    void receiveRejectsWorksPurchaseOrderWarehouseReceipt() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.SENT.name(), UUID.randomUUID());
        PurchaseOrderItem item = worksItem(purchaseOrderId);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, UUID.randomUUID(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Warehouse receipt is not applicable for WORKS purchase orders");
    }

    @Test
    void receiveMaterialExistingObligationUsesEligibilityInsteadOfMaterialAccreditationGate() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-GOODS");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(materialId);
        item.setQuantity(BigDecimal.ONE);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem asnItem = asnLine(asnId, item.getId(), materialId, "10");
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(asnItem));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId)).thenReturn(Optional.of(material));
        when(stockPostingPort.postAcceptedReceipt(any()))
            .thenReturn(new ProcurementStockPostingPort.PostedReceipt(UUID.randomUUID(), LocalDate.now()));
        when(warehouseReceiptPostingService.record(any()))
            .thenReturn(new ReceiptPostingResult(
                UUID.randomUUID(),
                "GR-1",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
            ));
        when(integrationEventService.emit(any(), any(), any(), ArgumentMatchers.<Map<String, ?>>any()))
            .thenAnswer(invocation -> {
                IntegrationEvent event = new IntegrationEvent();
                event.setId(UUID.randomUUID());
                event.setEventType(invocation.getArgument(0));
                event.setStatus("PENDING");
                return event;
            });
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        PurchaseOrderResponse result = service.receive(
            purchaseOrderId,
            new ReceivePoRequest(warehouseId, storageLocationId, asnId, List.of()));

        assertThat(result.getReceiptOutcome()).isNotNull();
        verify(supplierEligibilityService).requireExistingObligationAllowed(supplierId, "GOODS_RECEIPT");
        verify(supplierAccreditationService, never()).assertSupplierEligible(any(), any(), any());
        verify(stockPostingPort).postAcceptedReceipt(any());
    }

    @Test
    void receiveDamagedGoodsQuarantinesCandidateStockWithoutAcceptedFulfillment() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-GOODS");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem asnItem = asnLine(asnId, itemId, materialId, "10");

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(asnItem));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId)).thenReturn(Optional.of(material));
        when(stockPostingPort.postAcceptedReceipt(any()))
            .thenReturn(new ProcurementStockPostingPort.PostedReceipt(UUID.randomUUID(), LocalDate.now()));
        QualityInspectionResponse inspection = new QualityInspectionResponse();
        inspection.setId(UUID.randomUUID());
        inspection.setInspectionNumber("QI-PENDING");
        inspection.setStatus("QUARANTINE");
        when(qualityInspectionService.create(any())).thenReturn(inspection);
        when(qualityInspectionService.startQuarantine(inspection.getId())).thenReturn(inspection);
        Attachment evidence = new Attachment();
        evidence.setId(evidenceId);
        evidence.setTenantId("default");
        evidence.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        evidence.setEntityType("PURCHASEORDER");
        evidence.setEntityId(purchaseOrderId);
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, evidenceId)).thenReturn(Optional.of(evidence));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            UUID.randomUUID(), "GR-TEST", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setStatus("PENDING");
        when(integrationEventService.emit(any(), any(), any(), ArgumentMatchers.<Map<String, ?>>any())).thenReturn(event);
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, new BigDecimal("9"), BigDecimal.ZERO, BigDecimal.ONE,
                null, "LOT-PENDING", storageLocationId, null)),
            null,
            null,
            List.of(evidenceId)
        ));

        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(item.getQualityStatus()).isEqualTo("PENDING_INSPECTION");
        assertThat(response.getFulfillmentState()).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(response.getCloseReady()).isFalse();
        assertThat(response.getCloseBlockedReason()).contains("accepted fulfillment");
        verify(stockPostingPort).postAcceptedReceipt(ArgumentMatchers.argThat(
            receipt -> receipt.quantity().compareTo(new BigDecimal("9")) == 0));
        verify(payablesNotificationService).notifyInvoiceRequired(eq(purchaseOrder), eq(new BigDecimal("90")), eq("UZS"));
        verify(qualityInspectionService).create(ArgumentMatchers.argThat(
            request -> request.getQuarantinedQuantity().compareTo(new BigDecimal("9")) == 0));
        verify(qualityInspectionService).startQuarantine(inspection.getId());
    }

    @Test
    void receiveCleanGoodsAutoAcceptsEvenWhenLegacyClientSendsPendingInspectionStatus() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();
        UUID stockMovementId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-GOODS");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem cleanAsnItem = asnLine(asnId, itemId, materialId, "10");

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(cleanAsnItem));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId)).thenReturn(Optional.of(material));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(stockPostingPort.postAcceptedReceipt(any())).thenReturn(new ProcurementStockPostingPort.PostedReceipt(stockMovementId, java.time.LocalDate.now()));
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            UUID.randomUUID(), "GR-TEST", new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setStatus("PENDING");
        when(integrationEventService.emit(any(), any(), any(), ArgumentMatchers.<Map<String, ?>>any())).thenReturn(event);
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, "LOT-CLEAN", storageLocationId, "PENDING_INSPECTION"))
        ));

        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("10");
        assertThat(item.getQualityStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(cleanAsnItem.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(asn.getStatus()).isEqualTo("RECEIVED");
        assertThat(response.getReceiptOutcome().acceptedQuantity()).isEqualByComparingTo("10");
        verify(stockPostingPort).postAcceptedReceipt(any());
        verify(payablesNotificationService).notifyInvoiceRequired(eq(purchaseOrder), eq(new BigDecimal("100")), eq("UZS"));
    }

    @Test
    void receiveBatchRequiredSplitsAcceptedQuantityAcrossBatches() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();
        UUID movementA = UUID.randomUUID();
        UUID movementB = UUID.randomUUID();
        UUID stockBatchA = UUID.randomUUID();
        UUID stockBatchB = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-BATCH");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);
        item.setBatchTrackingSnapshot(BatchTrackingPolicy.REQUIRED);
        item.setSerialTrackingSnapshot(SerialTrackingPolicy.NONE);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        material.setBatchTrackingPolicy(BatchTrackingPolicy.REQUIRED);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem asnItem = asnLine(asnId, itemId, materialId, "10");

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(asnItem));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId)).thenReturn(Optional.of(material));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(stockPostingPort.postAcceptedReceipt(any()))
            .thenReturn(
                new ProcurementStockPostingPort.PostedReceipt(movementA, stockBatchA, LocalDate.now()),
                new ProcurementStockPostingPort.PostedReceipt(movementB, stockBatchB, LocalDate.now())
            );
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            UUID.randomUUID(), "GR-BATCH", new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setStatus("PENDING");
        when(integrationEventService.emit(any(), any(), any(), ArgumentMatchers.<Map<String, ?>>any())).thenReturn(event);
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, storageLocationId, null, null, null, List.of(),
                List.of(
                    new ReceivePoBatchRequest("batch-a", "B001", null, null, new BigDecimal("6"), List.of()),
                    new ReceivePoBatchRequest("batch-b", "B002", null, null, new BigDecimal("4"), List.of())
                )
            ))
        ));

        ArgumentCaptor<ProcurementStockPostingPort.AcceptedReceipt> postCaptor =
            ArgumentCaptor.forClass(ProcurementStockPostingPort.AcceptedReceipt.class);
        verify(stockPostingPort, org.mockito.Mockito.times(2)).postAcceptedReceipt(postCaptor.capture());
        assertThat(postCaptor.getAllValues())
            .extracting(ProcurementStockPostingPort.AcceptedReceipt::batchNumber, ProcurementStockPostingPort.AcceptedReceipt::quantity)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("B001", new BigDecimal("6")),
                org.assertj.core.groups.Tuple.tuple("B002", new BigDecimal("4"))
            );

        ArgumentCaptor<ReceiptPostingDraft> receiptCaptor = ArgumentCaptor.forClass(ReceiptPostingDraft.class);
        verify(warehouseReceiptPostingService).record(receiptCaptor.capture());
        assertThat(receiptCaptor.getValue().lines().get(0).batches())
            .extracting(batch -> batch.manufacturerBatchNumber(), batch -> batch.stockBatchId(), batch -> batch.acceptedQuantity())
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("B001", stockBatchA, new BigDecimal("6")),
                org.assertj.core.groups.Tuple.tuple("B002", stockBatchB, new BigDecimal("4"))
            );
    }

    @Test
    void receiveBatchRequiredRejectsBatchTotalMismatchBeforePosting() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-BATCH-MISMATCH");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);
        item.setBatchTrackingSnapshot(BatchTrackingPolicy.REQUIRED);
        item.setSerialTrackingSnapshot(SerialTrackingPolicy.NONE);
        Material material = new Material();
        material.setId(materialId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem asnItem = asnLine(asnId, itemId, materialId, "10");

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(asnItem));

        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId)).thenReturn(Optional.of(material));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, storageLocationId, null, null, null, List.of(),
                List.of(
                    new ReceivePoBatchRequest("batch-a", "B001", null, null, new BigDecimal("6"), List.of()),
                    new ReceivePoBatchRequest("batch-b", "B002", null, null, new BigDecimal("3"), List.of())
                )
            ))
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("BATCH_QUANTITY_MISMATCH");

        verify(stockPostingPort, never()).postAcceptedReceipt(any());
        verify(warehouseReceiptPostingService, never()).record(any());
    }

    @Test
    void receiveRejectsQuantityAboveConfirmedAsnLineQuantity() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-GOODS");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal("100"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);

        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem asnItem = asnLine(asnId, itemId, materialId, "40");

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(asnItem));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, "LOT-OVER-ASN", storageLocationId, null))
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Receipt quantity exceeds ASN line quantity for 10");

        verifyNoInteractions(stockPostingPort, warehouseReceiptPostingService, payablesNotificationService);
    }

    @Test
    void receiveRejectsQuantityAboveAsnRemainingAfterPriorReceipt() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-GOODS");
        purchaseOrder.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal("100"));
        item.setReceivedQuantity(new BigDecimal("30"));
        item.setUnitPrice(BigDecimal.TEN);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);

        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("PARTIALLY_RECEIVED");
        AdvanceShippingNoticeItem asnItem = asnLine(asnId, itemId, materialId, "40");
        asnItem.setReceivedQuantity(new BigDecimal("30"));

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(asnItem));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, new BigDecimal("20"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, "LOT-OVER-ASN-REMAINING", storageLocationId, null))
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Receipt quantity exceeds ASN line quantity for 10");

        verifyNoInteractions(stockPostingPort, warehouseReceiptPostingService, payablesNotificationService);
    }

    @Test
    void receiveUsesLegacyAsnLineMaterialWhenPurchaseOrderItemLinkIsMissing() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();
        UUID stockMovementId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-GOODS");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(materialId);
        item.setQuantity(BigDecimal.TEN);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);

        Material material = new Material();
        material.setId(materialId);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setMaterialGroupId(materialGroupId);
        item.setMaterial(material);
        markTmb(item, material);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("default");
        warehouse.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId("default");
        storageLocation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        storageLocation.setWarehouseId(warehouseId);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        AdvanceShippingNoticeItem legacyAsnItem = asnLine(asnId, itemId, materialId, "10");
        legacyAsnItem.setPurchaseOrderItemId(null);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, warehouseId)).thenReturn(Optional.of(warehouse));
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, storageLocationId)).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, asnId)).thenReturn(List.of(legacyAsnItem));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId)).thenReturn(Optional.of(material));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(stockPostingPort.postAcceptedReceipt(any())).thenReturn(new ProcurementStockPostingPort.PostedReceipt(stockMovementId, java.time.LocalDate.now()));
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            UUID.randomUUID(), "GR-TEST", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setStatus("PENDING");
        when(integrationEventService.emit(any(), any(), any(), ArgumentMatchers.<Map<String, ?>>any())).thenReturn(event);
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                null, "LOT-LEGACY-ASN", storageLocationId, null))
        ));

        assertThat(legacyAsnItem.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(legacyAsnItem.getPurchaseOrderItemId()).isEqualTo(itemId);
        assertThat(response.getReceiptOutcome().acceptedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void closeRejectsWorksPurchaseOrderUntilCompleted() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.SENT.name(), UUID.randomUUID());
        PurchaseOrderItem item = worksItem(purchaseOrderId);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessage("WORKS purchase order must be COMPLETED before close");

        assertThat(item.getLineType()).isEqualTo(ProcurementLineType.WORK);
        assertThat(item.getServiceActId()).isNull();
        assertThat(item.getServiceOrderId()).isNull();
        verifyNoInteractions(serviceAcceptanceActRepository);
    }

    @Test
    void closeAllowsCompletedWorksPurchaseOrderWithoutServiceAcceptanceReconciliation() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();

        PurchaseOrder purchaseOrder = worksPurchaseOrder(purchaseOrderId, PurchaseOrderStatus.COMPLETED.name(), UUID.randomUUID());
        PurchaseOrderItem item = worksItem(purchaseOrderId);
        PurchaseOrderResponse response = new PurchaseOrderResponse();

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(workCompletionActRepository.cumulativeQuantity(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.ONE);
        when(workCompletionActRepository.cumulativeAmount(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.ONE);
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(mapper.toResponse(purchaseOrder)).thenReturn(response);

        PurchaseOrderResponse result = service.close(purchaseOrderId);

        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED.name());
        assertThat(result.getFulfillmentState()).isEqualTo("FULFILLED");
        assertThat(result.getInvoicingState()).isEqualTo("NOT_INVOICED");
        assertThat(result.getPaymentState()).isEqualTo("NOT_REQUESTED");
        assertThat(result.getAdministrativeCloseState()).isEqualTo("CLOSED");
        assertThat(result.getCloseReady()).isFalse();
        assertThat(item.getLineType()).isEqualTo(ProcurementLineType.WORK);
        assertThat(item.getServiceActId()).isNull();
        assertThat(item.getServiceOrderId()).isNull();
        verifyNoInteractions(serviceAcceptanceActRepository);
    }

    @Test
    void closeRejectsCompletedWorksPurchaseOrderWithoutPaidWorkCoverage() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = worksPurchaseOrder(
            purchaseOrderId,
            PurchaseOrderStatus.COMPLETED.name(),
            UUID.randomUUID()
        );
        PurchaseOrderItem item = worksItem(purchaseOrderId);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(workCompletionActRepository.cumulativeQuantity(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("paid");
    }

    @Test
    void closeRejectsFullyPaidWorksPurchaseOrderWithOpenClaim() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = worksPurchaseOrder(
            purchaseOrderId,
            PurchaseOrderStatus.COMPLETED.name(),
            UUID.randomUUID()
        );
        PurchaseOrderItem item = worksItem(purchaseOrderId);

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(workCompletionActRepository.cumulativeQuantity(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.ONE);
        when(workCompletionActRepository.cumulativeAmount(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.ONE);
        when(serviceClaimRepository.findByTenantIdAndPurchaseOrderItemIdAndStatusNotAndIsDeletedFalse(
            "default", item.getId(), "CLOSED"
        )).thenReturn(List.of(new ServiceClaim()));

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("open service claims");
    }

    @Test
    void closeRejectsWorksPurchaseOrderWhosePaidAmountDoesNotCoverTheWorkLineValue() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID purchaseOrderId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = worksPurchaseOrder(
            purchaseOrderId,
            PurchaseOrderStatus.COMPLETED.name(),
            UUID.randomUUID()
        );
        PurchaseOrderItem item = worksItem(purchaseOrderId);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.valueOf(100));

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(item));
        when(workCompletionActRepository.cumulativeQuantity(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.TEN);
        when(workCompletionActRepository.cumulativeAmount(
            eq("default"), eq(item.getId()), eq(List.of("PAID")), eq(null)
        )).thenReturn(BigDecimal.valueOf(500));

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("paid work completion coverage");
    }

    private PurchaseOrder worksPurchaseOrder(UUID purchaseOrderId, String status, UUID supplierId) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-WORKS");
        purchaseOrder.setStatus(status);
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.SERVICE);
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder.setTotalAmount(BigDecimal.valueOf(250));
        return purchaseOrder;
    }

    private PurchaseOrderItem worksItem(UUID purchaseOrderId) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.WORK);
        item.setStatus("OPEN");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.ONE);
        return item;
    }

    private AdvanceShippingNoticeItem asnLine(UUID asnId, UUID purchaseOrderItemId, UUID materialId, String quantity) {
        AdvanceShippingNoticeItem item = new AdvanceShippingNoticeItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setAsnId(asnId);
        item.setPurchaseOrderItemId(purchaseOrderItemId);
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    private void markTmb(PurchaseOrderItem item, Material material) {
        material.setMaterialType(MaterialType.TMB);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setId(UUID.randomUUID());
        unit.setCode("EA");
        unit.setName("Each");
        unit.setStatus("ACTIVE");
        unit.setUnitType(MeasurementUnitType.QUANTITY);
        material.setUnitOfMeasureId(unit.getId());
        material.setUnitOfMeasure(unit);
        item.setUnit("EA");
    }
}

@ExtendWith(MockitoExtension.class)
class ContractServiceWorksValidationTest {
    @Mock private ContractRepository repository;
    @Mock private ContractSpecificationRepository specificationRepository;
    @Mock private ContractMapper mapper;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private SupplierAccreditationDemandResolver accreditationDemandResolver;
    @Mock private AuditService auditService;
    @Mock private PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    @InjectMocks private ContractService service;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void getExposesDemandCyclePurchaseOrderReadiness() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID contractId = UUID.randomUUID();
        Contract contract = contract(contractId, UUID.randomUUID(), "UZS", ContractStatus.ACTIVE.name());
        ContractResponse mapped = new ContractResponse();
        when(repository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        when(mapper.toResponse(contract)).thenReturn(mapped);
        when(purchaseOrderDemandCyclePolicy.readinessForContract(contractId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(false, PurchaseOrderDemandCyclePolicy.BLOCKED_REASON));

        ContractResponse response = service.get(contractId);

        assertThat(response.isPurchaseOrderReady()).isFalse();
        assertThat(response.getPurchaseOrderBlockedReason())
            .isEqualTo(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON);
    }

    @Test
    void validateWorksContractRequiresActiveContract() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID contractId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, supplierId, ContractStatus.ACTIVE.name()))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.validateWorksContract(null, supplierId, "UZS"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("WORKS purchase order requires an ACTIVE contract before sending");

        Contract draftContract = contract(contractId, UUID.randomUUID(), "UZS", ContractStatus.DRAFT.name());
        when(repository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(draftContract));

        assertThatThrownBy(() -> service.validateWorksContract(contractId, draftContract.getSupplierId(), "UZS"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("WORKS purchase order requires an ACTIVE contract before sending");
    }

    @Test
    void requireWorksContractResolvesActiveContractWhenPoHasNoContractId() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID supplierId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        Contract active = contract(contractId, supplierId, "UZS", ContractStatus.ACTIVE.name());
        active.setStartDate(java.time.LocalDate.now().minusDays(1));
        active.setEndDate(java.time.LocalDate.now().plusMonths(6));
        active.setLimitAmount(new BigDecimal("1000"));
        active.setUsedAmount(BigDecimal.ZERO);
        active.setCreatedAt(java.time.LocalDateTime.now());

        when(repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, supplierId, ContractStatus.ACTIVE.name()))
            .thenReturn(List.of(active));

        assertThat(service.requireWorksContract(null, supplierId, "UZS")).isEqualTo(contractId);
    }

    @Test
    void validateWorksContractRequiresMatchingSupplier() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID contractId = UUID.randomUUID();
        Contract contract = contract(contractId, UUID.randomUUID(), "UZS", ContractStatus.ACTIVE.name());

        when(repository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.validateWorksContract(contractId, UUID.randomUUID(), "UZS"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("WORKS contract supplier must match purchase order supplier");
    }

    @Test
    void validateWorksContractRequiresMatchingCurrencyIgnoringCase() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID contractId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Contract contract = contract(contractId, supplierId, "usd", ContractStatus.ACTIVE.name());

        when(repository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));

        service.validateWorksContract(contractId, supplierId, "USD");

        assertThatThrownBy(() -> service.validateWorksContract(contractId, supplierId, "UZS"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("WORKS contract currency must match purchase order currency");
    }

    @Test
    void activateValidatesSupplierAgainstContractMaterialGroups() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID contractId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        Contract contract = contract(contractId, supplierId, "UZS", ContractStatus.DRAFT.name());
        contract.setCode("CON-MATERIAL-GROUP");
        contract.setStartDate(java.time.LocalDate.now().minusDays(1));
        ContractSpecification specification = new ContractSpecification();
        specification.setContractId(contractId);
        specification.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        specification.setLineType(ProcurementLineType.MATERIAL);
        specification.setMaterialId(materialId);
        when(repository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        when(specificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse("default", contractId))
            .thenReturn(List.of(specification));
        when(repository.save(contract)).thenReturn(contract);
        when(accreditationDemandResolver.forContractSpecifications(List.of(specification)))
            .thenReturn(new AccreditationDemand(Set.of(materialGroupId), true, true, false, false));

        service.activate(contractId);

        verify(supplierAccreditationService)
            .assertSupplierEligibleForDemand(
                supplierId,
                new AccreditationDemand(Set.of(materialGroupId), true, true, false, false),
                "contract activation");
    }

    @Test
    void activateRequiresAllScopesForMixedContract() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID contractId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        Contract contract = contract(contractId, supplierId, "UZS", ContractStatus.DRAFT.name());
        contract.setStartDate(java.time.LocalDate.now().minusDays(1));
        ContractSpecification material = new ContractSpecification();
        material.setLineType(ProcurementLineType.MATERIAL);
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        ContractSpecification serviceLine = new ContractSpecification();
        serviceLine.setLineType(ProcurementLineType.SERVICE);
        serviceLine.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        ContractSpecification work = new ContractSpecification();
        work.setLineType(ProcurementLineType.WORK);
        work.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        List<ContractSpecification> specifications = List.of(material, serviceLine, work);
        AccreditationDemand demand = new AccreditationDemand(
            Set.of(materialGroupId), true, true, true, true);

        when(repository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        when(specificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse("default", contractId))
            .thenReturn(specifications);
        when(accreditationDemandResolver.forContractSpecifications(specifications)).thenReturn(demand);
        when(repository.save(contract)).thenReturn(contract);

        service.activate(contractId);

        verify(supplierAccreditationService)
            .assertSupplierEligibleForDemand(supplierId, demand, "contract activation");
    }

    @Test
    void activateServiceOnlyAndWorksOnlyContractsUseTheirTypedScopes() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID supplierId = UUID.randomUUID();

        UUID serviceContractId = UUID.randomUUID();
        Contract serviceContract = contract(
            serviceContractId, supplierId, "UZS", ContractStatus.DRAFT.name());
        serviceContract.setStartDate(java.time.LocalDate.now().minusDays(1));
        ContractSpecification serviceLine = new ContractSpecification();
        serviceLine.setLineType(ProcurementLineType.SERVICE);
        serviceLine.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        when(repository.findByIdAndIsDeletedFalse(serviceContractId))
            .thenReturn(Optional.of(serviceContract));
        when(specificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse(
            "default", serviceContractId)).thenReturn(List.of(serviceLine));
        when(accreditationDemandResolver.forContractSpecifications(List.of(serviceLine)))
            .thenReturn(AccreditationDemand.services());
        when(repository.save(serviceContract)).thenReturn(serviceContract);

        service.activate(serviceContractId);

        verify(supplierAccreditationService).assertSupplierEligibleForDemand(
            supplierId, AccreditationDemand.services(), "contract activation");

        UUID worksContractId = UUID.randomUUID();
        Contract worksContract = contract(
            worksContractId, supplierId, "UZS", ContractStatus.DRAFT.name());
        worksContract.setStartDate(java.time.LocalDate.now().minusDays(1));
        ContractSpecification worksLine = new ContractSpecification();
        worksLine.setLineType(ProcurementLineType.WORK);
        worksLine.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        when(repository.findByIdAndIsDeletedFalse(worksContractId))
            .thenReturn(Optional.of(worksContract));
        when(specificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse(
            "default", worksContractId)).thenReturn(List.of(worksLine));
        when(accreditationDemandResolver.forContractSpecifications(List.of(worksLine)))
            .thenReturn(AccreditationDemand.works());
        when(repository.save(worksContract)).thenReturn(worksContract);

        service.activate(worksContractId);

        verify(supplierAccreditationService).assertSupplierEligibleForDemand(
            supplierId, AccreditationDemand.works(), "contract activation");
    }

    @Test
    void beforeCreateForcesLedgerAmountsToZero() {
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Contract entity = new Contract();
        entity.setName("en: Falcon contract");
        entity.setStatus("ACTIVE");
        entity.setUsedAmount(null);
        entity.setServiceUsedAmount(null);
        entity.setLimitAmount(null);

        service.beforeCreate(entity, new com.enterprise.mm.payload.ContractCreateRequest());

        assertThat(entity.getStatus()).isEqualTo(ContractStatus.DRAFT.name());
        assertThat(entity.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entity.getServiceUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entity.getLimitAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private Contract contract(UUID contractId, UUID supplierId, String currencyCode, String status) {
        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setTenantId("default");
        contract.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        contract.setStatus(status);
        contract.setSupplierId(supplierId);
        contract.setCurrencyCode(currencyCode);
        return contract;
    }

}
