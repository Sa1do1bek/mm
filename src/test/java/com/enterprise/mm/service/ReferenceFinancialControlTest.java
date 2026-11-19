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
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.BudgetTransaction;
import com.enterprise.mm.entity.ChartOfAccount;
import com.enterprise.mm.entity.Classifier;
import com.enterprise.mm.entity.ClassifierType;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.DepartmentType;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.BudgetItemMapper;
import com.enterprise.mm.mapper.ChartOfAccountMapper;
import com.enterprise.mm.mapper.ClassifierMapper;
import com.enterprise.mm.mapper.CurrencyMapper;
import com.enterprise.mm.mapper.DepartmentMapper;
import com.enterprise.mm.mapper.ReferenceMappingMapper;
import com.enterprise.mm.payload.BudgetItemUpdateRequest;
import com.enterprise.mm.payload.CurrencyCreateRequest;
import com.enterprise.mm.payload.DepartmentCreateRequest;
import com.enterprise.mm.payload.DepartmentResponse;
import com.enterprise.mm.payload.DepartmentUpdateRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiApplyRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiSuggestionDto;
import com.enterprise.mm.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ReferenceFinancialControlTest {
    @Mock private AuditService auditService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("reference-financial-control-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void genericBudgetUpdateCannotChangeUsedOrReservedAmounts() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetItem original = scoped(new BudgetItem());
        original.setCode("CAPEX");
        original.setName("Capital expenditure");
        original.setAnnualLimit(new BigDecimal("1000"));
        original.setReservedAmount(new BigDecimal("125"));
        original.setUsedAmount(new BigDecimal("250"));
        when(repository.findByIdAndIsDeletedFalse(original.getId())).thenReturn(Optional.of(original));
        when(repository.save(original)).thenReturn(original);

        BudgetItemUpdateRequest update = new BudgetItemUpdateRequest();
        update.setName("Capital expenditure");
        update.setAnnualLimit(new BigDecimal("1200"));

        var result = new BudgetItemService(repository, mock(BudgetTransactionRepository.class), new BudgetItemMapper(), auditService)
            .update(original.getId(), update);

        assertThat(result.getAnnualLimit()).isEqualByComparingTo("1200");
        assertThat(result.getReservedAmount()).isEqualByComparingTo("125");
        assertThat(result.getUsedAmount()).isEqualByComparingTo("250");
    }

    @Test
    void sourceKeyedReservationDoesNotApplyTwice() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setAnnualLimit(new BigDecimal("1000"));
        AtomicReference<BudgetTransaction> savedTransaction = new AtomicReference<>();
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "PurchaseRequestItem:line-1", "RESERVE_APPROVED"))
            .thenAnswer(invocation -> Optional.ofNullable(savedTransaction.get()));
        when(transactionRepository.save(any(BudgetTransaction.class))).thenAnswer(invocation -> {
            BudgetTransaction transaction = invocation.getArgument(0);
            savedTransaction.set(transaction);
            return transaction;
        });

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);

        service.reserveIdempotent(
            budget.getId(),
            new BigDecimal("100"),
            "PurchaseRequest PR-1",
            "PurchaseRequest",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "PurchaseRequestItem:line-1",
            "RESERVE_APPROVED"
        );
        service.reserveIdempotent(
            budget.getId(),
            new BigDecimal("100"),
            "PurchaseRequest PR-1",
            "PurchaseRequest",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "PurchaseRequestItem:line-1",
            "RESERVE_APPROVED"
        );

        assertThat(budget.getReservedAmount()).isEqualByComparingTo("100");
        assertThat(savedTransaction.get().getReservedDelta()).isEqualByComparingTo("100");
        assertThat(savedTransaction.get().getCommittedDelta()).isZero();
        assertThat(savedTransaction.get().getUsedDelta()).isZero();
        verify(transactionRepository).save(any(BudgetTransaction.class));
    }

    @Test
    void budgetConsumptionDrainsCommitmentBeforeReservation() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setAnnualLimit(new BigDecimal("1000"));
        budget.setReservedAmount(new BigDecimal("60"));
        budget.setCommittedAmount(new BigDecimal("80"));
        budget.setUsedAmount(BigDecimal.ZERO);
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));

        BudgetItemService service = new BudgetItemService(
            repository,
            mock(BudgetTransactionRepository.class),
            new BudgetItemMapper(),
            auditService
        );

        service.consume(budget.getId(), new BigDecimal("100"), "Invoice INV-1");

        assertThat(budget.getCommittedAmount()).isZero();
        assertThat(budget.getReservedAmount()).isEqualByComparingTo("40");
        assertThat(budget.getUsedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void sourceKeyedConsumptionDoesNotApplyTwice() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setAnnualLimit(new BigDecimal("1000"));
        budget.setCommittedAmount(new BigDecimal("300"));
        AtomicReference<BudgetTransaction> consumption = new AtomicReference<>();
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "ContractInvoice:inv-1:PurchaseOrderItem:line-1", "CONSUME_ISSUED"))
            .thenAnswer(invocation -> Optional.ofNullable(consumption.get()));
        when(transactionRepository.save(any(BudgetTransaction.class))).thenAnswer(invocation -> {
            BudgetTransaction transaction = invocation.getArgument(0);
            consumption.set(transaction);
            return transaction;
        });

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);

        service.consumeIdempotent(
            budget.getId(),
            new BigDecimal("125"),
            "ContractInvoice INV-1",
            "ContractInvoice",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ContractInvoice:inv-1:PurchaseOrderItem:line-1",
            "CONSUME_ISSUED"
        );
        service.consumeIdempotent(
            budget.getId(),
            new BigDecimal("125"),
            "ContractInvoice INV-1",
            "ContractInvoice",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ContractInvoice:inv-1:PurchaseOrderItem:line-1",
            "CONSUME_ISSUED"
        );

        assertThat(budget.getCommittedAmount()).isEqualByComparingTo("175");
        assertThat(budget.getUsedAmount()).isEqualByComparingTo("125");
        assertThat(consumption.get().getReservedDelta()).isZero();
        assertThat(consumption.get().getCommittedDelta()).isEqualByComparingTo("-125");
        assertThat(consumption.get().getUsedDelta()).isEqualByComparingTo("125");
        verify(transactionRepository).save(any(BudgetTransaction.class));
    }

    @Test
    void sourceKeyedConsumptionReversalDoesNotApplyTwice() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setAnnualLimit(new BigDecimal("1000"));
        budget.setUsedAmount(new BigDecimal("125"));
        AtomicReference<BudgetTransaction> reversal = new AtomicReference<>();
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "ContractInvoice:inv-1:PurchaseOrderItem:line-1", "REVERSE_CANCELLED"))
            .thenAnswer(invocation -> Optional.ofNullable(reversal.get()));
        BudgetTransaction original = new BudgetTransaction();
        original.setId(UUID.randomUUID());
        original.setBudgetItemId(budget.getId());
        original.setTransactionType("CONSUMPTION");
        original.setAmount(new BigDecimal("125"));
        original.setCommittedDelta(new BigDecimal("-125"));
        original.setUsedDelta(new BigDecimal("125"));
        original.setConsumedFromCommitted(new BigDecimal("125"));
        when(transactionRepository.findFirstByTenantIdAndCompanyIdAndSourceKeyAndTransactionTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "ContractInvoice:inv-1:PurchaseOrderItem:line-1", "CONSUMPTION"))
            .thenReturn(Optional.of(original));
        when(transactionRepository.save(any(BudgetTransaction.class))).thenAnswer(invocation -> {
            BudgetTransaction transaction = invocation.getArgument(0);
            reversal.set(transaction);
            return transaction;
        });

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);

        service.reverseConsumptionIdempotent(
            budget.getId(),
            new BigDecimal("125"),
            "ContractInvoice INV-1",
            "ContractInvoice",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ContractInvoice:inv-1:PurchaseOrderItem:line-1",
            "REVERSE_CANCELLED"
        );
        service.reverseConsumptionIdempotent(
            budget.getId(),
            new BigDecimal("125"),
            "ContractInvoice INV-1",
            "ContractInvoice",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ContractInvoice:inv-1:PurchaseOrderItem:line-1",
            "REVERSE_CANCELLED"
        );

        assertThat(budget.getCommittedAmount()).isEqualByComparingTo("125");
        assertThat(budget.getUsedAmount()).isZero();
        assertThat(reversal.get().getReservedDelta()).isZero();
        assertThat(reversal.get().getCommittedDelta()).isEqualByComparingTo("125");
        assertThat(reversal.get().getUsedDelta()).isEqualByComparingTo("-125");
        verify(transactionRepository).save(any(BudgetTransaction.class));
    }

    @Test
    void consumptionReversalRestoresTheOriginalFundingBuckets() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setAnnualLimit(new BigDecimal("1000"));
        budget.setReservedAmount(new BigDecimal("20"));
        budget.setCommittedAmount(new BigDecimal("40"));
        budget.setUsedAmount(new BigDecimal("100"));
        UUID originalId = UUID.randomUUID();
        BudgetTransaction original = new BudgetTransaction();
        original.setId(originalId);
        original.setBudgetItemId(budget.getId());
        original.setTransactionType("CONSUMPTION");
        original.setAmount(new BigDecimal("100"));
        original.setReservedDelta(new BigDecimal("-60"));
        original.setCommittedDelta(new BigDecimal("-40"));
        original.setUsedDelta(new BigDecimal("100"));
        original.setConsumedFromReserved(new BigDecimal("60"));
        original.setConsumedFromCommitted(new BigDecimal("40"));
        original.setSourceKey("ContractInvoice:inv-1:PurchaseOrderItem:line-1");
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "ContractInvoice:inv-1:PurchaseOrderItem:line-1", "REVERSE_CANCELLED"))
            .thenReturn(Optional.empty());
        when(transactionRepository.findFirstByTenantIdAndCompanyIdAndSourceKeyAndTransactionTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "ContractInvoice:inv-1:PurchaseOrderItem:line-1", "CONSUMPTION"))
            .thenReturn(Optional.of(original));
        AtomicReference<BudgetTransaction> savedReversal = new AtomicReference<>();
        when(transactionRepository.save(any(BudgetTransaction.class))).thenAnswer(invocation -> {
            savedReversal.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);

        service.reverseConsumptionIdempotent(
            budget.getId(), new BigDecimal("100"), "ContractInvoice INV-1", "ContractInvoice",
            UUID.randomUUID(), UUID.randomUUID(), "ContractInvoice:inv-1:PurchaseOrderItem:line-1", "REVERSE_CANCELLED");

        assertThat(budget.getReservedAmount()).isEqualByComparingTo("80");
        assertThat(budget.getCommittedAmount()).isEqualByComparingTo("80");
        assertThat(budget.getUsedAmount()).isZero();
        assertThat(savedReversal.get().getOriginalTransactionId()).isEqualTo(originalId);
        assertThat(savedReversal.get().getReversalOfTransactionId()).isEqualTo(originalId);
    }

    @Test
    void releaseRejectsAnAmountLargerThanTheReservedBalance() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setReservedAmount(new BigDecimal("25"));
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "PR:1", "CANCEL_RELEASE:1"))
            .thenReturn(Optional.empty());

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);

        assertThatThrownBy(() -> service.releaseIdempotent(
            budget.getId(), new BigDecimal("26"), "PR-1", "PurchaseRequest", UUID.randomUUID(), null,
            "PR:1", "CANCEL_RELEASE:1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("release exceeds reserved amount");
    }

    @Test
    void sourceKeyedCommitmentAndReversalDoNotApplyTwice() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setAnnualLimit(new BigDecimal("1000"));
        AtomicReference<BudgetTransaction> commitment = new AtomicReference<>();
        AtomicReference<BudgetTransaction> reversal = new AtomicReference<>();
        when(repository.findByIdForUpdate(budget.getId())).thenReturn(Optional.of(budget));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "PurchaseOrderItem:line-1", "COMMIT_SENT"))
            .thenAnswer(invocation -> Optional.ofNullable(commitment.get()));
        when(transactionRepository.findByTenantIdAndCompanyIdAndSourceKeyAndActionKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "PurchaseOrderItem:line-1", "REVERSE_CANCELLED"))
            .thenAnswer(invocation -> Optional.ofNullable(reversal.get()));
        BudgetTransaction original = new BudgetTransaction();
        original.setId(UUID.randomUUID());
        original.setBudgetItemId(budget.getId());
        original.setTransactionType("COMMITMENT");
        original.setAmount(new BigDecimal("250"));
        original.setCommittedDelta(new BigDecimal("250"));
        when(transactionRepository.findFirstByTenantIdAndCompanyIdAndSourceKeyAndTransactionTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, "PurchaseOrderItem:line-1", "COMMITMENT"))
            .thenReturn(Optional.of(original));
        when(transactionRepository.save(any(BudgetTransaction.class))).thenAnswer(invocation -> {
            BudgetTransaction transaction = invocation.getArgument(0);
            if ("COMMITMENT".equals(transaction.getTransactionType())) {
                commitment.set(transaction);
            } else if ("COMMITMENT_REVERSAL".equals(transaction.getTransactionType())) {
                reversal.set(transaction);
            }
            return transaction;
        });

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);
        UUID poId = UUID.randomUUID();
        UUID poLineId = UUID.randomUUID();

        service.commitIdempotent(
            budget.getId(),
            new BigDecimal("250"),
            "PurchaseOrder PO-1",
            "PurchaseOrder",
            poId,
            poLineId,
            "PurchaseOrderItem:line-1",
            "COMMIT_SENT"
        );
        service.commitIdempotent(
            budget.getId(),
            new BigDecimal("250"),
            "PurchaseOrder PO-1",
            "PurchaseOrder",
            poId,
            poLineId,
            "PurchaseOrderItem:line-1",
            "COMMIT_SENT"
        );
        service.reverseCommitmentIdempotent(
            budget.getId(),
            new BigDecimal("250"),
            "PurchaseOrder PO-1",
            "PurchaseOrder",
            poId,
            poLineId,
            "PurchaseOrderItem:line-1",
            "REVERSE_CANCELLED"
        );
        service.reverseCommitmentIdempotent(
            budget.getId(),
            new BigDecimal("250"),
            "PurchaseOrder PO-1",
            "PurchaseOrder",
            poId,
            poLineId,
            "PurchaseOrderItem:line-1",
            "REVERSE_CANCELLED"
        );

        assertThat(budget.getCommittedAmount()).isZero();
        assertThat(commitment.get().getCommittedDelta()).isEqualByComparingTo("250");
        assertThat(reversal.get().getCommittedDelta()).isEqualByComparingTo("-250");
        verify(transactionRepository, org.mockito.Mockito.times(2)).save(any(BudgetTransaction.class));
    }

    @Test
    void budgetLedgerReconciliationComparesStoredBalancesToLedgerDeltas() {
        BudgetItemRepository repository = mock(BudgetItemRepository.class);
        BudgetTransactionRepository transactionRepository = mock(BudgetTransactionRepository.class);
        BudgetTransactionRepository.BudgetLedgerTotals totals = mock(BudgetTransactionRepository.BudgetLedgerTotals.class);
        BudgetItem budget = scoped(new BudgetItem());
        budget.setReservedAmount(new BigDecimal("40"));
        budget.setCommittedAmount(new BigDecimal("175"));
        budget.setUsedAmount(new BigDecimal("125"));
        when(repository.findByIdAndIsDeletedFalse(budget.getId())).thenReturn(Optional.of(budget));
        when(transactionRepository.sumActiveDeltas(TenantContext.currentTenant(), companyId, budget.getId()))
            .thenReturn(totals);
        when(totals.getReservedDelta()).thenReturn(new BigDecimal("40"));
        when(totals.getCommittedDelta()).thenReturn(new BigDecimal("175"));
        when(totals.getUsedDelta()).thenReturn(new BigDecimal("125"));

        BudgetItemService service = new BudgetItemService(repository, transactionRepository, new BudgetItemMapper(), auditService);

        var matched = service.reconcileLedger(budget.getId());

        assertThat(matched.matched()).isTrue();
        assertThat(matched.reservedDifference()).isZero();
        assertThat(matched.committedDifference()).isZero();
        assertThat(matched.usedDifference()).isZero();

        when(totals.getUsedDelta()).thenReturn(new BigDecimal("100"));

        var mismatched = service.reconcileLedger(budget.getId());

        assertThat(mismatched.matched()).isFalse();
        assertThat(mismatched.usedDifference()).isEqualByComparingTo("25");
    }

    @Test
    void currencyNormalizesOfficialIsoAndAllowsOnlyOneBasePerTenant() {
        CurrencyRepository repository = mock(CurrencyRepository.class);
        ExchangeRateRepository exchangeRateRepository = mock(ExchangeRateRepository.class); // Mock qo'shildi

        when(repository.findByTenantIdAndIsoCodeIgnoreCaseAndIsDeletedFalse(
                TenantContext.currentTenant(), "USD")).thenReturn(Optional.empty());
        when(repository.existsByTenantIdAndBaseCurrencyTrueAndIsDeletedFalse(TenantContext.currentTenant()))
                .thenReturn(false);
        when(repository.save(any(Currency.class))).thenAnswer(invocation -> {
            Currency entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setTenantId(TenantContext.currentTenant());
            return entity;
        });

        CurrencyCreateRequest request = new CurrencyCreateRequest();
        request.setIsoCode(" usd ");
        request.setName("US dollar");
        request.setBaseCurrency(true);

        var result = new CurrencyService(repository, new CurrencyMapper(),  auditService,exchangeRateRepository).create(request);

        assertThat(result.getIsoCode()).isEqualTo("USD");
        assertThat(result.getCode()).isEqualTo("USD");

        when(repository.existsByTenantIdAndBaseCurrencyTrueAndIsDeletedFalse(TenantContext.currentTenant()))
                .thenReturn(true);

        assertThatThrownBy(() -> new CurrencyService(repository, new CurrencyMapper(),  auditService,exchangeRateRepository).create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("base currency");
    }

    @Test
    void currencyRejectsNonIsoCode() {
        CurrencyCreateRequest request = new CurrencyCreateRequest();
        request.setIsoCode("US1");
        request.setName("Invalid");

        assertThatThrownBy(() -> new CurrencyService(
                mock(CurrencyRepository.class), new CurrencyMapper(),  auditService,mock(ExchangeRateRepository.class)).create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ISO");
    }

    @Test
    void departmentRejectsLongParentCycle() {
        DepartmentRepository repository = mock(DepartmentRepository.class);
        DepartmentTypeRepository typeRepository = mock(DepartmentTypeRepository.class);
        DepartmentType type = scoped(new DepartmentType());
        when(typeRepository.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, "DEPARTMENT")).thenReturn(Optional.of(type));

        Department a = department("A");
        Department b = department("B");
        Department c = department("C");
        a.setParentDepartment(b);
        b.setParentDepartment(c);
        c.setParentDepartment(a);
        when(repository.findByIdAndIsDeletedFalse(a.getId())).thenReturn(Optional.of(a));
        when(repository.findByIdAndIsDeletedFalse(b.getId())).thenReturn(Optional.of(b));

        DepartmentService service = new DepartmentService(
            repository, mock(DepartmentMapper.class), auditService, typeRepository, mock(EmployeeRepository.class),
            mock(com.enterprise.mm.repository.WarehouseRepository.class),
            mock(com.enterprise.mm.repository.DepartmentWarehouseAccessRepository.class));

        assertThatThrownBy(() -> service.beforeUpdate(c, new DepartmentUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void departmentCreateAllowsRootParentWithoutFalseCycle() {
        DepartmentRepository repository = mock(DepartmentRepository.class);
        DepartmentTypeRepository typeRepository = mock(DepartmentTypeRepository.class);
        DepartmentType type = scoped(new DepartmentType());
        when(typeRepository.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, "DEPARTMENT")).thenReturn(Optional.of(type));

        Department parent = department("ROOT");
        parent.setHierarchyPath("/ROOT");
        Department child = new Department();
        child.setCode("CHILD");
        child.setName("Child");
        child.setDepartmentType("DEPARTMENT");
        child.setParentDepartment(parent);

        when(repository.findByIdAndIsDeletedFalse(parent.getId())).thenReturn(Optional.of(parent));
        when(repository.findByTenantIdAndCompanyIdAndNameIgnoreCaseAndIsDeletedFalse(
            org.mockito.ArgumentMatchers.eq(TenantContext.currentTenant()),
            org.mockito.ArgumentMatchers.eq(companyId),
            any(String.class))).thenReturn(Optional.empty());

        DepartmentService service = new DepartmentService(
            repository, mock(DepartmentMapper.class), auditService, typeRepository, mock(EmployeeRepository.class),
            mock(com.enterprise.mm.repository.WarehouseRepository.class),
            mock(com.enterprise.mm.repository.DepartmentWarehouseAccessRepository.class));

        service.beforeCreate(child, new DepartmentCreateRequest());

        assertThat(child.getParentDepartment()).isSameAs(parent);
        assertThat(child.getHierarchyPath()).isEqualTo("/ROOT/CHILD");
    }

    @Test
    void departmentCreateAttachesUnassignedManagerEmployee() {
        DepartmentRepository repository = mock(DepartmentRepository.class);
        DepartmentTypeRepository typeRepository = mock(DepartmentTypeRepository.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        DepartmentMapper mapper = mock(DepartmentMapper.class);
        DepartmentType type = scoped(new DepartmentType());
        when(typeRepository.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), companyId, "DEPARTMENT")).thenReturn(Optional.of(type));

        Employee manager = scoped(new Employee());
        Department department = department("OPERATIONS");
        department.setManagerEmployee(manager);
        when(mapper.toEntity(any(DepartmentCreateRequest.class))).thenReturn(department);
        when(mapper.toResponse(department)).thenReturn(new DepartmentResponse());
        when(repository.findByTenantIdAndCompanyIdAndNameIgnoreCaseAndIsDeletedFalse(
            org.mockito.ArgumentMatchers.eq(TenantContext.currentTenant()),
            org.mockito.ArgumentMatchers.eq(companyId),
            any(String.class))).thenReturn(Optional.empty());
        when(repository.save(department)).thenReturn(department);
        when(employeeRepository.findByIdAndIsDeletedFalse(manager.getId())).thenReturn(Optional.of(manager));

        com.enterprise.mm.repository.DepartmentWarehouseAccessRepository accessRepository =
            mock(com.enterprise.mm.repository.DepartmentWarehouseAccessRepository.class);
        when(accessRepository.findActiveWarehouseIds(any(), any(), any())).thenReturn(java.util.Set.of());
        new DepartmentService(repository, mapper, auditService, typeRepository, employeeRepository,
            mock(com.enterprise.mm.repository.WarehouseRepository.class), accessRepository)
            .create(new DepartmentCreateRequest());

        assertThat(manager.getDepartment()).isSameAs(department);
        verify(employeeRepository).save(manager);
    }

    @Test
    void chartRejectsLongParentCycle() {
        ChartOfAccountRepository repository = mock(ChartOfAccountRepository.class);
        ChartOfAccount a = account("A", "B");
        ChartOfAccount b = account("B", "C");
        ChartOfAccount c = account("C", "A");
        when(repository.findByTenantIdAndCompanyIdAndAccountCodeAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "A")).thenReturn(Optional.of(a));
        when(repository.findByTenantIdAndCompanyIdAndAccountCodeAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "B")).thenReturn(Optional.of(b));

        ChartOfAccountService service = new ChartOfAccountService(
            repository, new ChartOfAccountMapper(), auditService, mock(EntityManager.class));

        assertThatThrownBy(() -> service.beforeUpdate(c, null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void genericClassifierCommandsCannotMutateValuationMethods() {
        ClassifierRepository repository = mock(ClassifierRepository.class);
        ClassifierTypeRepository typeRepository = mock(ClassifierTypeRepository.class);
        Classifier valuationMethod = scoped(new Classifier());
        valuationMethod.setCode(String.valueOf(com.enterprise.mm.enums.ValuationMethod.FIFO));
        valuationMethod.setName(String.valueOf(com.enterprise.mm.enums.ValuationMethod.FIFO));
        valuationMethod.setClassifierType("VALUATION_METHOD");
        when(repository.findByIdAndIsDeletedFalse(valuationMethod.getId()))
            .thenReturn(Optional.of(valuationMethod));

        ClassifierService service = new ClassifierService(
            repository, new ClassifierMapper(), auditService, typeRepository);

        assertThatThrownBy(() -> service.softDelete(valuationMethod.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("valuation method");
        verify(repository, never()).save(valuationMethod);
    }

    @Test
    void referenceMappingRestoreRepeatsUniquenessValidationBeforeMutation() {
        ReferenceMappingRepository repository = mock(ReferenceMappingRepository.class);
        ReferenceMapping deleted = scoped(new ReferenceMapping());
        deleted.setDeleted(true);
        deleted.setReferenceEntityType("MATERIAL");
        deleted.setReferenceCode("MAT-1");
        deleted.setExternalSystem("ONE_C");
        deleted.setExternalCode("EXT-1");
        deleted.setAccountingArea("MANAGEMENT");
        deleted.setName("Mapping");
        ReferenceMapping activeDuplicate = scoped(new ReferenceMapping());
        when(repository.findAnyById(deleted.getId())).thenReturn(Optional.of(deleted));
        when(repository.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndExternalSystemAndExternalCodeAndAccountingAreaAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, "MATERIAL", "ONE_C", "EXT-1", "MANAGEMENT"))
            .thenReturn(Optional.of(activeDuplicate));

        ReferenceMappingService service = new ReferenceMappingService(
            repository, new ReferenceMappingMapper(), auditService);

        assertThatThrownBy(() -> service.restore(deleted.getId()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("сопоставлен");
        assertThat(deleted.isDeleted()).isTrue();
        verify(repository, never()).save(deleted);
    }

    @Test
    void referenceAndFinancialRestoresRejectOtherTenantBeforeMutation() {
        BudgetItemRepository budgetRepository = mock(BudgetItemRepository.class);
        CurrencyRepository currencyRepository = mock(CurrencyRepository.class);
        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        ChartOfAccountRepository chartRepository = mock(ChartOfAccountRepository.class);
        ClassifierRepository classifierRepository = mock(ClassifierRepository.class);
        ReferenceMappingRepository mappingRepository = mock(ReferenceMappingRepository.class);

        BudgetItem budget = otherTenant(new BudgetItem());
        Currency currency = otherTenant(new Currency());
        Department department = otherTenant(new Department());
        ChartOfAccount chart = otherTenant(new ChartOfAccount());
        Classifier classifier = otherTenant(new Classifier());
        ReferenceMapping mapping = otherTenant(new ReferenceMapping());

        when(budgetRepository.findAnyById(budget.getId())).thenReturn(Optional.of(budget));
        when(currencyRepository.findAnyById(currency.getId())).thenReturn(Optional.of(currency));
        when(departmentRepository.findAnyById(department.getId())).thenReturn(Optional.of(department));
        when(chartRepository.findAnyById(chart.getId())).thenReturn(Optional.of(chart));
        when(classifierRepository.findAnyById(classifier.getId())).thenReturn(Optional.of(classifier));
        when(mappingRepository.findAnyById(mapping.getId())).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> new BudgetItemService(
                budgetRepository, mock(BudgetTransactionRepository.class), new BudgetItemMapper(), auditService).restore(budget.getId()))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> new CurrencyService(
                currencyRepository, new CurrencyMapper(), auditService, mock(ExchangeRateRepository.class)).restore(currency.getId()))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> new DepartmentService(
                departmentRepository, mock(DepartmentMapper.class), auditService,
                mock(DepartmentTypeRepository.class), mock(EmployeeRepository.class),
                mock(com.enterprise.mm.repository.WarehouseRepository.class),
                mock(com.enterprise.mm.repository.DepartmentWarehouseAccessRepository.class)).restore(department.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> new ChartOfAccountService(
                chartRepository, new ChartOfAccountMapper(), auditService,
                mock(EntityManager.class)).restore(chart.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> new ClassifierService(
                classifierRepository, new ClassifierMapper(), auditService,
                mock(ClassifierTypeRepository.class)).restore(classifier.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> new ReferenceMappingService(
                mappingRepository, new ReferenceMappingMapper(), auditService).restore(mapping.getId()))
                .isInstanceOf(NotFoundException.class);

        verify(budgetRepository, never()).save(any());
        verify(currencyRepository, never()).save(any());
        verify(departmentRepository, never()).save(any());
        verify(chartRepository, never()).save(any());
        verify(classifierRepository, never()).save(any());
        verify(mappingRepository, never()).save(any());
    }

    @Test
    void aiBulkApplyReturnsItemLevelFailureInsteadOfSilentSkip() {
        ReferenceMappingRepository repository = mock(ReferenceMappingRepository.class);
        ReferenceMappingService mappingService = mock(ReferenceMappingService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IntegrationSettingService> provider = mock(ObjectProvider.class);
        ReferenceMappingAiService service = new ReferenceMappingAiService(
            repository, new ReferenceMappingMapper(), mappingService, provider, new ObjectMapper());
        when(repository.findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceCodeAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
            TenantContext.currentTenant(), companyId, "MATERIAL", "MAT-FAIL", "ONE_C", "MANAGEMENT"))
            .thenReturn(Optional.empty());
        when(mappingService.create(any())).thenThrow(new BadRequestException("duplicate external code"));

        var result = service.applyAiSuggestions(new ReferenceMappingAiApplyRequest(
            "MATERIAL", "ONE_C", "MANAGEMENT", List.of(suggestion("MAT-FAIL", "EXT-FAIL"))));

        assertThat(result.applied()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.referenceCode()).isEqualTo("MAT-FAIL");
            assertThat(failure.externalCode()).isEqualTo("EXT-FAIL");
            assertThat(failure.message()).contains("duplicate external code");
        });
    }

    private ReferenceMappingAiSuggestionDto suggestion(String referenceCode, String externalCode) {
        return new ReferenceMappingAiSuggestionDto(
            null, referenceCode, "Material", externalCode, "External material", "MANAGEMENT",
            null, null, null, null, null, null, null, null, 100, true,
            0.9d, "rule", "RULE", null, false);
    }

    private Department department(String code) {
        Department department = scoped(new Department());
        department.setCode(code);
        department.setName(code);
        department.setDepartmentType("DEPARTMENT");
        return department;
    }

    private ChartOfAccount account(String code, String parentCode) {
        ChartOfAccount account = scoped(new ChartOfAccount());
        account.setCode(code);
        account.setAccountCode(code);
        account.setName(code);
        account.setParentCode(parentCode);
        return account;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T scoped(T entity) {
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(companyId);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T otherTenant(T entity) {
        entity.setId(UUID.randomUUID());
        entity.setTenantId("other-tenant");
        entity.setCompanyId(companyId);
        entity.setDeleted(true);
        return entity;
    }
}
