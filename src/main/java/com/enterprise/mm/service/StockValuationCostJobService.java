package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockValuationCostJob;
import com.enterprise.mm.entity.StockValuationCostJobResult;
import com.enterprise.mm.event.StockValuationCostJobCreatedEvent;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.StockValuationCostJobResponse;
import com.enterprise.mm.payload.StockValuationCostJobResultResponse;
import com.enterprise.mm.repository.StockValuationCostJobRepository;
import com.enterprise.mm.repository.StockValuationCostJobResultRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockValuationCostJobService {
    private final StockValuationCostJobRepository jobRepository;
    private final StockValuationCostJobResultRepository resultRepository;
    private final StockValuationCostJobCreationService creationService;
    private final ApplicationEventPublisher eventPublisher;
    private final WarehouseRepository warehouseRepository;

    @Transactional
    public StockValuationCostJobResponse startJob(UUID warehouseId) {
        return startJob(warehouseId, List.of(), null);
    }

    @Transactional
    public StockValuationCostJobResponse startJob(UUID warehouseId, ValuationMethod valuationMethod) {
        return startJob(warehouseId, List.of(), valuationMethod);
    }

    @Transactional
    public StockValuationCostJobResponse startJob(UUID warehouseId, List<UUID> warehouseIds, ValuationMethod valuationMethod) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<UUID> normalizedWarehouseIds = warehouseIds == null ? List.of() : List.copyOf(warehouseIds);
        if (warehouseId != null) {
            warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, warehouseId)
                .orElseThrow(() -> new NotFoundException("Warehouse not found"));
        }
        var existing = jobRepository.findActive(tenantId, companyId, warehouseId);
        if (existing.isPresent()) {
            return toResponse(existingOrConflict(existing.get(), normalizedWarehouseIds, valuationMethod));
        }

        try {
            StockValuationCostJob created = creationService.create(
                tenantId, companyId, warehouseId, normalizedWarehouseIds, valuationMethod, requestedBy());
            eventPublisher.publishEvent(new StockValuationCostJobCreatedEvent(created.getId()));
            return toResponse(created);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findActive(tenantId, companyId, warehouseId)
                .map(job -> toResponse(existingOrConflict(job, normalizedWarehouseIds, valuationMethod)))
                .orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public StockValuationCostJobResponse getJob(UUID jobId) {
        return toResponse(findJob(jobId));
    }

    @Transactional(readOnly = true)
    public Page<StockValuationCostJobResponse> listJobs(Pageable pageable) {
        return jobRepository.findHistory(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<StockValuationCostJobResultResponse> getResults(UUID jobId, Pageable pageable) {
        findJob(jobId);
        return resultRepository.findByJobIdOrderBySequenceNoAsc(jobId, pageable).map(this::toResultResponse);
    }

    private StockValuationCostJob findJob(UUID jobId) {
        return jobRepository.findScoped(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Stock valuation cost job not found"));
    }

    private StockValuationCostJobResponse toResponse(StockValuationCostJob job) {
        return new StockValuationCostJobResponse(
            job.getId(),
            job.getWarehouseId(),
            job.getValuationMethod(),
            job.getRequestedBy(),
            job.getStatus() == null ? null : job.getStatus().name(),
            job.getPhase() == null ? null : job.getPhase().name(),
            job.getProgressPercent(),
            job.getTotalItems(),
            job.getProcessedItems(),
            job.getSuccessfulItems(),
            job.getFailedItems(),
            job.getErrorMessage(),
            job.getStartedAt(),
            job.getFinishedAt(),
            job.getCreatedAt(),
            job.getUpdatedAt()
        );
    }

    private StockValuationCostJobResultResponse toResultResponse(StockValuationCostJobResult result) {
        return new StockValuationCostJobResultResponse(
            result.getMaterialId(),
            result.getMaterialName(),
            result.getWarehouseId(),
            result.getWarehouseName(),
            result.getQuantity(),
            result.getValuationMethod(),
            result.getTotalCost(),
            result.isAvailable(),
            result.getErrorMessage()
        );
    }

    private String requestedBy() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null || authentication.getName().isBlank()
            ? "system"
            : authentication.getName();
    }

    private StockValuationCostJob existingOrConflict(StockValuationCostJob job, List<UUID> requestedWarehouseIds, ValuationMethod requestedMethod) {
        if (!new java.util.HashSet<>(job.getWarehouseIds()).equals(new java.util.HashSet<>(requestedWarehouseIds))) {
            throw new BadRequestException("An active valuation job already exists for a different warehouse selection");
        }
        if (!java.util.Objects.equals(job.getValuationMethod(), requestedMethod)) {
            String label = job.getValuationMethod() == null ? "material policy" : job.getValuationMethod().name();
            throw new BadRequestException("An active valuation job already exists for this scope with method " + label);
        }
        return job;
    }
}
