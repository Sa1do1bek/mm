package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobWorkerProcessingTest {
    @Mock
    private StockValuationCostJobRepository jobRepository;
    @Mock
    private StockValuationCostJobTransactionService transactionService;
    @Mock
    private StockValuationCostSelectionService selectionService;

    @Test
    void successfulGroupsPersistInOrderAndComplete() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = StockValuationCostJob.queued("tenant", UUID.randomUUID(), null, "user");
        job.setId(jobId);
        StockGroup first = group("Steel");
        StockGroup second = group("Copper");
        StockCost firstResult = cost(first, true, "10.0000", null);
        StockCost secondResult = cost(second, true, "20.0000", null);
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.of(job));
        when(selectionService.findPositiveStockGroups(null)).thenReturn(List.of(first, second));
        when(selectionService.calculateOne(first, null)).thenReturn(firstResult);
        when(selectionService.calculateOne(second, null)).thenReturn(secondResult);

        worker().run(jobId);

        InOrder order = inOrder(transactionService, selectionService);
        order.verify(transactionService).markRunning(jobId);
        order.verify(selectionService).findPositiveStockGroups(null);
        order.verify(transactionService).saveTotalItems(jobId, 2);
        order.verify(selectionService).calculateOne(first, null);
        order.verify(transactionService).saveResultAndIncrementCounters(jobId, 1, firstResult);
        order.verify(selectionService).calculateOne(second, null);
        order.verify(transactionService).saveResultAndIncrementCounters(jobId, 2, secondResult);
        order.verify(transactionService).markFinalizing(jobId);
        order.verify(transactionService).markCompleted(jobId);
        verify(transactionService, never()).markCompletedWithErrors(jobId);
    }

    @Test
    void unavailableAndThrownGroupsCompleteWithErrorsAndPersistUnavailableRows() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = job(jobId);
        StockGroup available = group("Steel");
        StockGroup unavailable = group("Copper");
        StockGroup thrown = group("Bronze");
        StockCost availableResult = cost(available, true, "10.0000", null);
        StockCost unavailableResult = cost(unavailable, false, null, "no batches");
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.of(job));
        when(selectionService.findPositiveStockGroups(null)).thenReturn(List.of(available, unavailable, thrown));
        when(selectionService.calculateOne(available, null)).thenReturn(availableResult);
        when(selectionService.calculateOne(unavailable, null)).thenReturn(unavailableResult);
        when(selectionService.calculateOne(thrown, null)).thenThrow(new IllegalStateException("  calculation exploded  "));

        worker().run(jobId);

        verify(transactionService).saveResultAndIncrementCounters(jobId, 1, availableResult);
        verify(transactionService).saveResultAndIncrementCounters(jobId, 2, unavailableResult);
        ArgumentCaptor<StockCost> thrownResult = ArgumentCaptor.forClass(StockCost.class);
        verify(transactionService).saveResultAndIncrementCounters(eq(jobId), eq(3), thrownResult.capture());
        assertThat(thrownResult.getValue().available()).isFalse();
        assertThat(thrownResult.getValue().errorMessage()).isEqualTo("calculation exploded");
        verify(transactionService).markCompletedWithErrors(jobId);
        verify(transactionService, never()).markCompleted(jobId);
        verify(transactionService, never()).markFailed(any(), any());
    }

    @Test
    void emptyWorksetCompletesWithoutCalculations() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.of(job(jobId)));
        when(selectionService.findPositiveStockGroups(null)).thenReturn(List.of());

        worker().run(jobId);

        verify(transactionService).markRunning(jobId);
        verify(transactionService).saveTotalItems(jobId, 0);
        verify(transactionService).markFinalizing(jobId);
        verify(transactionService).markCompleted(jobId);
        verify(selectionService, never()).calculateOne(any(), any());
        verify(transactionService, never()).markCompletedWithErrors(jobId);
    }

    @Test
    void storedValuationMethodIsPassedToEachCalculation() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = job(jobId);
        job.setValuationMethod(com.enterprise.mm.enums.ValuationMethod.FIFO);
        StockGroup group = group("Steel");
        StockCost result = cost(group, true, "10.0000", null);
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.of(job));
        when(selectionService.findPositiveStockGroups(null)).thenReturn(List.of(group));
        when(selectionService.calculateOne(group, ValuationMethod.FIFO)).thenReturn(result);

        worker().run(jobId);

        verify(selectionService).calculateOne(group, ValuationMethod.FIFO);
        verify(transactionService).saveResultAndIncrementCounters(jobId, 1, result);
        verify(transactionService).markCompleted(jobId);
    }

    @Test
    void fatalDiscoveryErrorMarksJobFailed() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findWithWarehouseIdsById(jobId)).thenReturn(Optional.of(job(jobId)));
        when(selectionService.findPositiveStockGroups(null)).thenThrow(new IllegalStateException(" database unavailable "));

        worker().run(jobId);

        verify(transactionService).markFailed(jobId, "database unavailable");
        verify(transactionService, never()).markCompleted(jobId);
        verify(transactionService, never()).markCompletedWithErrors(jobId);
    }

    private StockValuationCostJobWorker worker() {
        return new StockValuationCostJobWorker(jobRepository, transactionService, selectionService);
    }

    private static StockValuationCostJob job(UUID id) {
        StockValuationCostJob job = StockValuationCostJob.queued("tenant", UUID.randomUUID(), null, "user");
        job.setId(id);
        return job;
    }

    private static StockGroup group(String name) {
        return new StockGroup(UUID.randomUUID(), name, UUID.randomUUID(), "Main", BigDecimal.TEN);
    }

    private static StockCost cost(StockGroup group, boolean available, String totalCost, String error) {
        return new StockCost(
            group.materialId(), group.materialName(), group.warehouseId(), group.warehouseName(),
            group.quantity(), com.enterprise.mm.enums.ValuationMethod.FIFO, totalCost == null ? null : new BigDecimal(totalCost), available, error);
    }
}
