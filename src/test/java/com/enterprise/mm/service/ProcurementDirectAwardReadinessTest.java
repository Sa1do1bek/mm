package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.ProcurementDirectAward;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardPurchaseOrderRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardResponse;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.ProcurementDirectAwardRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementDirectAwardApprovalAdapter;
import com.enterprise.mm.supplier.po.service.SupplierPoAsnService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementDirectAwardReadinessTest {

    @Mock private ProcurementDirectAwardRepository repository;
    @Mock private ProcurementProcedureRepository procedureRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private ProcurementMethodPolicy methodPolicy;
    @Mock private AuditService auditService;
    @Mock private PurchaseOrderDemandCyclePolicy demandCyclePolicy;
    @Mock private ProcurementAwardFinalizationOrchestrator awardFinalizationOrchestrator;
    @Mock private ContractService contractService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private ProcurementMethodCapabilityService capabilityService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private SupplierAccreditationDemandResolver accreditationDemandResolver;
    @InjectMocks private ProcurementDirectAwardService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void responseExposesDemandCycleBlockReason() {
        UUID awardId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementDirectAward award = new ProcurementDirectAward();
        award.setId(awardId);
        award.setTenantId("default");
        award.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        award.setStatus("APPROVED");
        award.setPurchaseRequestId(purchaseRequestId);
        when(repository.findByIdAndIsDeletedFalse(awardId)).thenReturn(Optional.of(award));
        when(demandCyclePolicy.readinessForPurchaseRequest(purchaseRequestId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(
                false,
                PurchaseOrderDemandCyclePolicy.BLOCKED_REASON
            ));

        DirectAwardResponse response = service.get(awardId);

        assertThat(response.purchaseOrderReady()).isFalse();
        assertThat(response.purchaseOrderBlockedReason())
            .isEqualTo(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON);
    }

    @Test
    void directAwardRejectsMissingBasisType() {
        DirectAwardRequest request = new DirectAwardRequest(
            UUID.randomUUID(),
            new BigDecimal("1200.00"),
            "UZS",
            "Supplier selection is justified by approved exception workflow",
            null,
            null,
            "Urgent delivery is needed to prevent operational disruption",
            null
        );

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("awardBasisType is required");
    }

    @Test
    void directAwardRejectsMissingExplanation() {
        DirectAwardRequest request = new DirectAwardRequest(
            UUID.randomUUID(),
            new BigDecimal("1200.00"),
            "UZS",
            "Supplier selection is justified by approved exception workflow",
            "URGENCY",
            null,
            null,
            null
        );

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("awardBasisExplanation must contain a meaningful explanation");
    }

    @Test
    void directPurchaseAcceptsMarketResearchWithGovernedEvidence() {
        UUID procedureId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        Supplier supplier = activeSupplier(supplierId);
        DirectAwardRequest request = new DirectAwardRequest(
            supplierId,
            new BigDecimal("1200.00"),
            "UZS",
            "Supplier selection is justified by approved exception workflow",
            "MARKET_RESEARCH",
            null,
            "Market research supports the documented supplier selection",
            null,
            "EVIDENCE-2026-001",
            new BigDecimal("1250.00"),
            "MARKET-PRICE-2026-001",
            null,
            "No buyer or approver conflict exists for this supplier selection",
            true
        );

        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(repository.findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId
        )).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));
        when(repository.save(any(ProcurementDirectAward.class))).thenAnswer(invocation -> {
            ProcurementDirectAward award = invocation.getArgument(0);
            award.setId(UUID.randomUUID());
            return award;
        });
        when(procedureRepository.save(any(ProcurementProcedure.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(demandCyclePolicy.readinessForPurchaseRequest(purchaseRequestId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(true, null));

        DirectAwardResponse response = service.create(procedureId, request);

        assertThat(response.awardBasisType()).isEqualTo("MARKET_RESEARCH");
        assertThat(response.basisCode()).isEqualTo("MARKET_RESEARCH");
        assertThat(response.awardBasisExplanation())
            .isEqualTo("Market research supports the documented supplier selection");
        assertThat(response.awardBasis())
            .isEqualTo("Market research supports the documented supplier selection");
    }

    @Test
    void submitStartsUnifiedApprovalInsteadOfApprovingDirectly() {
        UUID awardId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementDirectAward award = new ProcurementDirectAward();
        award.setId(awardId);
        award.setTenantId(TenantContext.currentTenant());
        award.setCompanyId(CompanyContext.currentCompany());
        award.setStatus("DRAFT");
        award.setPurchaseRequestId(purchaseRequestId);
        when(repository.findByIdAndIsDeletedFalse(awardId)).thenReturn(Optional.of(award));
        when(demandCyclePolicy.readinessForPurchaseRequest(purchaseRequestId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(true, null));

        DirectAwardResponse response = service.submit(awardId);

        org.mockito.Mockito.verify(approvalEngineService)
            .start(ProcurementDirectAwardApprovalAdapter.MODULE_KEY, awardId);
        assertThat(response.status()).isEqualTo("DRAFT");
    }

    @Test
    void approveEndpointCannotBypassApprovalTaskAssignment() {
        assertThatThrownBy(() -> service.approve(UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Direct award approval must be completed through approval task endpoints");
    }

    @Test
    void singleSourceStartsItsDistinctApprovalFlow() {
        UUID awardId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, procedureId, purchaseRequestId, UUID.randomUUID());
        award.setStatus("DRAFT");
        award.setMethod(ProcurementMethodType.SINGLE_SOURCE.name());
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        procedure.setMethod(ProcurementMethodType.SINGLE_SOURCE.name());
        when(repository.findByIdAndIsDeletedFalse(awardId)).thenReturn(Optional.of(award));
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.SINGLE_SOURCE.name()))
            .thenReturn(ProcurementMethodType.SINGLE_SOURCE);
        when(supplierRepository.findByIdAndIsDeletedFalse(award.getSupplierId()))
            .thenReturn(Optional.of(activeSupplier(award.getSupplierId())));
        when(demandCyclePolicy.readinessForPurchaseRequest(purchaseRequestId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(true, null));

        service.submit(awardId);

        verify(approvalEngineService).start(ProcurementDirectAwardApprovalAdapter.SINGLE_SOURCE_MODULE_KEY, awardId);
    }

    @Test
    void directPurchaseRejectsMissingMarketPriceEvidence() {
        UUID procedureId = UUID.randomUUID();
        ProcurementProcedure procedure = directAwardProcedure(procedureId, UUID.randomUUID());
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);

        assertThatThrownBy(() -> service.create(
            procedureId,
            governedRequest(UUID.randomUUID(), ProcurementMethodType.DIRECT_PURCHASE, null)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("marketPriceEvidenceReference is required for DIRECT_PURCHASE");
    }

    @Test
    void capabilityThresholdBlocksDirectPurchaseAboveConfiguredMaximum() {
        UUID procedureId = UUID.randomUUID();
        ProcurementProcedure procedure = directAwardProcedure(procedureId, UUID.randomUUID());
        DirectAwardRequest request = governedRequest(
            UUID.randomUUID(), ProcurementMethodType.DIRECT_PURCHASE, "MARKET-PRICE-2026-001");
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        org.mockito.Mockito.doThrow(new BusinessException("procurement.method_capability.amount_above_maximum"))
            .when(capabilityService).requireAvailableForCompany(
                ProcurementMethodType.DIRECT_PURCHASE.name(), new BigDecimal("1200.00"), "UZS",
                null, null, CompanyContext.currentCompany());

        assertThatThrownBy(() -> service.create(procedureId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("procurement.method_capability.amount_above_maximum");
    }

    @Test
    void singleSourceRejectsMissingNegotiationEvidence() {
        UUID procedureId = UUID.randomUUID();
        ProcurementProcedure procedure = directAwardProcedure(procedureId, UUID.randomUUID());
        procedure.setMethod(ProcurementMethodType.SINGLE_SOURCE.name());
        DirectAwardRequest base = governedRequest(
            UUID.randomUUID(), ProcurementMethodType.SINGLE_SOURCE, "MARKET-PRICE-2026-001");
        DirectAwardRequest missingNegotiation = new DirectAwardRequest(
            base.supplierId(), base.awardAmount(), base.currencyCode(), base.justification(),
            base.awardBasisType(), base.basisCode(), base.awardBasisExplanation(), base.awardBasis(),
            base.evidenceReference(), base.marketPriceAmount(), base.marketPriceEvidenceReference(),
            null, base.conflictOfInterestDeclaration(), base.conflictOfInterestConfirmed());
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.SINGLE_SOURCE.name()))
            .thenReturn(ProcurementMethodType.SINGLE_SOURCE);

        assertThatThrownBy(() -> service.create(procedureId, missingNegotiation))
            .isInstanceOf(BusinessException.class)
            .hasMessage("negotiationSummary must contain a meaningful explanation");
    }

    @Test
    void supplierAccreditationBlockPreventsDirectAward() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        PurchaseRequest purchaseRequest = purchaseRequest(purchaseRequestId, RequestType.GOODS);
        PurchaseRequestItem item = purchaseRequestItem(
            purchaseRequestId, ProcurementLineType.MATERIAL, UUID.randomUUID());
        var demand = com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand.materials(
            List.of(UUID.randomUUID()));
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(activeSupplier(supplierId)));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseRequestId)).thenReturn(List.of(item));
        when(accreditationDemandResolver.forPurchaseRequestItems(RequestType.GOODS, List.of(item))).thenReturn(demand);
        org.mockito.Mockito.doThrow(new BusinessException("Supplier accreditation is required for direct award"))
            .when(supplierAccreditationService)
            .assertSupplierEligibleForDemand(supplierId, demand, "direct award supplier selection");

        assertThatThrownBy(() -> service.create(
            procedureId,
            governedRequest(supplierId, ProcurementMethodType.DIRECT_PURCHASE, "MARKET-PRICE-2026-001")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier accreditation is required for direct award");
    }

    @Test
    void blockedSupplierCannotBeSelectedForDirectAward() {
        UUID procedureId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ProcurementProcedure procedure = directAwardProcedure(procedureId, UUID.randomUUID());
        Supplier supplier = activeSupplier(supplierId);
        supplier.setStatus("BLOCKED");
        supplier.setBlockedReason("Sanctions match");
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.create(
            procedureId,
            governedRequest(supplierId, ProcurementMethodType.DIRECT_PURCHASE, "MARKET-PRICE-2026-001")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("BLOCKED", "Sanctions match");
    }

    @Test
    void approvedSupplierAndAmountCannotBeChanged() {
        UUID awardId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        award.setMethod(ProcurementMethodType.DIRECT_PURCHASE.name());
        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));

        assertThatThrownBy(() -> service.update(
            awardId,
            governedRequest(UUID.randomUUID(), ProcurementMethodType.DIRECT_PURCHASE, "MARKET-PRICE-2026-001")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Approved direct award supplier, amount, method and output are immutable");
    }

    @Test
    void pendingAmountChangeCancelsApprovalForRestart() {
        UUID awardId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, procedureId, purchaseRequestId, supplierId);
        award.setStatus("APPROVAL_PENDING");
        award.setMethod(ProcurementMethodType.DIRECT_PURCHASE.name());
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(activeSupplier(supplierId)));
        when(repository.save(award)).thenReturn(award);
        when(demandCyclePolicy.readinessForPurchaseRequest(purchaseRequestId))
            .thenReturn(new PurchaseOrderDemandCyclePolicy.Readiness(true, null));

        DirectAwardRequest changed = governedRequest(
            supplierId, ProcurementMethodType.DIRECT_PURCHASE, "MARKET-PRICE-2026-002");
        service.update(awardId, changed);

        verify(approvalEngineService).resetForResubmission(
            ProcurementDirectAwardApprovalAdapter.MODULE_KEY,
            awardId,
            "Direct award material terms changed"
        );
        assertThat(award.getStatus()).isEqualTo("DRAFT");
        assertThat(award.getAwardAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void approvedSnapshotMismatchBlocksCommercialOutput() {
        UUID awardId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        award.setMethod(ProcurementMethodType.DIRECT_PURCHASE.name());
        award.setApprovedSupplierId(UUID.randomUUID());
        award.setApprovedAwardAmount(award.getAwardAmount());
        award.setApprovedMethod(ProcurementMethodType.DIRECT_PURCHASE.name());
        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));

        assertThatThrownBy(() -> service.createPurchaseOrder(awardId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Direct award material terms changed after approval; restart approval");
        verify(purchaseOrderService, never()).create(any(PurchaseOrderCreateRequest.class));
    }

    @Test
    void duplicateCommercialOutputReturnsExistingPurchaseOrder() {
        UUID awardId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        award.setMethod(ProcurementMethodType.DIRECT_PURCHASE.name());
        award.setApprovedSupplierId(award.getSupplierId());
        award.setApprovedAwardAmount(award.getAwardAmount());
        award.setApprovedMethod(award.getMethod());
        award.setPurchaseOrderId(purchaseOrderId);
        ProcurementProcedure procedure = directAwardProcedure(award.getProcurementProcedureId(), award.getPurchaseRequestId());
        var existing = new com.enterprise.mm.payload.PurchaseOrderResponse();
        existing.setId(purchaseOrderId);
        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));
        when(procedureRepository.lockScopedById(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), award.getProcurementProcedureId()))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(purchaseOrderService.get(purchaseOrderId)).thenReturn(existing);

        assertThat(service.createPurchaseOrder(awardId, null).getId()).isEqualTo(purchaseOrderId);
        verify(purchaseOrderService, never()).create(any(PurchaseOrderCreateRequest.class));
    }

    @Test
    void singleSourceDirectAwardCopiesMaterialIdToPurchaseOrderItem() {
        UUID awardId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, procedureId, purchaseRequestId, supplierId);
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        procedure.setStatus(ProcurementProcedureStatus.DIRECT_AWARD_APPROVED.name());
        PurchaseRequest purchaseRequest = purchaseRequest(purchaseRequestId, RequestType.GOODS);
        PurchaseRequestItem item = purchaseRequestItem(purchaseRequestId, ProcurementLineType.MATERIAL, materialId);
        var purchaseOrderResponse = new com.enterprise.mm.payload.PurchaseOrderResponse();
        purchaseOrderResponse.setId(purchaseOrderId);

        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest));
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(activeSupplier(supplierId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseRequestId
        )).thenReturn(List.of(item));
        when(purchaseOrderService.create(any(PurchaseOrderCreateRequest.class))).thenReturn(purchaseOrderResponse);

        service.createPurchaseOrder(
            awardId,
            new DirectAwardPurchaseOrderRequest(LocalDate.now(), LocalDate.now().plusDays(7), null)
        );

        ArgumentCaptor<PurchaseOrderCreateRequest> captor = ArgumentCaptor.forClass(PurchaseOrderCreateRequest.class);
        verify(purchaseOrderService).create(captor.capture());
        PurchaseOrderCreateRequest createdPo = captor.getValue();
        assertThat(createdPo.getItems()).hasSize(1);
        assertThat(createdPo.getItems().get(0).getPurchaseRequestItemId()).isEqualTo(item.getId());
        assertThat(createdPo.getItems().get(0).getMaterialId()).isEqualTo(materialId);
    }

    @Test
    void directAwardPurchaseOrderMaterialLinkAllowsSupplierCommitmentAsnShipment() {
        UUID awardId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, procedureId, purchaseRequestId, supplierId);
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        procedure.setStatus(ProcurementProcedureStatus.DIRECT_AWARD_APPROVED.name());
        PurchaseRequest purchaseRequest = purchaseRequest(purchaseRequestId, RequestType.GOODS);
        PurchaseRequestItem requestItem = purchaseRequestItem(purchaseRequestId, ProcurementLineType.MATERIAL, materialId);
        var purchaseOrderResponse = new com.enterprise.mm.payload.PurchaseOrderResponse();
        purchaseOrderResponse.setId(purchaseOrderId);

        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest));
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(activeSupplier(supplierId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseRequestId
        )).thenReturn(List.of(requestItem));
        when(purchaseOrderService.create(any(PurchaseOrderCreateRequest.class))).thenReturn(purchaseOrderResponse);

        service.createPurchaseOrder(
            awardId,
            new DirectAwardPurchaseOrderRequest(LocalDate.now(), LocalDate.now().plusDays(7), null)
        );

        ArgumentCaptor<PurchaseOrderCreateRequest> captor = ArgumentCaptor.forClass(PurchaseOrderCreateRequest.class);
        verify(purchaseOrderService).create(captor.capture());
        UUID purchaseOrderItemMaterialId = captor.getValue().getItems().get(0).getMaterialId();
        assertThat(purchaseOrderItemMaterialId).isEqualTo(materialId);

        AdvanceShippingNoticeItem asnItem = createSupplierAsnFromCommitment(
            purchaseOrderId,
            supplierId,
            purchaseOrderItemMaterialId
        );

        assertThat(asnItem.getMaterialId()).isEqualTo(materialId);
        assertThat(asnItem.getQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void goodsPoCreationFailsWhenSourceItemHasNoMaterial() {
        UUID awardId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        ProcurementDirectAward award = approvedAward(awardId, procedureId, purchaseRequestId, supplierId);
        ProcurementProcedure procedure = directAwardProcedure(procedureId, purchaseRequestId);
        procedure.setStatus(ProcurementProcedureStatus.DIRECT_AWARD_APPROVED.name());
        PurchaseRequest purchaseRequest = purchaseRequest(purchaseRequestId, RequestType.GOODS);
        PurchaseRequestItem item = purchaseRequestItem(purchaseRequestId, ProcurementLineType.MATERIAL, null);

        when(repository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), awardId))
            .thenReturn(Optional.of(award));
        when(procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), procedureId))
            .thenReturn(Optional.of(procedure));
        when(methodPolicy.require(ProcurementMethodType.DIRECT_PURCHASE.name()))
            .thenReturn(ProcurementMethodType.DIRECT_PURCHASE);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest));
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(activeSupplier(supplierId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseRequestId
        )).thenReturn(List.of(item));

        assertThatThrownBy(() -> service.createPurchaseOrder(
            awardId,
            new DirectAwardPurchaseOrderRequest(LocalDate.now(), LocalDate.now().plusDays(7), null)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage(ProcurementLineValidationService.GOODS_PO_ITEM_REQUIRES_MATERIAL);

        verify(purchaseOrderService, never()).create(any(PurchaseOrderCreateRequest.class));
    }

    private AdvanceShippingNoticeItem createSupplierAsnFromCommitment(
        UUID purchaseOrderId,
        UUID supplierId,
        UUID materialId
    ) {
        AdvanceShippingNoticeRepository asnRepository = mock(AdvanceShippingNoticeRepository.class);
        AdvanceShippingNoticeItemRepository asnItemRepository = mock(AdvanceShippingNoticeItemRepository.class);
        AdvanceShippingNoticeBatchRepository asnBatchRepository = mock(AdvanceShippingNoticeBatchRepository.class);
        AdvanceShippingNoticeSerialRepository asnSerialRepository = mock(AdvanceShippingNoticeSerialRepository.class);
        SupplierPoCommitmentRepository commitmentRepository = mock(SupplierPoCommitmentRepository.class);
        PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        SupplierPoAsnService asnService = new SupplierPoAsnService(
            asnRepository,
            asnItemRepository,
            asnBatchRepository,
            asnSerialRepository,
            commitmentRepository,
            purchaseOrderRepository,
            mock(IdempotencyService.class),
            mock(AuditService.class),
            mock(NotificationService.class)
        );
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId, supplierId);
        SupplierPoCommitment commitment = commitment(purchaseOrderId, supplierId, materialId);
        AtomicReference<AdvanceShippingNoticeItem> savedItem = new AtomicReference<>();

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(asnRepository.save(any(AdvanceShippingNotice.class))).thenAnswer(invocation -> {
            AdvanceShippingNotice asn = invocation.getArgument(0);
            if (asn.getId() == null) {
                asn.setId(UUID.randomUUID());
            }
            return asn;
        });
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));
        when(asnItemRepository.sumActiveQuantityForCommitment(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(asnItemRepository.save(any(AdvanceShippingNoticeItem.class))).thenAnswer(invocation -> {
            AdvanceShippingNoticeItem item = invocation.getArgument(0);
            savedItem.set(item);
            return item;
        });
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of())
            .thenAnswer(invocation -> savedItem.get() == null ? List.of() : List.of(savedItem.get()));
        when(asnRepository.findByIdAndIsDeletedFalse(any())).thenAnswer(invocation -> {
            AdvanceShippingNotice asn = new AdvanceShippingNotice();
            asn.setId(invocation.getArgument(0));
            asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
            asn.setTotalQuantity(new BigDecimal("5"));
            return Optional.of(asn);
        });

        asnService.createDraft(
            purchaseOrderId,
            supplierId,
            UUID.randomUUID(),
            new SupplierPoAsnService.AsnDraftCommand(
                null, null, null, null, null, null, null, null, null,
                List.of(new SupplierPoAsnService.AsnLineCommand(commitment.getId(), new BigDecimal("5"), null, null))
            )
        );

        return savedItem.get();
    }

    private ProcurementProcedure directAwardProcedure(UUID procedureId, UUID purchaseRequestId) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(procedureId);
        procedure.setTenantId(TenantContext.currentTenant());
        procedure.setCompanyId(CompanyContext.currentCompany());
        procedure.setCode("PROC-DA-001");
        procedure.setName("Direct award procedure");
        procedure.setProcedureNumber("PROC-DA-001");
        procedure.setStatus(ProcurementProcedureStatus.DIRECT_AWARD_DRAFT.name());
        procedure.setMethod(ProcurementMethodType.DIRECT_PURCHASE.name());
        procedure.setExecutionPath(ProcurementExecutionPath.DIRECT_AWARD.name());
        procedure.setMethodDecisionId(UUID.randomUUID());
        procedure.setPurchaseRequestId(purchaseRequestId);
        return procedure;
    }

    private Supplier activeSupplier(UUID supplierId) {
        Supplier supplier = new Supplier();
        supplier.setId(supplierId);
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(CompanyContext.currentCompany());
        supplier.setCode("SUP-001");
        supplier.setName("Active supplier");
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private DirectAwardRequest governedRequest(
        UUID supplierId,
        ProcurementMethodType method,
        String marketPriceEvidenceReference
    ) {
        return new DirectAwardRequest(
            supplierId,
            new BigDecimal("1200.00"),
            "UZS",
            "Documented business need supports this governed supplier selection",
            method == ProcurementMethodType.DIRECT_PURCHASE ? "MARKET_RESEARCH" : "EXCLUSIVITY",
            null,
            "Documented evidence supports the selected non-competitive method",
            null,
            "EVIDENCE-2026-001",
            new BigDecimal("1250.00"),
            marketPriceEvidenceReference,
            method == ProcurementMethodType.SINGLE_SOURCE
                ? "Supplier accepted the documented negotiated price and delivery terms"
                : null,
            "No buyer or approver conflict exists for this supplier selection",
            true
        );
    }

    private ProcurementDirectAward approvedAward(
        UUID awardId,
        UUID procedureId,
        UUID purchaseRequestId,
        UUID supplierId
    ) {
        ProcurementDirectAward award = new ProcurementDirectAward();
        award.setId(awardId);
        award.setTenantId(TenantContext.currentTenant());
        award.setCompanyId(CompanyContext.currentCompany());
        award.setStatus("APPROVED");
        award.setProcurementProcedureId(procedureId);
        award.setPurchaseRequestId(purchaseRequestId);
        award.setSupplierId(supplierId);
        award.setAwardAmount(new BigDecimal("1000.00"));
        award.setCurrencyCode("UZS");
        award.setCode("PDA-001");
        return award;
    }

    private PurchaseRequest purchaseRequest(UUID purchaseRequestId, RequestType requestType) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(purchaseRequestId);
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(CompanyContext.currentCompany());
        request.setName("Goods request");
        request.setRequestType(requestType);
        request.setCurrencyCode("UZS");
        return request;
    }

    private PurchaseOrder purchaseOrder(UUID purchaseOrderId, UUID supplierId) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(CompanyContext.currentCompany());
        purchaseOrder.setCode("PO-DA-001");
        purchaseOrder.setName("Direct award purchase order");
        purchaseOrder.setStatus("SENT");
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        return purchaseOrder;
    }

    private SupplierPoCommitment commitment(UUID purchaseOrderId, UUID supplierId, UUID materialId) {
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setTenantId(TenantContext.currentTenant());
        commitment.setCompanyId(CompanyContext.currentCompany());
        commitment.setCode("COMMIT-DA-001");
        commitment.setStatus("CONFIRMED");
        commitment.setPurchaseOrderId(purchaseOrderId);
        commitment.setSupplierId(supplierId);
        commitment.setConfirmedQuantity(new BigDecimal("5"));
        commitment.setCurrent(true);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderId(purchaseOrderId);
        item.setMaterialId(materialId);
        item.setUnit("EA");
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();
        unit.setId(UUID.randomUUID());
        unit.setCode("EA");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);
        var material = new com.enterprise.mm.entity.Material();
        material.setId(materialId);
        material.setMaterialType(com.enterprise.mm.enums.MaterialType.TMB);
        material.setUnitOfMeasureId(unit.getId());
        material.setUnitOfMeasure(unit);
        item.setMaterial(material);
        commitment.setPurchaseOrderItem(item);
        commitment.setPurchaseOrderItemId(item.getId());
        return commitment;
    }

    private PurchaseRequestItem purchaseRequestItem(
        UUID purchaseRequestId,
        ProcurementLineType lineType,
        UUID materialId
    ) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(TenantContext.currentTenant());
        item.setCompanyId(CompanyContext.currentCompany());
        item.setName("Goods request line");
        item.setPurchaseRequestId(purchaseRequestId);
        item.setLineType(lineType);
        item.setMaterialId(materialId);
        item.setQuantity(BigDecimal.ONE);
        item.setEstimatedPrice(new BigDecimal("1000.00"));
        return item;
    }
}
