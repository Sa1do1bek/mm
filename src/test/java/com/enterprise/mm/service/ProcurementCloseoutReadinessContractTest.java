package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.QualityClaimStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.ServiceClaimStatus;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.BudgetTransactionRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementCloseoutRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.QualityClaimRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.payload.BudgetLedgerReconciliationResult;
import java.math.BigDecimal;
import java.util.Arrays;
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
class ProcurementCloseoutReadinessContractTest {
    @Mock private ProcurementProcedureRepository procedureRepository;
    @Mock private ProcurementAwardOutcomeRepository awardOutcomeRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private QualityClaimRepository qualityClaimRepository;
    @Mock private ServiceClaimRepository serviceClaimRepository;
    @Mock private ProcurementCloseoutAuditHistoryService closeoutAuditHistoryService;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private ContractInvoiceRepository contractInvoiceRepository;
    @Mock private ContractPaymentRepository contractPaymentRepository;
    @Mock private BudgetTransactionRepository budgetTransactionRepository;
    @Mock private ProcurementCloseoutRepository closeoutRepository;
    @Mock private ApprovalTaskRepository approvalTaskRepository;
    @Mock private BudgetItemService budgetItemService;
    @Mock private AssetAcceptanceRepository assetAcceptanceRepository;
    @Mock private AssetAcceptanceLineRepository assetAcceptanceLineRepository;
    @Mock private AssetAcceptanceUnitRepository assetAcceptanceUnitRepository;
    @InjectMocks private ProcurementCloseoutReadinessService service;

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
    void marksGoodsProcedureReadyOnlyWhenAwardPoInvoicePaymentAndBudgetEvidenceAreSettled() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem purchaseOrderItem = new PurchaseOrderItem();
        purchaseOrderItem.setId(UUID.randomUUID());
        purchaseOrderItem.setBudgetItemId(budgetItemId);
        purchaseOrderItem.setQuantity(BigDecimal.ONE);
        purchaseOrderItem.setReceivedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setAcceptedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setUnitPrice(new BigDecimal("10.00"));
        purchaseOrderItem.setQualityStatus(QualityInspectionStatus.RELEASED.name());
        ContractInvoice invoice = new ContractInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus("PAID");
        invoice.setAmount(new BigDecimal("100.00"));
        invoice.setPaidAmount(new BigDecimal("100.00"));
        ContractPayment payment = new ContractPayment();
        payment.setStatus("PAID");
        payment.setAmount(new BigDecimal("100.00"));
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        outcome.setAwardReference("AWARD-1");

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem));
        when(contractInvoiceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of(invoice));
        when(contractPaymentRepository.findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, invoice.getId())).thenReturn(List.of(payment));
        when(budgetTransactionRepository.sumOpenEncumbranceBySourceId(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(BigDecimal.ZERO);

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
    }

    @Test
    void blocksWhenAwardAndPurchaseOrderEvidenceAreMissing() {
        UUID procedureId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, null);
        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.empty());

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isFalse();
        assertThat(blockerCodes(readiness)).containsExactlyInAnyOrder(
            "MISSING_AWARD_OUTCOME",
            "MISSING_PO_OR_CONTRACT"
        );
    }

    @Test
    void blocksServicePurchaseOrderWithoutAnApprovedAcceptanceAct() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        purchaseOrder.setRequestType(com.enterprise.mm.enums.RequestType.SERVICE);
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of());
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isFalse();
        assertThat(blockerCodes(readiness)).contains(
            "SERVICE_ACT_PENDING"
        );
    }

    @Test
    void blocksGoodsLineWithoutTerminalQualityEvidence() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setQuantity(BigDecimal.ONE);
        item.setReceivedQuantity(BigDecimal.ONE);
        item.setAcceptedQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.TEN);
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(blockerCodes(readiness)).contains("QUALITY_INSPECTION_PENDING");
    }

    @Test
    void blocksGoodsCloseoutWhenPhysicalReceiptExceedsAcceptedFulfillment() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setQuantity(new BigDecimal("10.0000"));
        item.setReceivedQuantity(new BigDecimal("10.0000"));
        item.setAcceptedQuantity(new BigDecimal("4.0000"));
        item.setUnitPrice(BigDecimal.TEN);
        item.setQualityStatus(QualityInspectionStatus.RELEASED.name());
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isFalse();
        assertThat(blockerCodes(readiness)).contains("RECEIPT_PENDING");
    }

    @Test
    void fixedAssetCloseoutUsesPostedAssetAcceptanceInsteadOfPoAcceptedQuantityFallback() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem item = fixedAssetItem(purchaseOrderId, new BigDecimal("2.0000"));
        item.setReceivedQuantity(new BigDecimal("2.0000"));
        item.setAcceptedQuantity(new BigDecimal("2.0000"));
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        when(assetAcceptanceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED))
            .thenReturn(List.of());
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isFalse();
        assertThat(blockerCodes(readiness)).contains("RECEIPT_PENDING");
    }

    @Test
    void fixedAssetCloseoutAllowsUncommissionedAssetsWhenAcceptanceAndFinanceAreComplete() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem item = fixedAssetItem(purchaseOrderId, new BigDecimal("2.0000"));
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();
        AssetAcceptance acceptance = postedAssetAcceptance(purchaseOrderId);
        AssetAcceptanceLine line = assetAcceptanceLine(acceptance, item, new BigDecimal("2.0000"), BigDecimal.ZERO, BigDecimal.ZERO);

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        stubPostedAssetAcceptanceEvidence(purchaseOrderId, acceptance, line, List.of(
            assetAcceptanceUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
            assetAcceptanceUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID())
        ));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
    }

    @Test
    void fixedAssetCloseoutBlocksHeldOrRejectedAcceptanceUntilSupplierDispositionIsClosed() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem item = fixedAssetItem(purchaseOrderId, new BigDecimal("3.0000"));
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();
        AssetAcceptance acceptance = postedAssetAcceptance(purchaseOrderId);
        AssetAcceptanceLine line = assetAcceptanceLine(acceptance, item, new BigDecimal("3.0000"), BigDecimal.ONE, new BigDecimal("1.0000"));

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        stubPostedAssetAcceptanceEvidence(purchaseOrderId, acceptance, line, List.of(
            assetAcceptanceUnit(line, AssetAcceptanceUnitQualityDecision.ACCEPTED, UUID.randomUUID()),
            assetAcceptanceUnit(line, AssetAcceptanceUnitQualityDecision.HOLD, null),
            assetAcceptanceUnit(line, AssetAcceptanceUnitQualityDecision.REJECTED, null)
        ));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(readiness.ready()).isFalse();
        assertThat(blockerCodes(readiness)).contains("QUALITY_INSPECTION_PENDING", "QUALITY_REJECTION_UNRESOLVED");
    }

    @Test
    void blocksServiceCloseoutWhenLineClaimIsOpen() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        purchaseOrder.setRequestType(com.enterprise.mm.enums.RequestType.SERVICE);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setStatus("APPROVED");
        ServiceClaim claim = new ServiceClaim();
        claim.setId(UUID.randomUUID());
        claim.setStatus(ServiceClaimStatus.UNDER_RESOLUTION.name());
        ContractInvoice invoice = paidInvoice();

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(serviceAcceptanceActRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of(act));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId)).thenReturn(List.of(item));
        when(serviceClaimRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, item.getId())).thenReturn(List.of(claim));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var readiness = service.readiness(procedureId);

        assertThat(blockerCodes(readiness)).contains("OPEN_CLAIM", "SERVICE_EXECUTION_INCOMPLETE");
    }

    @Test
    void availableActionsFollowApprovalDrivenCloseoutContract() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem purchaseOrderItem = new PurchaseOrderItem();
        purchaseOrderItem.setId(UUID.randomUUID());
        purchaseOrderItem.setQuantity(BigDecimal.ONE);
        purchaseOrderItem.setReceivedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setAcceptedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setUnitPrice(new BigDecimal("10.00"));
        purchaseOrderItem.setQualityStatus(QualityInspectionStatus.RELEASED.name());
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();
        ProcurementCloseout closeout = closeout(UUID.randomUUID(), procedureId);
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.APPROVED);

        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(closeoutRepository.findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(closeout));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);

        var approvedActionKeys = actionKeys(service.readiness(procedureId));

        assertThat(approvedActionKeys).contains("FINALIZE_CLOSEOUT");
        assertThat(approvedActionKeys).doesNotContain("SUBMIT_CLOSEOUT", "APPLY_REOPEN");

        closeout.setCloseoutStatus(ProcurementCloseoutStatus.REOPEN_REQUESTED);
        var reopenRequestedActionKeys = actionKeys(service.readiness(procedureId));
        assertThat(reopenRequestedActionKeys).doesNotContain("APPLY_REOPEN");

        closeout.setCloseoutStatus(ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING);
        closeout.setApprovalInstanceId(UUID.randomUUID());
        var reopenApprovalActionKeys = actionKeys(service.readiness(procedureId));
        assertThat(reopenApprovalActionKeys).contains("VIEW_APPROVAL_TASK");
        assertThat(reopenApprovalActionKeys).doesNotContain("APPLY_REOPEN");
    }

    @Test
    void responseIncludesPendingApprovalTaskIdForLinkedApprovalInstance() throws Exception {
        assertThat(Arrays.stream(com.enterprise.mm.payload.ProcurementCloseoutDtos.Response.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .contains("approvalTaskId");

        UUID procedureId = UUID.randomUUID();
        UUID closeoutId = UUID.randomUUID();
        UUID approvalInstanceId = UUID.randomUUID();
        UUID approvalTaskId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, null);
        ProcurementCloseout closeout = closeout(closeoutId, procedureId);
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.APPROVAL_PENDING);
        closeout.setApprovalInstanceId(approvalInstanceId);
        ApprovalTask pendingTask = new ApprovalTask();
        pendingTask.setId(approvalTaskId);
        pendingTask.setApprovalInstanceId(approvalInstanceId);
        pendingTask.setTaskStatus(ApprovalTaskStatus.PENDING);

        when(closeoutRepository.findById(closeoutId)).thenReturn(Optional.of(closeout));
        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.empty());
        when(approvalTaskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
            "default", approvalInstanceId, ApprovalTaskStatus.PENDING)).thenReturn(Optional.of(pendingTask));

        var response = service.get(closeoutId);

        assertThat(response.getClass().getMethod("approvalTaskId").invoke(response)).isEqualTo(approvalTaskId);
    }

    @Test
    void rejectsFinalizationWhenAnInScopeBudgetLedgerDoesNotReconcile() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID closeoutId = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem purchaseOrderItem = new PurchaseOrderItem();
        purchaseOrderItem.setId(UUID.randomUUID());
        purchaseOrderItem.setBudgetItemId(budgetItemId);
        purchaseOrderItem.setQuantity(BigDecimal.ONE);
        purchaseOrderItem.setReceivedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setAcceptedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setUnitPrice(new BigDecimal("10.00"));
        purchaseOrderItem.setQualityStatus(QualityInspectionStatus.RELEASED.name());
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();
        ProcurementCloseout closeout = new ProcurementCloseout();
        closeout.setId(closeoutId);
        closeout.setTenantId("default");
        closeout.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        closeout.setProcurementProcedureId(procedureId);

        when(closeoutRepository.findByIdForUpdate(closeoutId)).thenReturn(Optional.of(closeout));
        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem));
        stubSettledFinancialEvidence(procedureId, purchaseOrderId, invoice);
        when(budgetItemService.reconcileLedger(budgetItemId)).thenReturn(new BudgetLedgerReconciliationResult(
            budgetItemId, false,
            new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        ));

        assertThatThrownBy(() -> service.finalize(closeoutId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("budget ledger discrepancy");
    }

    @Test
    void finalizeReversesRemainingPurchaseOrderCommitmentWithCloseoutActionKey() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID closeoutId = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();
        UUID purchaseOrderItemId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem purchaseOrderItem = new PurchaseOrderItem();
        purchaseOrderItem.setId(purchaseOrderItemId);
        purchaseOrderItem.setBudgetItemId(budgetItemId);
        purchaseOrderItem.setQuantity(new BigDecimal("10.0000"));
        purchaseOrderItem.setReceivedQuantity(new BigDecimal("6.0000"));
        purchaseOrderItem.setAcceptedQuantity(new BigDecimal("6.0000"));
        purchaseOrderItem.setDamagedQuantity(new BigDecimal("4.0000"));
        purchaseOrderItem.setUnitPrice(new BigDecimal("10.00"));
        purchaseOrderItem.setQualityStatus(QualityInspectionStatus.RELEASED.name());
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();
        ProcurementCloseout closeout = closeout(closeoutId, procedureId);

        when(closeoutRepository.findByIdForUpdate(closeoutId)).thenReturn(Optional.of(closeout));
        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem));
        when(qualityClaimRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of(closedQualityClaim(purchaseOrderId)));
        when(contractInvoiceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of(invoice));
        when(contractPaymentRepository.findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, invoice.getId())).thenReturn(List.of(paidPayment()));
        when(budgetTransactionRepository.sumOpenEncumbranceBySourceId(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(new BigDecimal("40.00"), BigDecimal.ZERO);
        when(budgetTransactionRepository.sumOpenEncumbranceBySourceKey(
            "default", CompanyContext.DEFAULT_COMPANY_ID, "PurchaseOrderItem:" + purchaseOrderItemId)).thenReturn(new BigDecimal("40.00"));
        when(budgetItemService.reconcileLedger(budgetItemId)).thenReturn(matchedLedger(budgetItemId));
        when(closeoutRepository.save(any(ProcurementCloseout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.finalize(closeoutId);

        assertThat(response.status()).isEqualTo(ProcurementCloseoutStatus.CLOSED);
        verify(budgetItemService).reverseCommitmentIdempotent(
            eq(budgetItemId),
            eq(new BigDecimal("40.000000")),
            eq("ProcurementCloseout PROC-CLOSEOUT-1"),
            eq("PurchaseOrder"),
            eq(purchaseOrderId),
            eq(purchaseOrderItemId),
            eq("PurchaseOrderItem:" + purchaseOrderItemId),
            eq("CLOSEOUT:" + closeoutId + ":REVERSE_PO:" + purchaseOrderId + ":0")
        );
    }

    @Test
    void repeatedFinalizationOfClosedCloseoutDoesNotMoveMoneyAgain() {
        UUID procedureId = UUID.randomUUID();
        UUID closeoutId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, null);
        ProcurementCloseout closeout = closeout(closeoutId, procedureId);
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.CLOSED);
        closeout.setStatus(ProcurementCloseoutStatus.CLOSED.name());

        when(closeoutRepository.findByIdForUpdate(closeoutId)).thenReturn(Optional.of(closeout));
        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.empty());

        var response = service.finalize(closeoutId);

        assertThat(response.status()).isEqualTo(ProcurementCloseoutStatus.CLOSED);
        verifyNoInteractions(budgetItemService);
        verify(closeoutRepository, never()).save(any(ProcurementCloseout.class));
    }

    @Test
    void finalizeReleasesOpenPurchaseRequestReservationWithCloseoutActionKey() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID closeoutId = UUID.randomUUID();
        UUID purchaseOrderItemId = UUID.randomUUID();
        UUID purchaseRequestItemId = UUID.randomUUID();
        UUID sourceBudgetItemId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseOrderId);
        procedure.setPurchaseRequestId(purchaseRequestId);
        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
        PurchaseOrderItem purchaseOrderItem = new PurchaseOrderItem();
        purchaseOrderItem.setId(purchaseOrderItemId);
        purchaseOrderItem.setQuantity(BigDecimal.ONE);
        purchaseOrderItem.setReceivedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setAcceptedQuantity(BigDecimal.ONE);
        purchaseOrderItem.setUnitPrice(new BigDecimal("30.00"));
        purchaseOrderItem.setQualityStatus(QualityInspectionStatus.RELEASED.name());
        PurchaseRequestItem sourceItem = new PurchaseRequestItem();
        sourceItem.setId(purchaseRequestItemId);
        sourceItem.setTenantId("default");
        sourceItem.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        sourceItem.setPurchaseRequestId(purchaseRequestId);
        sourceItem.setBudgetItemId(sourceBudgetItemId);
        sourceItem.setEstimatedPrice(new BigDecimal("15.00"));
        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setPurchaseOrderItemId(purchaseOrderItemId);
        allocation.setPurchaseRequestItemId(purchaseRequestItemId);
        allocation.setOrderedQuantity(new BigDecimal("2.0000"));
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setPurchaseOrderId(purchaseOrderId);
        ContractInvoice invoice = paidInvoice();
        ProcurementCloseout closeout = closeout(closeoutId, procedureId);

        when(closeoutRepository.findByIdForUpdate(closeoutId)).thenReturn(Optional.of(closeout));
        when(procedureRepository.findByIdAndIsDeletedFalse(procedureId)).thenReturn(Optional.of(procedure));
        when(awardOutcomeRepository.findFirstByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalseOrderByFinalizedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, procedureId)).thenReturn(Optional.of(outcome));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(purchaseOrderItem));
        when(purchaseOrderSourceAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, List.of(purchaseOrderItemId))).thenReturn(List.of(allocation));
        when(purchaseOrderSourceAllocationRepository.findByPurchaseOrderItemIdsForUpdate(
            "default", CompanyContext.DEFAULT_COMPANY_ID, List.of(purchaseOrderItemId))).thenReturn(List.of(allocation));
        when(purchaseRequestItemRepository.findAllByIdInForUpdate(any())).thenReturn(List.of(sourceItem));
        when(purchaseRequestItemRepository.findByIdAndIsDeletedFalse(purchaseRequestItemId)).thenReturn(Optional.of(sourceItem));
        when(contractInvoiceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of(invoice));
        when(contractPaymentRepository.findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, invoice.getId())).thenReturn(List.of(paidPayment()));
        when(budgetTransactionRepository.sumOpenEncumbranceBySourceId(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(BigDecimal.ZERO);
        when(budgetTransactionRepository.sumOpenEncumbranceBySourceKey(
            "default", CompanyContext.DEFAULT_COMPANY_ID, "PurchaseRequestItem:" + purchaseRequestItemId))
            .thenReturn(new BigDecimal("30.00"), new BigDecimal("30.00"), BigDecimal.ZERO);
        when(budgetItemService.reconcileLedger(sourceBudgetItemId)).thenReturn(matchedLedger(sourceBudgetItemId));
        when(closeoutRepository.save(any(ProcurementCloseout.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.finalize(closeoutId);

        assertThat(response.status()).isEqualTo(ProcurementCloseoutStatus.CLOSED);
        verify(budgetItemService).releaseIdempotent(
            eq(sourceBudgetItemId),
            eq(new BigDecimal("30.000000")),
            eq("ProcurementCloseout PROC-CLOSEOUT-1"),
            eq("PurchaseRequest"),
            eq(purchaseRequestId),
            eq(purchaseRequestItemId),
            eq("PurchaseRequestItem:" + purchaseRequestItemId),
            eq("CLOSEOUT:" + closeoutId + ":RELEASE_PR:" + purchaseRequestId + ":0")
        );
    }

    private ProcurementProcedure procedure(UUID procedureId, UUID purchaseOrderId) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(procedureId);
        procedure.setTenantId("default");
        procedure.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        procedure.setStatus("COMPLETED");
        procedure.setPurchaseOrderId(purchaseOrderId);
        return procedure;
    }

    private PurchaseOrder purchaseOrder(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("default");
        purchaseOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrder.setCode("PO-1");
        purchaseOrder.setStatus("CLOSED");
        purchaseOrder.setTotalAmount(new BigDecimal("100.00"));
        return purchaseOrder;
    }

    private PurchaseOrderItem fixedAssetItem(UUID purchaseOrderId, BigDecimal quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        item.setQuantity(quantity);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setAcceptedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(new BigDecimal("100.00"));
        return item;
    }

    private AssetAcceptance postedAssetAcceptance(UUID purchaseOrderId) {
        AssetAcceptance acceptance = new AssetAcceptance();
        acceptance.setId(UUID.randomUUID());
        acceptance.setTenantId("default");
        acceptance.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        acceptance.setPurchaseOrderId(purchaseOrderId);
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.APPROVED);
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.POSTED);
        return acceptance;
    }

    private AssetAcceptanceLine assetAcceptanceLine(
        AssetAcceptance acceptance,
        PurchaseOrderItem item,
        BigDecimal delivered,
        BigDecimal hold,
        BigDecimal rejected
    ) {
        AssetAcceptanceLine line = new AssetAcceptanceLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("default");
        line.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        line.setAssetAcceptanceId(acceptance.getId());
        line.setPurchaseOrderItemId(item.getId());
        line.setMaterialId(UUID.randomUUID());
        line.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        line.setDeliveredQuantity(delivered);
        line.setHoldQuantity(hold);
        line.setRejectedQuantity(rejected);
        return line;
    }

    private AssetAcceptanceUnit assetAcceptanceUnit(
        AssetAcceptanceLine line,
        AssetAcceptanceUnitQualityDecision decision,
        UUID postedFixedAssetId
    ) {
        AssetAcceptanceUnit unit = new AssetAcceptanceUnit();
        unit.setId(UUID.randomUUID());
        unit.setTenantId("default");
        unit.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        unit.setAssetAcceptanceLineId(line.getId());
        unit.setPurchaseOrderItemId(line.getPurchaseOrderItemId());
        unit.setMaterialId(line.getMaterialId());
        unit.setQualityDecision(decision);
        unit.setPostedFixedAssetId(postedFixedAssetId);
        return unit;
    }

    private void stubPostedAssetAcceptanceEvidence(
        UUID purchaseOrderId,
        AssetAcceptance acceptance,
        AssetAcceptanceLine line,
        List<AssetAcceptanceUnit> units
    ) {
        when(assetAcceptanceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, AssetAcceptanceApprovalStatus.APPROVED, AssetAcceptancePostingStatus.POSTED))
            .thenReturn(List.of(acceptance));
        when(assetAcceptanceLineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, acceptance.getId())).thenReturn(List.of(line));
        when(assetAcceptanceUnitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, line.getId())).thenReturn(units);
    }

    private ContractInvoice paidInvoice() {
        ContractInvoice invoice = new ContractInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setStatus("PAID");
        invoice.setAmount(new BigDecimal("100.00"));
        invoice.setPaidAmount(new BigDecimal("100.00"));
        return invoice;
    }

    private ContractPayment paidPayment() {
        ContractPayment payment = new ContractPayment();
        payment.setStatus("PAID");
        payment.setAmount(new BigDecimal("100.00"));
        return payment;
    }

    private ProcurementCloseout closeout(UUID closeoutId, UUID procedureId) {
        ProcurementCloseout closeout = new ProcurementCloseout();
        closeout.setId(closeoutId);
        closeout.setTenantId("default");
        closeout.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        closeout.setProcurementProcedureId(procedureId);
        closeout.setCode("PROC-CLOSEOUT-1");
        return closeout;
    }

    private BudgetLedgerReconciliationResult matchedLedger(UUID budgetItemId) {
        return new BudgetLedgerReconciliationResult(
            budgetItemId, true,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    private QualityClaim closedQualityClaim(UUID purchaseOrderId) {
        QualityClaim claim = new QualityClaim();
        claim.setId(UUID.randomUUID());
        claim.setTenantId("default");
        claim.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        claim.setPurchaseOrderId(purchaseOrderId);
        claim.setStatus(QualityClaimStatus.CLOSED.name());
        return claim;
    }

    private void stubSettledFinancialEvidence(UUID procedureId, UUID purchaseOrderId, ContractInvoice invoice) {
        ContractPayment payment = new ContractPayment();
        payment.setStatus("PAID");
        payment.setAmount(new BigDecimal("100.00"));
        when(contractInvoiceRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(List.of(invoice));
        when(contractPaymentRepository.findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, invoice.getId())).thenReturn(List.of(payment));
        when(budgetTransactionRepository.sumOpenEncumbranceBySourceId(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId)).thenReturn(BigDecimal.ZERO);
    }

    private List<String> blockerCodes(com.enterprise.mm.payload.ProcurementCloseoutDtos.Readiness readiness) {
        return readiness.blockers().stream().map(com.enterprise.mm.payload.ProcurementCloseoutDtos.Blocker::blockerCode).toList();
    }

    private List<String> actionKeys(com.enterprise.mm.payload.ProcurementCloseoutDtos.Readiness readiness) {
        return readiness.availableActions().stream().map(com.enterprise.mm.payload.ProcurementCloseoutDtos.Action::key).toList();
    }
}
