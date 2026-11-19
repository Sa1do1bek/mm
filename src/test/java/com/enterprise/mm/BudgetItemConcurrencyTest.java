package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.BudgetTransaction;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.BudgetTransactionRepository;
import com.enterprise.mm.service.BudgetItemService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
class BudgetItemConcurrencyTest {
    @Autowired BudgetItemService budgetItemService;
    @Autowired BudgetItemRepository budgetItemRepository;
    @Autowired BudgetTransactionRepository budgetTransactionRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private String tenantId;
    private UUID companyId;
    private BudgetItem budgetItem;

    @BeforeEach
    void setUp() {
        tenantId = "budget-concurrency-" + UUID.randomUUID();
        companyId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        budgetItem = new BudgetItem();
        budgetItem.setCompanyId(companyId);
        budgetItem.setCode("BUD-CON-" + suffix());
        budgetItem.setName("Concurrent budget");
        budgetItem.setStatus("ACTIVE");
        budgetItem.setAnnualLimit(new BigDecimal("1000"));
        budgetItem.setReservedAmount(BigDecimal.ZERO);
        budgetItem.setCommittedAmount(BigDecimal.ZERO);
        budgetItem.setUsedAmount(BigDecimal.ZERO);
        budgetItem = budgetItemRepository.saveAndFlush(budgetItem);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void concurrentCommitmentsCannotOverspendBudgetLimit() throws Exception {
        List<ConcurrentResult> results = runConcurrently(List.of(
            () -> commit("PurchaseOrderItem:line-1"),
            () -> commit("PurchaseOrderItem:line-2")
        ));

        assertThat(results.stream().filter(ConcurrentResult::successful)).hasSize(1);
        assertThat(results.stream().filter(result -> result.error() instanceof BusinessException)).hasSize(1);

        BudgetItem stored = budgetItemRepository.findByIdAndIsDeletedFalse(budgetItem.getId()).orElseThrow();
        assertThat(stored.getReservedAmount()).isZero();
        assertThat(stored.getCommittedAmount()).isEqualByComparingTo("700");
        assertThat(stored.getUsedAmount()).isZero();

        List<BudgetTransaction> transactions = budgetTransactionRepository.findAll().stream()
            .filter(transaction -> tenantId.equals(transaction.getTenantId()))
            .filter(transaction -> companyId.equals(transaction.getCompanyId()))
            .filter(transaction -> budgetItem.getId().equals(transaction.getBudgetItemId()))
            .filter(transaction -> !transaction.isDeleted())
            .toList();
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst().getCommittedDelta()).isEqualByComparingTo("700");
        assertThat(transactions.getFirst().getReservedDelta()).isZero();
        assertThat(transactions.getFirst().getUsedDelta()).isZero();
        assertThat(budgetItemService.reconcileLedger(budgetItem.getId()).matched()).isTrue();
    }

    private UUID commit(String sourceKey) {
        return budgetItemService.commitIdempotent(
            budgetItem.getId(),
            new BigDecimal("700"),
            "PurchaseOrder PO-CON",
            "PurchaseOrder",
            UUID.randomUUID(),
            UUID.randomUUID(),
            sourceKey,
            "COMMIT_SENT"
        ).budgetItemId();
    }

    private List<ConcurrentResult> runConcurrently(List<Callable<UUID>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ConcurrentResult>> futures = new ArrayList<>();
        for (Callable<UUID> operation : operations) {
            futures.add(executor.submit(() -> {
                TenantContext.setTenant(tenantId);
                CompanyContext.setCompany(companyId);
                try {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    operation.call();
                    return new ConcurrentResult(true, null);
                } catch (Throwable error) {
                    return new ConcurrentResult(false, rootCause(error));
                } finally {
                    TenantContext.clear();
                    CompanyContext.clear();
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<ConcurrentResult> results = new ArrayList<>();
        for (Future<ConcurrentResult> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record ConcurrentResult(boolean successful, Throwable error) {
    }
}
