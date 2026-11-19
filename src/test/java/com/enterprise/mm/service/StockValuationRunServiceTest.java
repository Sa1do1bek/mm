package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.entity.StockValuationRun;
import com.enterprise.mm.enums.StockValuationRunScope;
import com.enterprise.mm.payload.StockValuationCostJobResponse;
import com.enterprise.mm.payload.StockValuationRunCreateRequest;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.StockValuationRunItemRepository;
import com.enterprise.mm.repository.StockValuationRunRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.StockValuationRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.i18n.LocaleContextHolder;

@ExtendWith(MockitoExtension.class)
class StockValuationRunServiceTest {
    @Mock
    private StockValuationRunRepository repository;
    @Mock
    private StockValuationRunItemRepository itemRepository;
    @Mock
    private StockValuationCostJobResultRepository jobResultRepository;
    @Mock
    private StockValuationCostJobService jobService;
    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private StockValuationRepository valuationRepository;

    @Test
    void rejectsMoreThanTenWarehousesBeforeLookingThemUp() {
        var request = new StockValuationRunCreateRequest();
        request.setScopeType(StockValuationRunScope.MULTI_WAREHOUSE);
        request.setWarehouseIds(java.util.stream.IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID()).toList());
        var service = new StockValuationRunService(repository, itemRepository, jobResultRepository, jobService, warehouseRepository, valuationRepository);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class).hasMessageContaining("10");
        org.mockito.Mockito.verifyNoInteractions(repository, warehouseRepository);
    }
    @Test
    void acceptsExactlyTenWarehouses() {
        stubQueuedJob();
        var request = new StockValuationRunCreateRequest();
        request.setName("Ten warehouse valuation");
        request.setScopeType(StockValuationRunScope.MULTI_WAREHOUSE);
        var ids = java.util.stream.IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
        request.setWarehouseIds(ids);
        var warehouses = new java.util.ArrayList<com.enterprise.mm.entity.Warehouse>();
        for (UUID id : ids) {
            var warehouse = new com.enterprise.mm.entity.Warehouse();
            warehouse.setId(id);
            warehouses.add(warehouse);
            when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)).thenReturn(java.util.Optional.of(warehouse));
        }
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdIn(
            eq(TenantContext.currentTenant()), eq(CompanyContext.currentCompany()), any())).thenReturn(warehouses);
        when(repository.save(any(StockValuationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new StockValuationRunService(repository, itemRepository, jobResultRepository, jobService, warehouseRepository, valuationRepository);
        assertThat(service.create(request).getWarehouses()).map(com.enterprise.mm.payload.WarehouseIDAndNameDto::id).containsExactlyInAnyOrderElementsOf(ids);
    }
    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void searchReturnsJobSnapshotWithoutWritingRunStatus() {
        TenantContext.setTenant("tenant-a");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        LocalDateTime finishedAt = LocalDateTime.now();
        StockValuationRun run = new StockValuationRun();
        run.setId(runId);
        run.setTenantId("tenant-a");
        run.setCompanyId(companyId);
        run.setCode("VALRUN-1");
        run.setName("Valuation run");
        run.setStatus(StockValuationRun.STATUS_RUNNING);
        run.setScopeType(StockValuationRunScope.GLOBAL);
        run.setJobId(jobId);
        var pageable = PageRequest.of(0, 20);
        var job = new com.enterprise.mm.entity.StockValuationCostJob();
        job.setStatus(com.enterprise.mm.enums.StockValuationCostJobStatus.COMPLETED);
        job.setFinishedAt(finishedAt);
        var row = org.mockito.Mockito.mock(StockValuationRunRepository.RunWithJob.class);
        when(row.getRun()).thenReturn(run);
        when(row.getJob()).thenReturn(job);
        when(repository.search(
            eq("tenant-a"),
            eq(companyId),
            anyBoolean(),
            nullable(String.class),
            anyList(),
            anyBoolean(),
            nullable(UUID.class),
            nullable(StockValuationRunScope.class),
            nullable(ValuationMethod.class),
            nullable(LocalDateTime.class),
            nullable(LocalDateTime.class),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        var page = service().search(null, null, null, null, null, null, null, false, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getStatus()).isEqualTo(StockValuationRun.STATUS_COMPLETED);
        assertThat(page.getContent().getFirst().getFinishedAt()).isEqualTo(finishedAt);
        assertThat(run.getStatus()).isEqualTo(StockValuationRun.STATUS_RUNNING);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(jobService);
    }

    @Test
    void createGeneratesMultilingualStoredNameAndReturnsOnlyRequestedTranslation() {
        stubQueuedJob();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(UUID.randomUUID());
        LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        StockValuationRunCreateRequest request = new StockValuationRunCreateRequest();
        request.setScopeType(StockValuationRunScope.GLOBAL);
        request.setDescription("unchanged description");
        when(repository.save(any(StockValuationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(request);

        var captor = org.mockito.ArgumentCaptor.forClass(StockValuationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName())
            .startsWith("ru: Оценка запасов:")
            .contains("\nen: Inventory valuation:")
            .contains("\nuz: Zaxiralarni baholash:");
        assertThat(response.getName()).startsWith("Inventory valuation:");
        assertThat(response.getName()).doesNotContain("ru:", "en:", "uz:");
        assertThat(response.getDescription()).isEqualTo("unchanged description");
    }

    @Test
    void createStoresProvidedNameAsIsAndLocalizesFormattedValueInResponse() {
        stubQueuedJob();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(UUID.randomUUID());
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("uz"));
        String supplied = "ru: Русское имя\nen: English name\nuz: O‘zbekcha nom";
        StockValuationRunCreateRequest request = new StockValuationRunCreateRequest();
        request.setScopeType(StockValuationRunScope.GLOBAL);
        request.setName(supplied);
        when(repository.save(any(StockValuationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(request);

        var captor = org.mockito.ArgumentCaptor.forClass(StockValuationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo(supplied);
        assertThat(response.getName()).isEqualTo("O‘zbekcha nom");
    }

    private void stubQueuedJob() {
        when(jobService.startJob(any(), any(), any())).thenReturn(new StockValuationCostJobResponse(
            UUID.randomUUID(), null, null, null, "QUEUED", null, null, 0, 0, 0, 0, null, null, null, null, null));
    }

    private StockValuationRunService service() {
        return new StockValuationRunService(
            repository,
            itemRepository,
            jobResultRepository,
            jobService,
            warehouseRepository,
            valuationRepository
        );
    }
    @Test
    void getStatisticsRetrievesAggregationsFromRepositoriesAndReturnsZeroesForNulls() {
        TenantContext.setTenant("tenant-b");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);

        when(repository.getStatistics("tenant-b", companyId)).thenReturn(null);
        when(itemRepository.getGlobalStatisticsNative("tenant-b", companyId)).thenReturn(null);

        var response = service().getStatistics();

        assertThat(response.getTotalRuns()).isZero();
        assertThat(response.getCompletedRuns()).isZero();
        assertThat(response.getFailedRuns()).isZero();
        assertThat(response.getCurrentGlobalStockValue()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.getValuatedMaterialsCount()).isZero();
    }

    @Test
    void getStatisticsPopulatesResponseWithRealAggregations() {
        TenantContext.setTenant("tenant-c");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);

        com.enterprise.mm.payload.RunStatsDto runStats = new com.enterprise.mm.payload.RunStatsDto(15L, 10L, 5L);
        com.enterprise.mm.payload.ValuationStatsProjection valStats = org.mockito.Mockito.mock(com.enterprise.mm.payload.ValuationStatsProjection.class);
        when(valStats.getTotalValue()).thenReturn(new BigDecimal("12345.67"));
        when(valStats.getMaterialsCount()).thenReturn(42L);

        when(repository.getStatistics("tenant-c", companyId)).thenReturn(runStats);
        when(itemRepository.getGlobalStatisticsNative("tenant-c", companyId)).thenReturn(valStats);

        var response = service().getStatistics();

        assertThat(response.getTotalRuns()).isEqualTo(15L);
        assertThat(response.getCompletedRuns()).isEqualTo(10L);
        assertThat(response.getFailedRuns()).isEqualTo(5L);
        assertThat(response.getCurrentGlobalStockValue()).isEqualTo(new BigDecimal("12345.67"));
        assertThat(response.getValuatedMaterialsCount()).isEqualTo(42L);
    }
}
