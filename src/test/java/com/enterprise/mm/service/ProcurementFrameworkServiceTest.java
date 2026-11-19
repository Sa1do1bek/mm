package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementFramework;
import com.enterprise.mm.entity.ProcurementFrameworkLedgerEntry;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementFrameworkLedgerType;
import com.enterprise.mm.enums.ProcurementFrameworkStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ContractResponse;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.CallOffRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.CreateRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.ReversalRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.repository.ProcurementFrameworkLedgerRepository;
import com.enterprise.mm.repository.ProcurementFrameworkRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementFrameworkApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcurementFrameworkServiceTest {
    @Mock private ProcurementFrameworkRepository repository;
    @Mock private ProcurementFrameworkLedgerRepository ledgerRepository;
    @Mock private ProcurementAwardOutcomeRepository outcomeRepository;
    @Mock private ProcurementProcedureRepository procedureRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProcurementMethodPolicy methodPolicy;
    @Mock private ProcurementMethodCapabilityService capabilityService;
    @Mock private ProcurementAwardPurchaseOrderService awardPurchaseOrderService;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private ContractService contractService;
    @Mock private ContractSpecificationService contractSpecificationService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private ProcurementAwardOutcomeLineService outcomeLineService;
    @Mock private AuditService auditService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private SupplierEligibilityService supplierEligibilityService;

    private final List<ProcurementFrameworkLedgerEntry> ledger = new ArrayList<>();
    private ProcurementFrameworkService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new ProcurementFrameworkService(
            repository,
            ledgerRepository,
            outcomeRepository,
            procedureRepository,
            purchaseRequestRepository,
            purchaseRequestItemRepository,
            supplierRepository,
            methodPolicy,
            capabilityService,
            awardPurchaseOrderService,
            purchaseOrderService,
            contractService,
            contractSpecificationService,
            approvalEngineService,
            outcomeLineService,
            auditService,
            integrationEventService,
            supplierEligibilityService
        );
        when(methodPolicy.require(ProcurementMethodType.FRAME_AGREEMENT.name()))
            .thenReturn(ProcurementMethodType.FRAME_AGREEMENT);
        when(ledgerRepository.findByTenantIdAndCompanyIdAndFrameworkIdAndIsDeletedFalseOrderByOccurredAtAsc(
            eq("default"), eq(CompanyContext.DEFAULT_COMPANY_ID), any(UUID.class)))
            .thenAnswer(invocation -> ledger.stream()
                .filter(entry -> invocation.<UUID>getArgument(2).equals(entry.getFrameworkId()))
                .toList());
        when(ledgerRepository.findByTenantIdAndCompanyIdAndFrameworkIdAndIdempotencyKeyAndIsDeletedFalseOrderByOccurredAtAsc(
            eq("default"), eq(CompanyContext.DEFAULT_COMPANY_ID), any(UUID.class), any(String.class)))
            .thenAnswer(invocation -> ledger.stream()
                .filter(entry -> invocation.<UUID>getArgument(2).equals(entry.getFrameworkId()))
                .filter(entry -> invocation.<String>getArgument(3).equals(entry.getIdempotencyKey()))
                .toList());
        when(ledgerRepository.save(any(ProcurementFrameworkLedgerEntry.class))).thenAnswer(invocation -> {
            ProcurementFrameworkLedgerEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            entry.setTenantId(TenantContext.currentTenant());
            entry.setCompanyId(CompanyContext.currentCompany());
            ledger.add(entry);
            return entry;
        });
        when(outcomeRepository.findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
            eq("default"), eq(CompanyContext.DEFAULT_COMPANY_ID), any(UUID.class), any(), any(String.class)))
            .thenReturn(Optional.empty());
        when(outcomeRepository.save(any(ProcurementAwardOutcome.class))).thenAnswer(invocation -> {
            ProcurementAwardOutcome outcome = invocation.getArgument(0);
            if (outcome.getId() == null) {
                outcome.setId(UUID.randomUUID());
            }
            outcome.setTenantId(TenantContext.currentTenant());
            outcome.setCompanyId(CompanyContext.currentCompany());
            return outcome;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createRequiresCapabilityAndKeepsFrameworkInDraftUntilApproval() {
        UUID procedureId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseRequestId, ProcurementProcedureStatus.FRAMEWORK_DRAFT);
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure));
        when(repository.lockScopedByProcedureId("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(activeSupplier(supplierId)));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest(purchaseRequestId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse("default", purchaseRequestId))
            .thenReturn(List.of(requestItem(purchaseRequestId)));
        UUID contractId = UUID.randomUUID();
        when(contractService.create(any())).thenReturn(contract(contractId));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> {
            ProcurementFramework framework = invocation.getArgument(0);
            framework.setId(UUID.randomUUID());
            framework.setTenantId("default");
            framework.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
            return framework;
        });

        var response = service.create(new CreateRequest(
            procedureId,
            procedure.getMethodDecisionId(),
            purchaseRequestId,
            supplierId,
            null,
            "SINGLE_SUPPLIER",
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(30),
            "UZS",
            new BigDecimal("1000.00"),
            null,
            "FW-AWARD-1",
            "CON-1"
        ));

        assertThat(response.status()).isEqualTo(ProcurementFrameworkStatus.DRAFT);
        assertThat(response.contractId()).isEqualTo(contractId);
        verify(capabilityService).requireAvailableForCompany(
            ProcurementMethodType.FRAME_AGREEMENT.name(),
            new BigDecimal("1000.00"),
            "UZS",
            RequestType.GOODS.name(),
            null,
            CompanyContext.DEFAULT_COMPANY_ID
        );
        verify(approvalEngineService, never()).start(any(), any());
    }

    @Test
    void draftFrameworkAwardCanBeUpdatedWithoutCreatingSecondFramework() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        UUID newSupplierId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseRequestId, ProcurementProcedureStatus.FRAMEWORK_DRAFT);
        ProcurementFramework existing = framework(UUID.randomUUID(), ProcurementFrameworkStatus.DRAFT, new BigDecimal("500.00"));
        existing.setProcedureId(procedureId);
        existing.setPurchaseRequestId(purchaseRequestId);
        existing.setMethodDecisionId(procedure.getMethodDecisionId());
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure));
        when(repository.lockScopedByProcedureId("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(existing));
        when(supplierRepository.findByIdAndIsDeletedFalse(newSupplierId)).thenReturn(Optional.of(activeSupplier(newSupplierId)));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId))
            .thenReturn(Optional.of(purchaseRequest(purchaseRequestId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse("default", purchaseRequestId))
            .thenReturn(List.of());
        when(contractService.get(existing.getContractId())).thenReturn(contract(existing.getContractId()));
        when(contractService.update(eq(existing.getContractId()), any())).thenReturn(contract(existing.getContractId()));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(procedureRepository.save(any(ProcurementProcedure.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(new CreateRequest(
            procedureId,
            procedure.getMethodDecisionId(),
            purchaseRequestId,
            newSupplierId,
            null,
            "SINGLE_SUPPLIER",
            null,
            LocalDate.now(),
            LocalDate.now().plusMonths(6),
            "UZS",
            new BigDecimal("100000000.00"),
            null,
            "FW-AWARD-UPDATED",
            "FA-UPDATED"
        ));

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.ceilingAmount()).isEqualByComparingTo("100000000.00");
        assertThat(response.supplierId()).isEqualTo(newSupplierId);
        assertThat(response.awardReference()).isEqualTo("FW-AWARD-UPDATED");
        assertThat(procedure.getAwardedSupplierId()).isEqualTo(newSupplierId);
        assertThat(procedure.getAwardReference()).isEqualTo("FW-AWARD-UPDATED");
        verify(contractService).update(eq(existing.getContractId()), any());
        verify(contractService, never()).create(any());
    }

    @Test
    void approvedFrameworkAwardCannotBeUpdated() {
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseRequestId, ProcurementProcedureStatus.FRAMEWORK_AWARDED);
        ProcurementFramework existing = framework(UUID.randomUUID(), ProcurementFrameworkStatus.APPROVED, new BigDecimal("500.00"));
        existing.setProcedureId(procedureId);
        existing.setPurchaseRequestId(purchaseRequestId);
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure));
        when(repository.lockScopedByProcedureId("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateRequest(
            procedureId,
            procedure.getMethodDecisionId(),
            purchaseRequestId,
            existing.getSupplierId(),
            null,
            "SINGLE_SUPPLIER",
            null,
            existing.getValidFrom(),
            existing.getValidTo(),
            existing.getCurrencyCode(),
            new BigDecimal("900.00"),
            null,
            existing.getAwardReference(),
            null
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Framework award can only be updated while DRAFT");
        verify(contractService, never()).create(any());
        verify(contractService, never()).update(any(), any());
    }

    @Test
    void getByProcedureReturnsFrameworkForProcedure() {
        UUID procedureId = UUID.randomUUID();
        ProcurementFramework existing = framework(UUID.randomUUID(), ProcurementFrameworkStatus.DRAFT, new BigDecimal("500.00"));
        existing.setProcedureId(procedureId);
        when(repository.findScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.empty());
        when(repository.findScopedByProcedureId("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(existing));

        var response = service.getByProcedure(procedureId);

        assertThat(response.id()).isEqualTo(procedureId);
        assertThat(response.procedureId()).isEqualTo(procedureId);
        assertThat(response.status()).isEqualTo(ProcurementFrameworkStatus.DRAFT);
    }

    @Test
    void getAcceptsProcedureIdWhenUiPassesProcedureAsFrameworkPathId() {
        UUID procedureId = UUID.randomUUID();
        UUID frameworkId = UUID.randomUUID();
        ProcurementFramework existing = framework(frameworkId, ProcurementFrameworkStatus.DRAFT, new BigDecimal("500.00"));
        existing.setProcedureId(procedureId);
        when(repository.findScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.empty());
        when(repository.findScopedByProcedureId("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(existing));

        var response = service.get(procedureId);

        assertThat(response.id()).isEqualTo(procedureId);
        assertThat(response.procedureId()).isEqualTo(procedureId);
        assertThat(response.ceilingAmount()).isEqualByComparingTo("500.00");
        assertThat(response.awardReference()).isEqualTo("FW-AWARD");
    }

    @Test
    void multiSupplierFrameworkRequiresRankedMembershipEvidence() {
        UUID procedureId = UUID.randomUUID();
        UUID primarySupplierId = UUID.randomUUID();
        UUID secondarySupplierId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure(procedureId, purchaseRequestId, ProcurementProcedureStatus.FRAMEWORK_DRAFT);
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure));
        when(repository.lockScopedByProcedureId("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndIsDeletedFalse(primarySupplierId)).thenReturn(Optional.of(activeSupplier(primarySupplierId)));
        when(supplierRepository.findByIdAndIsDeletedFalse(secondarySupplierId)).thenReturn(Optional.of(activeSupplier(secondarySupplierId)));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest(purchaseRequestId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse("default", purchaseRequestId))
            .thenReturn(List.of(requestItem(purchaseRequestId)));
        UUID contractId = UUID.randomUUID();
        when(contractService.create(any())).thenReturn(contract(contractId));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> {
            ProcurementFramework framework = invocation.getArgument(0);
            framework.setId(UUID.randomUUID());
            framework.setTenantId("default");
            framework.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
            return framework;
        });

        var response = service.create(new CreateRequest(
            procedureId,
            procedure.getMethodDecisionId(),
            purchaseRequestId,
            primarySupplierId,
            List.of(primarySupplierId, secondarySupplierId),
            "MULTI_SUPPLIER",
            "RANKED_SUPPLIERS",
            LocalDate.now(),
            LocalDate.now().plusDays(30),
            "UZS",
            new BigDecimal("1000.00"),
            null,
            "FW-MULTI",
            null
        ));

        assertThat(response.supplierModel()).isEqualTo("MULTI_SUPPLIER");
        assertThat(response.allocationBasis()).isEqualTo("RANKED_SUPPLIERS");
        assertThat(response.supplierIds()).containsExactly(primarySupplierId, secondarySupplierId);
    }

    @Test
    void submitUsesApprovalEngineAndActivationRequiresApprovedStatus() {
        UUID frameworkId = UUID.randomUUID();
        ProcurementFramework draft = framework(frameworkId, ProcurementFrameworkStatus.DRAFT, new BigDecimal("1000.00"));
        when(repository.findScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(draft));
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(draft));

        service.submit(frameworkId);

        verify(approvalEngineService).start(ProcurementFrameworkApprovalAdapter.MODULE_KEY, frameworkId);
        assertThatThrownBy(() -> service.activate(frameworkId))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Framework must be APPROVED before activation");
    }

    @Test
    void activationRequiresApprovalAndActivatesContractAndProcedure() {
        UUID frameworkId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementFramework approved = framework(frameworkId, ProcurementFrameworkStatus.APPROVED, new BigDecimal("1000.00"));
        approved.setProcedureId(procedureId);
        approved.setPurchaseRequestId(purchaseRequestId);
        ProcurementProcedure procedure = procedure(procedureId, purchaseRequestId, ProcurementProcedureStatus.FRAMEWORK_AWARDED);
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(approved));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)).thenReturn(Optional.of(purchaseRequest(purchaseRequestId)));
        when(purchaseRequestItemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse("default", purchaseRequestId))
            .thenReturn(List.of(requestItem(purchaseRequestId)));
        when(supplierRepository.findByIdAndIsDeletedFalse(approved.getSupplierId()))
            .thenReturn(Optional.of(activeSupplier(approved.getSupplierId())));

        var response = service.activate(frameworkId);

        assertThat(response.status()).isEqualTo(ProcurementFrameworkStatus.ACTIVE);
        assertThat(procedure.getStatus()).isEqualTo(ProcurementProcedureStatus.FRAMEWORK_ACTIVE.name());
        verify(contractService).activate(approved.getContractId());
        verify(procedureRepository).save(procedure);
    }

    @Test
    void callOffUsesLedgerCapacityAndRejectsOverCeiling() {
        UUID frameworkId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        ProcurementFramework active = framework(frameworkId, ProcurementFrameworkStatus.ACTIVE, new BigDecimal("100.00"));
        active.setProcedureId(procedureId);
        active.setPurchaseRequestId(UUID.randomUUID());
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(active));
        when(repository.findScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(active));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(supplierRepository.findByIdAndIsDeletedFalse(active.getSupplierId())).thenReturn(Optional.of(activeSupplier(active.getSupplierId())));
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure(procedureId, active.getPurchaseRequestId(), ProcurementProcedureStatus.FRAMEWORK_ACTIVE)));
        PurchaseOrderResponse purchaseOrder = purchaseOrder(UUID.randomUUID());
        when(awardPurchaseOrderService.create(any(), eq(active.getSupplierId()), eq(new BigDecimal("60.00")),
            eq("UZS"), eq(active.getContractId()), any(), any(), any())).thenReturn(purchaseOrder);

        service.callOff(frameworkId, new CallOffRequest(
            new BigDecimal("60.00"),
            "UZS",
            "calloff-1",
            "CO-1",
            active.getSupplierId(),
            active.getPurchaseRequestId(),
            procedureId,
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(7),
            null
        ));

        assertThat(service.remainingCapacity(frameworkId)).isEqualByComparingTo("40.00");
        assertThat(ledger).extracting(ProcurementFrameworkLedgerEntry::getTransactionType)
            .containsExactly(ProcurementFrameworkLedgerType.COMMIT, ProcurementFrameworkLedgerType.CONSUME);
        assertThatThrownBy(() -> service.callOff(frameworkId, new CallOffRequest(
            new BigDecimal("41.00"),
            "UZS",
            "calloff-2",
            "CO-2",
            active.getSupplierId(),
            active.getPurchaseRequestId(),
            procedureId,
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(7),
            null
        ))).isInstanceOf(BusinessException.class)
            .hasMessage("Framework ceiling exceeded");
    }

    @Test
    void duplicateCallOffRetryReturnsExistingPurchaseOrderWithoutPostingAgain() {
        UUID frameworkId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementFramework active = framework(frameworkId, ProcurementFrameworkStatus.ACTIVE, new BigDecimal("100.00"));
        ledger.add(ledgerEntry(frameworkId, ProcurementFrameworkLedgerType.COMMIT, "same-key", purchaseOrderId, new BigDecimal("20.00")));
        ledger.add(ledgerEntry(frameworkId, ProcurementFrameworkLedgerType.CONSUME, "same-key", purchaseOrderId, new BigDecimal("20.00")));
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(active));
        when(supplierRepository.findByIdAndIsDeletedFalse(active.getSupplierId())).thenReturn(Optional.of(activeSupplier(active.getSupplierId())));
        when(purchaseOrderService.get(purchaseOrderId)).thenReturn(purchaseOrder(purchaseOrderId));

        PurchaseOrderResponse response = service.callOff(frameworkId, new CallOffRequest(
            new BigDecimal("20.00"),
            "UZS",
            "same-key",
            "CO-1",
            active.getSupplierId(),
            active.getPurchaseRequestId(),
            active.getProcedureId(),
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(7),
            null
        ));

        assertThat(response.getId()).isEqualTo(purchaseOrderId);
        assertThat(ledger).hasSize(2);
        verify(awardPurchaseOrderService, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rankedMultiSupplierCallOffUsesSelectedSupplierForPoAndOutcome() {
        UUID frameworkId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        UUID primarySupplierId = UUID.randomUUID();
        UUID secondarySupplierId = UUID.randomUUID();
        ProcurementFramework active = framework(frameworkId, ProcurementFrameworkStatus.ACTIVE, new BigDecimal("100.00"));
        active.setProcedureId(procedureId);
        active.setSupplierId(primarySupplierId);
        active.setSupplierIds(primarySupplierId + "," + secondarySupplierId);
        active.setSupplierModel("MULTI_SUPPLIER");
        active.setAllocationBasis("RANKED_SUPPLIERS");
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(active));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(supplierRepository.findByIdAndIsDeletedFalse(secondarySupplierId))
            .thenReturn(Optional.of(activeSupplier(secondarySupplierId)));
        when(procedureRepository.lockScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, procedureId))
            .thenReturn(Optional.of(procedure(procedureId, active.getPurchaseRequestId(), ProcurementProcedureStatus.FRAMEWORK_ACTIVE)));
        PurchaseOrderResponse purchaseOrder = purchaseOrder(UUID.randomUUID());
        when(awardPurchaseOrderService.create(any(), eq(secondarySupplierId), eq(new BigDecimal("30.00")),
            eq("UZS"), eq(active.getContractId()), any(), any(), any())).thenReturn(purchaseOrder);

        service.callOff(frameworkId, new CallOffRequest(
            new BigDecimal("30.00"),
            "UZS",
            "ranked-secondary",
            "CO-RANKED",
            secondarySupplierId,
            active.getPurchaseRequestId(),
            procedureId,
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(7),
            "RANKED_SUPPLIERS"
        ));

        verify(awardPurchaseOrderService).create(any(), eq(secondarySupplierId), eq(new BigDecimal("30.00")),
            eq("UZS"), eq(active.getContractId()), any(), any(), any());
        ArgumentCaptor<ProcurementAwardOutcome> outcomeCaptor = ArgumentCaptor.forClass(ProcurementAwardOutcome.class);
        verify(outcomeRepository).save(outcomeCaptor.capture());
        assertThat(outcomeCaptor.getValue().getAwardedSupplierId()).isEqualTo(secondarySupplierId);
    }

    @Test
    void reversalRestoresCapacityExactlyOnce() {
        UUID frameworkId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        ProcurementFramework active = framework(frameworkId, ProcurementFrameworkStatus.ACTIVE, new BigDecimal("100.00"));
        ledger.add(ledgerEntry(frameworkId, ProcurementFrameworkLedgerType.COMMIT, "reverse-me", purchaseOrderId, new BigDecimal("25.00")));
        ledger.add(ledgerEntry(frameworkId, ProcurementFrameworkLedgerType.CONSUME, "reverse-me", purchaseOrderId, new BigDecimal("25.00")));
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(active));
        when(repository.findScopedById("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(active));
        when(repository.save(any(ProcurementFramework.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reverseCallOff(frameworkId, new ReversalRequest("reverse-me", "cancelled"));
        service.reverseCallOff(frameworkId, new ReversalRequest("reverse-me", "cancelled-again"));

        assertThat(service.remainingCapacity(frameworkId)).isEqualByComparingTo("100.00");
        assertThat(ledger).extracting(ProcurementFrameworkLedgerEntry::getTransactionType)
            .containsExactly(
                ProcurementFrameworkLedgerType.COMMIT,
                ProcurementFrameworkLedgerType.CONSUME,
                ProcurementFrameworkLedgerType.COMMIT_REVERSAL,
                ProcurementFrameworkLedgerType.CONSUME_REVERSAL
            );
    }

    @Test
    void suspendedExpiredAndArbitraryMultiSupplierSelectionCannotCreateCallOff() {
        UUID frameworkId = UUID.randomUUID();
        ProcurementFramework suspended = framework(frameworkId, ProcurementFrameworkStatus.SUSPENDED, new BigDecimal("100.00"));
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.callOff(frameworkId, callOff(suspended, "suspended")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Framework status does not allow new call-offs: SUSPENDED");

        ProcurementFramework activeMulti = framework(frameworkId, ProcurementFrameworkStatus.ACTIVE, new BigDecimal("100.00"));
        activeMulti.setSupplierModel("MULTI_SUPPLIER");
        when(repository.lockScoped("default", CompanyContext.DEFAULT_COMPANY_ID, frameworkId))
            .thenReturn(Optional.of(activeMulti));
        when(supplierRepository.findByIdAndIsDeletedFalse(activeMulti.getSupplierId()))
            .thenReturn(Optional.of(activeSupplier(activeMulti.getSupplierId())));

        assertThatThrownBy(() -> service.callOff(frameworkId, callOff(activeMulti, "multi")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Multi-supplier call-off requires recorded RANKED_SUPPLIERS allocation basis");
    }

    private ProcurementProcedure procedure(UUID id, UUID purchaseRequestId, ProcurementProcedureStatus status) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(id);
        procedure.setTenantId("default");
        procedure.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        procedure.setCode("PROC-" + id.toString().substring(0, 8));
        procedure.setName("Framework procedure");
        procedure.setStatus(status.name());
        procedure.setProcedureNumber("PROC-FW");
        procedure.setMethodDecisionId(UUID.randomUUID());
        procedure.setPurchaseRequestId(purchaseRequestId);
        procedure.setMethod(ProcurementMethodType.FRAME_AGREEMENT.name());
        procedure.setExecutionPath(ProcurementExecutionPath.FRAMEWORK_AGREEMENT.name());
        return procedure;
    }

    private ProcurementFramework framework(UUID id, ProcurementFrameworkStatus status, BigDecimal ceiling) {
        ProcurementFramework framework = new ProcurementFramework();
        framework.setId(id);
        framework.setTenantId("default");
        framework.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        framework.setCode("FW-" + id.toString().substring(0, 8));
        framework.setName("Framework");
        framework.setFrameworkStatus(status);
        framework.setProcedureId(UUID.randomUUID());
        framework.setMethodDecisionId(UUID.randomUUID());
        framework.setPurchaseRequestId(UUID.randomUUID());
        framework.setContractId(UUID.randomUUID());
        framework.setSupplierId(UUID.randomUUID());
        framework.setSupplierModel("SINGLE_SUPPLIER");
        framework.setValidFrom(LocalDate.now().minusDays(1));
        framework.setValidTo(LocalDate.now().plusDays(30));
        framework.setCurrencyCode("UZS");
        framework.setCeilingAmount(ceiling);
        framework.setCommittedAmount(BigDecimal.ZERO);
        framework.setConsumedAmount(BigDecimal.ZERO);
        framework.setAwardReference("FW-AWARD");
        return framework;
    }

    private Supplier activeSupplier(UUID id) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId("default");
        supplier.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        supplier.setCode("SUP");
        supplier.setName("Supplier");
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private PurchaseRequest purchaseRequest(UUID id) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(id);
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setCode("PR");
        request.setName("Purchase request");
        request.setStatus("APPROVED");
        request.setRequestType(RequestType.GOODS);
        request.setCurrencyCode("UZS");
        return request;
    }

    private PurchaseRequestItem requestItem(UUID purchaseRequestId) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setCode("PRI");
        item.setName("Line");
        item.setPurchaseRequestId(purchaseRequestId);
        item.setQuantity(BigDecimal.ONE);
        item.setEstimatedPrice(BigDecimal.TEN);
        item.setUnit("pcs");
        item.setMaterialId(UUID.randomUUID());
        return item;
    }

    private ContractResponse contract(UUID id) {
        ContractResponse response = new ContractResponse();
        response.setId(id);
        response.setCode("FA-" + id.toString().substring(0, 8));
        response.setName("Framework contract");
        response.setStatus("DRAFT");
        response.setCurrencyCode("UZS");
        response.setLimitAmount(new BigDecimal("500.00"));
        response.setUsedAmount(BigDecimal.ZERO);
        response.setServiceUsedAmount(BigDecimal.ZERO);
        response.setContractNumber("CON-1");
        return response;
    }

    private PurchaseOrderResponse purchaseOrder(UUID id) {
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        response.setId(id);
        response.setCurrencyCode("UZS");
        return response;
    }

    private ProcurementFrameworkLedgerEntry ledgerEntry(
        UUID frameworkId,
        ProcurementFrameworkLedgerType type,
        String key,
        UUID purchaseOrderId,
        BigDecimal amount
    ) {
        ProcurementFrameworkLedgerEntry entry = new ProcurementFrameworkLedgerEntry();
        entry.setId(UUID.randomUUID());
        entry.setTenantId("default");
        entry.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entry.setFrameworkId(frameworkId);
        entry.setTransactionType(type);
        entry.setIdempotencyKey(key);
        entry.setPurchaseOrderId(purchaseOrderId);
        entry.setAmount(amount);
        entry.setCurrencyCode("UZS");
        entry.setCallOffReference("CO");
        return entry;
    }

    private CallOffRequest callOff(ProcurementFramework framework, String key) {
        return new CallOffRequest(
            BigDecimal.TEN,
            framework.getCurrencyCode(),
            key,
            "CO-" + key,
            framework.getSupplierId(),
            framework.getPurchaseRequestId(),
            framework.getProcedureId(),
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(7),
            null
        );
    }
}
