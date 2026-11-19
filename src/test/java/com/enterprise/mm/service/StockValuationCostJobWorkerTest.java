package com.enterprise.mm.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobWorkerTest {
    @Mock
    private StockValuationCostJobRepository jobRepository;
    @Mock
    private StockValuationCostJobTransactionService transactionService;
    @Mock
    private StockValuationCostSelectionService selectionService;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void workerRestoresScopeTransitionsToCompletedAndClearsContexts() {
        UUID companyId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = StockValuationCostJob.queued("worker-tenant", companyId, null, "finance-user");
        job.setId(jobId);
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.of(job));
        when(selectionService.findPositiveStockGroups(null)).thenReturn(List.of());

        new StockValuationCostJobWorker(jobRepository, transactionService, selectionService).run(jobId);

        verify(transactionService).markRunning(jobId);
        verify(transactionService).markCompleted(jobId);
        org.assertj.core.api.Assertions.assertThat(TenantContext.currentTenant()).isEqualTo(TenantContext.DEFAULT_TENANT);
        org.assertj.core.api.Assertions.assertThat(CompanyContext.currentCompany()).isEqualTo(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @Test
    void missingJobDoesNotCrashOrMutateState() {
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenant("stale-tenant");
        CompanyContext.setCompany(UUID.randomUUID());
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.empty());

        new StockValuationCostJobWorker(jobRepository, transactionService, selectionService).run(jobId);

        verifyNoInteractions(transactionService);
        org.assertj.core.api.Assertions.assertThat(TenantContext.currentTenant()).isEqualTo(TenantContext.DEFAULT_TENANT);
        org.assertj.core.api.Assertions.assertThat(CompanyContext.currentCompany()).isEqualTo(CompanyContext.DEFAULT_COMPANY_ID);
    }
}
