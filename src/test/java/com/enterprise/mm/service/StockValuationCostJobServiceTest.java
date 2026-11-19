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
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.entity.StockValuationCostJobResult;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.StockValuationCostJobPhase;
import com.enterprise.mm.enums.StockValuationCostJobStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.StockValuationCostJobResponse;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class StockValuationCostJobServiceTest {
    private static final UUID COMPANY_ID = UUID.randomUUID();

    @Mock
    private StockValuationCostJobRepository jobRepository;
    @Mock
    private StockValuationCostJobResultRepository resultRepository;
    @Mock
    private StockValuationCostJobCreationService creationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WarehouseRepository warehouseRepository;

    private StockValuationCostJobService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "password"));
        service = new StockValuationCostJobService(jobRepository, resultRepository, creationService, eventPublisher,
            warehouseRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void startJobCreatesQueuedWaitingJobAndReturnsAcceptedShape() {
        UUID warehouseId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "tenant-a", COMPANY_ID, warehouseId)).thenReturn(Optional.of(new Warehouse()));
        when(jobRepository.findActive("tenant-a", COMPANY_ID, warehouseId))
            .thenReturn(Optional.empty());
        StockValuationCostJob created = job(jobId, warehouseId);
        when(creationService.create("tenant-a", COMPANY_ID, warehouseId, List.of(), null, "alice")).thenReturn(created);

        StockValuationCostJobResponse response = service.startJob(warehouseId);

        assertThat(response.id()).isEqualTo(jobId);
        assertThat(response.warehouseId()).isEqualTo(warehouseId);
        assertThat(response.valuationMethod()).isNull();
        assertThat(response.requestedBy()).isEqualTo("alice");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.phase()).isEqualTo("WAITING");
        assertThat(response.progressPercent()).isEqualByComparingTo("0.00");
        assertThat(response.totalItems()).isZero();
        assertThat(response.processedItems()).isZero();
        assertThat(response.successfulItems()).isZero();
        assertThat(response.failedItems()).isZero();

        verify(creationService).create("tenant-a", COMPANY_ID, warehouseId, List.of(), null, "alice");
    }

    @Test
    void startJobRejectsWarehouseOutsideCurrentTenantAndCompany() {
        UUID warehouseId = UUID.randomUUID();
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "tenant-a", COMPANY_ID, warehouseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startJob(warehouseId))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Warehouse not found");
        verify(jobRepository, never()).findActive(any(), any(), any());
        verify(creationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startJobWithoutWarehouseUsesAllWarehouseDuplicateScope() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob existing = job(jobId, null);
        when(jobRepository.findActive("tenant-a", COMPANY_ID, null))
            .thenReturn(Optional.of(existing));

        StockValuationCostJobResponse response = service.startJob(null);

        assertThat(response.id()).isEqualTo(jobId);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void startJobDoesNotReuseActiveJobForDifferentWarehouseSelection() {
        UUID selectedWarehouse = UUID.randomUUID();
        StockValuationCostJob existing = job(UUID.randomUUID(), null);
        existing.getWarehouseIds().add(selectedWarehouse);
        when(jobRepository.findActive("tenant-a", COMPANY_ID, null)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startJob(null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("different warehouse selection");
        verify(creationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startJobReloadsWinningActiveJobAfterUniqueConstraintRace() {
        UUID warehouseId = UUID.randomUUID();
        StockValuationCostJob winner = job(UUID.randomUUID(), warehouseId);
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "tenant-a", COMPANY_ID, warehouseId)).thenReturn(Optional.of(new Warehouse()));
        when(jobRepository.findActive("tenant-a", COMPANY_ID, warehouseId))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(creationService.create("tenant-a", COMPANY_ID, warehouseId, List.of(), null, "alice"))
            .thenThrow(new DataIntegrityViolationException("active job already exists"));

        StockValuationCostJobResponse response = service.startJob(warehouseId);

        assertThat(response.id()).isEqualTo(winner.getId());
        verify(creationService).create("tenant-a", COMPANY_ID, warehouseId, List.of(), null, "alice");
    }

    @Test
    void startJobNormalizesRequestedValuationMethod() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob created = job(jobId, null);
        created.setValuationMethod(com.enterprise.mm.enums.ValuationMethod.FIFO);
        when(jobRepository.findActive("tenant-a", COMPANY_ID, null))
            .thenReturn(Optional.empty());
        when(creationService.create("tenant-a", COMPANY_ID, null, List.of(), com.enterprise.mm.enums.ValuationMethod.FIFO, "alice")).thenReturn(created);

        StockValuationCostJobResponse response = service.startJob(null, ValuationMethod.FIFO);

        assertThat(response.valuationMethod()).isEqualTo(com.enterprise.mm.enums.ValuationMethod.FIFO);
        verify(creationService).create("tenant-a", COMPANY_ID, null, List.of(), com.enterprise.mm.enums.ValuationMethod.FIFO, "alice");
    }

    @Test
    void getJobTreatsWrongTenantOrCompanyAsNotFound() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findScoped(jobId, "tenant-a", COMPANY_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getJob(jobId))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Stock valuation cost job not found");
        verify(jobRepository).findScoped(jobId, "tenant-a", COMPANY_ID);
    }

    @Test
    void listJobsIsTenantAndCompanyScoped() {
        StockValuationCostJob first = job(UUID.randomUUID(), null);
        StockValuationCostJob second = job(UUID.randomUUID(), UUID.randomUUID());
        PageRequest pageable = PageRequest.of(0, 20);
        when(jobRepository.findHistory("tenant-a", COMPANY_ID, pageable))
            .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));

        var page = service.listJobs(pageable);

        assertThat(page.getContent()).extracting(StockValuationCostJobResponse::id)
            .containsExactly(first.getId(), second.getId());
        verify(jobRepository).findHistory("tenant-a", COMPANY_ID, pageable);
    }

    @Test
    void getResultsIsTenantAndCompanyScopedAndOrderedBySequence() {
        UUID jobId = UUID.randomUUID();
        StockValuationCostJob job = job(jobId, null);
        StockValuationCostJobResult first = result(jobId, 1, "Steel");
        StockValuationCostJobResult second = result(jobId, 2, "Copper");
        PageRequest pageable = PageRequest.of(0, 10);
        when(jobRepository.findScoped(jobId, "tenant-a", COMPANY_ID)).thenReturn(Optional.of(job));
        when(resultRepository.findByJobIdOrderBySequenceNoAsc(jobId, pageable))
            .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));

        var page = service.getResults(jobId, pageable);

        assertThat(page.getContent()).extracting(item -> item.materialName())
            .containsExactly("Steel", "Copper");
        verify(resultRepository).findByJobIdOrderBySequenceNoAsc(jobId, pageable);
    }

    private StockValuationCostJob job(UUID id, UUID warehouseId) {
        StockValuationCostJob job = new StockValuationCostJob();
        job.setId(id);
        job.setTenantId("tenant-a");
        job.setCompanyId(COMPANY_ID);
        job.setWarehouseId(warehouseId);
        job.setRequestedBy("alice");
        job.setStatus(StockValuationCostJobStatus.QUEUED);
        job.setPhase(StockValuationCostJobPhase.WAITING);
        job.setProgressPercent(new BigDecimal("0.00"));
        job.setTotalItems(0);
        job.setProcessedItems(0);
        job.setSuccessfulItems(0);
        job.setFailedItems(0);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    private StockValuationCostJobResult result(UUID jobId, int sequenceNo, String materialName) {
        StockValuationCostJobResult result = new StockValuationCostJobResult();
        result.setJobId(jobId);
        result.setSequenceNo(sequenceNo);
        result.setMaterialName(materialName);
        result.setQuantity(BigDecimal.ONE);
        result.setAvailable(true);
        return result;
    }
}
