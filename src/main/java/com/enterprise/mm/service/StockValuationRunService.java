package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.StockValuationRunScope;
import com.enterprise.mm.enums.StockValuationRunStatus;
import com.enterprise.mm.enums.StockValuationRunItemStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.StockValuationCostJobResponse;
import com.enterprise.mm.payload.StockValuationCostJobResultResponse;
import com.enterprise.mm.payload.StockValuationRunCreateRequest;
import com.enterprise.mm.payload.StockValuationRunItemResponse;
import com.enterprise.mm.payload.StockValuationRunResponse;
import com.enterprise.mm.payload.StockValuationRunUpdateRequest;
import com.enterprise.mm.payload.StockValuationStatisticsResponse;
import com.enterprise.mm.payload.WarehouseIDAndNameDto;
import com.enterprise.mm.payload.RunStatsDto;
import com.enterprise.mm.payload.ValuationStatsProjection;
import com.enterprise.mm.repository.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationRunService {
    private static final Set<String> RUN_STATUSES = java.util.Arrays.stream(StockValuationRunStatus.values())
        .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> ITEM_STATUSES = java.util.Arrays.stream(StockValuationRunItemStatus.values())
        .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final StockValuationRunRepository repository;
    private final StockValuationRunItemRepository itemRepository;
    private final StockValuationCostJobResultRepository jobResultRepository;
    private final StockValuationCostJobService jobService;
    private final WarehouseRepository warehouseRepository;
    private final StockValuationRepository valuationRepository;

    @Transactional(readOnly = true)
    public Page<StockValuationRunResponse> search(
        String search,
        List<String> status,
        UUID warehouseId,
        StockValuationRunScope scopeType,
        ValuationMethod valuationMethod,
        LocalDate dateFrom,
        LocalDate dateTo,
        boolean includeDeleted,
        Pageable pageable
    ) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BadRequestException("dateFrom must not be after dateTo");
        }
        List<String> normalizedStatus = normalizeStatuses(status, RUN_STATUSES);
        Page<StockValuationRunRepository.RunWithJob> pageResult = repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            includeDeleted,
            searchPattern(search),
            normalizedStatus,
            normalizedStatus.isEmpty(),
            warehouseId,
            scopeType,
            valuationMethod,
            dateFrom == null ? null : dateFrom.atStartOfDay(),
            dateTo == null ? null : dateTo.atTime(LocalTime.MAX),
            pageable
        );

        Set<UUID> allWarehouseIds = new java.util.HashSet<>();
        for (StockValuationRunRepository.RunWithJob row : pageResult.getContent()) {
            if (row.getRun().getWarehouseId() != null) {
                allWarehouseIds.add(row.getRun().getWarehouseId());
            }
            if (row.getRun().getWarehouseIds() != null) {
                allWarehouseIds.addAll(row.getRun().getWarehouseIds());
            }
        }

        Map<UUID, WarehouseIDAndNameDto> warehouseMap = new java.util.HashMap<>();
        if (!allWarehouseIds.isEmpty()) {
            warehouseRepository.findByTenantIdAndCompanyIdAndIdIn(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                allWarehouseIds
            ).forEach(w -> warehouseMap.put(w.getId(), new WarehouseIDAndNameDto(w.getId(), LocalizedText.forCurrentLocale(w.getName()))));
        }

        return pageResult.map(row -> {
            StockValuationRunResponse response = toResponse(row.getRun(), warehouseMap);
            StockValuationCostJob job = row.getJob();
            if (job != null) {
                response.setStatus(job.getStatus().name());
                response.setStartedAt(job.getStartedAt());
                response.setFinishedAt(job.getFinishedAt());
                response.setErrorMessage(job.getErrorMessage());
                response.setProgressPercent(job.getProgressPercent());
            }
            return response;
        });
    }

    @Transactional
    public StockValuationRunResponse get(UUID id) {
        StockValuationRun run = findRun(id);
        syncFromJob(run);
        return toResponse(run);
    }

    @Transactional
    public StockValuationRunResponse create(StockValuationRunCreateRequest request) {
        StockValuationRun run = new StockValuationRun();
        apply(run, request, false);
        run.setStatus(StockValuationRun.STATUS_DRAFT);
        if (run.getCode() == null || run.getCode().isBlank()) {
            run.setCode("VALRUN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }

        UUID warehouseId = run.getScopeType() == StockValuationRunScope.WAREHOUSE
                ? run.getWarehouseId()
                : null;
        List<UUID> warehouseIds = run.getScopeType() == StockValuationRunScope.MULTI_WAREHOUSE
                ? List.copyOf(run.getWarehouseIds())
                : List.of();
        StockValuationCostJobResponse job =  jobService.startJob(warehouseId, warehouseIds, run.getValuationMethod());
        run.setJobId(job.id());
        run.setStatus(job.status());
        run.setStartedAt(job.startedAt());
        run.setFinishedAt(job.finishedAt());
        run.setErrorMessage(job.errorMessage());
        StockValuationRun saved = repository.save(run);

        return toResponse(saved);
    }

    @Transactional
    public StockValuationRunResponse update(UUID id, StockValuationRunUpdateRequest request) {
        StockValuationRun run = findRun(id);
        if (!StockValuationRun.STATUS_DRAFT.equals(run.getStatus())) {
            throw new BadRequestException("Only draft valuation runs can be updated");
        }
        apply(run, request, true);
        return toResponse(repository.save(run));
    }

    @Transactional
    public void softDelete(UUID id) {
        StockValuationRun run = findRun(id);
        if (StockValuationRun.STATUS_RUNNING.equals(run.getStatus()) || StockValuationRun.STATUS_QUEUED.equals(run.getStatus())) {
            throw new BadRequestException("Active valuation runs cannot be deleted");
        }
        run.softDelete();
        repository.save(run);
    }

    @Transactional
    public StockValuationRunResponse restore(UUID id) {
        StockValuationRun run = repository.findById(id)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Stock valuation run not found"));
        run.restore();
        return toResponse(repository.save(run));
    }

    @Transactional
    public StockValuationRunResponse start(UUID id) {
        StockValuationRun run = findRun(id);
        if (run.getJobId() != null) {
            syncFromJob(run);
            return toResponse(run);
        }
        UUID warehouseId = run.getScopeType() == StockValuationRunScope.WAREHOUSE
            ? run.getWarehouseId()
            : null;
        List<UUID> warehouseIds = run.getScopeType() == StockValuationRunScope.MULTI_WAREHOUSE
            ? List.copyOf(run.getWarehouseIds())
            : List.of();
        StockValuationCostJobResponse job = jobService.startJob(warehouseId, warehouseIds, run.getValuationMethod());
        run.setJobId(job.id());
        run.setStatus(job.status());
        run.setStartedAt(job.startedAt());
        run.setFinishedAt(job.finishedAt());
        run.setErrorMessage(job.errorMessage());
        return toResponse(repository.save(run));
    }

    @Transactional
    public Page<StockValuationRunItemResponse> items(
        UUID runId, String search, UUID warehouseId, List<String> status, Pageable pageable
    ) {
        List<String> statuses = normalizeStatuses(status, ITEM_STATUSES);
        Boolean available = statuses.size() == 1
            ? StockValuationRunItemStatus.valueOf(statuses.getFirst()).isAvailable() : null;
        StockValuationRun run = findRun(runId);
        syncFromJob(run);
        return itemRepository.search(runId, TenantContext.currentTenant(), CompanyContext.currentCompany(),
                searchPattern(search), warehouseId, available, pageable)
            .map(this::toItemResponse);
    }

    private void syncFromJob(StockValuationRun run) {
        if (run.getJobId() == null) {
            return;
        }
        syncStatusFromJob(run);

        if (isActive(run.getStatus())) {
            return;
        }

        long existing = itemRepository.countByValuationRunId(run.getId());
        int sequenceNo = 1;
        for (StockValuationCostJobResult result : jobResultRepository.findByJobIdOrderBySequenceNoAsc(run.getJobId())) {
            if (sequenceNo <= existing) {
                sequenceNo++;
                continue;
            }
            itemRepository.save(StockValuationRunItem.fromJobResult(
                run.getId(),
                sequenceNo++,
                new StockValuationCostJobResultResponse(
                    result.getMaterialId(),
                    result.getMaterialName(),
                    result.getWarehouseId(),
                    result.getWarehouseName(),
                    result.getQuantity(),
                    result.getValuationMethod(),
                    result.getTotalCost(),
                    result.isAvailable(),
                    result.getErrorMessage()
                )
            ));
        }
    }

    private void syncStatusFromJob(StockValuationRun run) {
        if (run.getJobId() == null || !isActive(run.getStatus())) {
            return;
        }
        StockValuationCostJobResponse job = jobService.getJob(run.getJobId());
        boolean changed = !Objects.equals(run.getStatus(), job.status())
            || !Objects.equals(run.getStartedAt(), job.startedAt())
            || !Objects.equals(run.getFinishedAt(), job.finishedAt())
            || !Objects.equals(run.getErrorMessage(), job.errorMessage());
        run.setStatus(job.status());
        run.setStartedAt(job.startedAt());
        run.setFinishedAt(job.finishedAt());
        run.setErrorMessage(job.errorMessage());
        run.setProgressPercent(job.progressPercent());
        if (changed) {
            repository.save(run);
        }
    }

    private boolean isActive(String status) {
        return StockValuationRun.STATUS_QUEUED.equals(status) || StockValuationRun.STATUS_RUNNING.equals(status);
    }

    private void apply(StockValuationRun run, StockValuationRunCreateRequest request, boolean preserveCode) {
        validateScope(request.getScopeType(), request.getWarehouseId(), request.getWarehouseIds());
        if (!preserveCode) {
            run.setCode(blankToNull(request.getCode()));
        }
        Warehouse warehouse = null;
        if (request.getScopeType() == StockValuationRunScope.WAREHOUSE) {
            warehouse = warehouseRepository
                .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), request.getWarehouseId())
                .orElseThrow(() -> new NotFoundException("Warehouse not found"));
        }
        run.setName(resolveName(run, request, warehouse));
        run.setDescription(request.getDescription());
        run.setScopeType(request.getScopeType());
        run.setWarehouseId(request.getScopeType() == StockValuationRunScope.WAREHOUSE
            ? request.getWarehouseId()
            : null);
        run.getWarehouseIds().clear();
        if (request.getScopeType() == StockValuationRunScope.MULTI_WAREHOUSE
                && request.getWarehouseIds() != null) {
            run.getWarehouseIds().addAll(request.getWarehouseIds());
        }
        run.setValuationMethod(request.getValuationMethod());
        run.setDescriptionUz(request.getDescriptionUz());
        run.setDescriptionRu(request.getDescriptionRu());
        run.setDescriptionEn(request.getDescriptionEn());
    }

    private void validateScope(StockValuationRunScope scopeType, UUID warehouseId, List<UUID> warehouseIds) {
        if (scopeType == null) {
            throw new BadRequestException("scopeType is required");
        }
        switch (scopeType) {
            case GLOBAL -> {
                if (warehouseId != null || (warehouseIds != null && !warehouseIds.isEmpty())) {
                    throw new BadRequestException("warehouseId and warehouseIds must be empty for GLOBAL valuation runs");
                }
            }
            case WAREHOUSE -> {
                if (warehouseId == null) {
                    throw new BadRequestException("warehouseId is required for WAREHOUSE valuation runs");
                }
            }
            case MULTI_WAREHOUSE -> {
                if (warehouseIds != null && warehouseIds.size() > 10) {
                    throw new BadRequestException("At most 10 warehouses can be selected");
                }

                if (warehouseId != null) {
                    throw new BadRequestException("warehouseId must be empty for MULTI_WAREHOUSE valuation runs; use warehouseIds instead");
                }
                String tenantId = TenantContext.currentTenant();
                UUID companyId = CompanyContext.currentCompany();
                for (UUID wid : warehouseIds) {
                    warehouseRepository
                        .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, wid)
                        .orElseThrow(() -> new NotFoundException("Warehouse not found: " + wid));
                }
            }
        }
    }

    private StockValuationRun findRun(UUID id) {
        return repository.findScoped(id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Stock valuation run not found"));
    }

    private StockValuationRunResponse toResponse(StockValuationRun run) {
        Set<UUID> ids = new java.util.HashSet<>();
        if (run.getWarehouseId() != null) ids.add(run.getWarehouseId());
        if (run.getWarehouseIds() != null) ids.addAll(run.getWarehouseIds());

        Map<UUID, WarehouseIDAndNameDto> map = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            warehouseRepository.findByTenantIdAndCompanyIdAndIdIn(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), ids)
                .forEach(w -> map.put(w.getId(), new WarehouseIDAndNameDto(w.getId(), LocalizedText.forCurrentLocale(w.getName()))));
        }
        return toResponse(run, map);
    }

    private StockValuationRunResponse toResponse(StockValuationRun run, Map<UUID, WarehouseIDAndNameDto> warehouseMap) {
        StockValuationRunResponse response = new StockValuationRunResponse();
        response.setId(run.getId());
        response.setTenantId(run.getTenantId());
        response.setCompanyId(run.getCompanyId());
        response.setName(LocalizedText.forCurrentLocale(run.getName()));
        Map<String, String> names = nameTranslations(run.getName());
        response.setNameUz(names.get("uz"));
        response.setNameRu(names.get("ru"));
        response.setNameEn(names.get("en"));
        response.setStatus(run.getStatus());
        response.setDescription(run.getDescription());
        response.setDeleted(run.isDeleted());
        response.setCreatedAt(run.getCreatedAt());
        response.setUpdatedAt(run.getUpdatedAt());
        response.setCreatedBy(run.getCreatedBy());
        response.setUpdatedBy(run.getUpdatedBy());
        response.setVersion(run.getVersion());
        response.setScopeType(run.getScopeType());
        
        List<WarehouseIDAndNameDto> mappedWarehouses = new java.util.ArrayList<>();
        if (run.getWarehouseId() != null && warehouseMap.containsKey(run.getWarehouseId())) {
            mappedWarehouses.add(warehouseMap.get(run.getWarehouseId()));
        }
        if (run.getWarehouseIds() != null && !run.getWarehouseIds().isEmpty()) {
            run.getWarehouseIds().stream()
                .map(warehouseMap::get)
                .filter(Objects::nonNull)
                .forEach(mappedWarehouses::add);
        }
        response.setWarehouses(mappedWarehouses.isEmpty() ? null : mappedWarehouses);
        
        response.setValuationMethod(run.getValuationMethod());
        response.setDescriptionUz(run.getDescriptionUz());
        response.setDescriptionRu(run.getDescriptionRu());
        response.setDescriptionEn(run.getDescriptionEn());
        response.setJobId(run.getJobId());
        response.setStartedAt(run.getStartedAt());
        response.setFinishedAt(run.getFinishedAt());
        response.setErrorMessage(run.getErrorMessage());
        response.setProgressPercent(run.getProgressPercent());
        return response;
    }

    private StockValuationRunItemResponse toItemResponse(StockValuationRunItem item) {
        String unitName = null;
        if (item.getMaterial() != null && item.getMaterial().getUnitOfMeasure() != null) {
            unitName = item.getMaterial().getUnitOfMeasure().getMeasurementNameShort();
        }

        return new StockValuationRunItemResponse(
            item.getId(),
            item.getMaterialId(),
            item.getMaterialName(),
            item.getWarehouseId(),
            item.getWarehouseName(),
            item.getQuantity(),
            unitName,
            item.getValuationMethod(),
            item.getUnitCost(),
            item.getTotalValue(),
            item.getCarryingValue(),
            item.isAvailable(),
            item.getErrorMessage(),
            item.getSequenceNo(),
            item.getCalculatedAt()
        );
    }

    private String resolveName(StockValuationRun run, StockValuationRunCreateRequest request, Warehouse warehouse) {
        if (request.getNameUz() != null || request.getNameRu() != null || request.getNameEn() != null) {
            Map<String, String> existing = nameTranslations(firstNonBlank(run.getName(), request.getName(), ""));
            String formatted = LocalizedText.format(
                request.getNameRu() == null ? existing.get("ru") : blankToNull(request.getNameRu()),
                request.getNameEn() == null ? existing.get("en") : blankToNull(request.getNameEn()),
                request.getNameUz() == null ? existing.get("uz") : blankToNull(request.getNameUz())
            );
            // Match BusinessEntity's persistence normalization, including missing-language fallback.
            String complete = LocalizedText.ensureComplete(formatted);
            return complete == null ? generatedName(request.getScopeType(), warehouse) : complete;
        }
        return firstNonBlank(request.getName(), run.getName(), generatedName(request.getScopeType(), warehouse));
    }

    private Map<String, String> nameTranslations(String name) {
        if (LocalizedText.isMultilingual(name)) {
            return LocalizedText.parse(name);
        }
        String legacy = name == null ? "" : name;
        return Map.of("uz", legacy, "ru", legacy, "en", legacy);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String generatedName(StockValuationRunScope scopeType, Warehouse warehouse) {
        LocalDate date = LocalDate.now();
        if (scopeType == StockValuationRunScope.GLOBAL) {
            return LocalizedText.format(
                "Оценка запасов: все склады, " + date,
                "Inventory valuation: all warehouses, " + date,
                "Zaxiralarni baholash: barcha omborlar, " + date
            );
        }
        if (scopeType == StockValuationRunScope.MULTI_WAREHOUSE) {
            return LocalizedText.format(
                "Оценка запасов: несколько складов, " + date,
                "Inventory valuation: multiple warehouses, " + date,
                "Zaxiralarni baholash: bir nechta omborlar, " + date
            );
        }
        String warehouseLabel = compactLabel(firstNonBlank(warehouse.getCode(), warehouse.getName(), "warehouse"));
        return LocalizedText.format(
            "Оценка запасов: " + warehouseLabel + ", " + date,
            "Inventory valuation: " + warehouseLabel + ", " + date,
            "Zaxiralarni baholash: " + warehouseLabel + ", " + date
        );
    }

    private String compactLabel(String value) {
        String normalized = value.trim();
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String searchPattern(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : "%" + normalized.toLowerCase(Locale.ROOT)
            .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private List<String> normalizeStatuses(List<String> values, Set<String> allowed) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
            .map(value -> value.toUpperCase(Locale.ROOT))
            .map(value -> {
                if (!allowed.contains(value)) throw new BadRequestException("Unsupported valuation status: " + value);
                return value;
            }).distinct().toList();
    }

    public StockValuationStatisticsResponse getStatistics() {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        RunStatsDto runStats = repository.getStatistics(tenantId, companyId);
        ValuationStatsProjection valuationStats = itemRepository.getGlobalStatisticsNative(tenantId, companyId);

        return StockValuationStatisticsResponse.builder()
            .totalRuns(runStats != null && runStats.totalRuns() != null ? runStats.totalRuns() : 0L)
            .completedRuns(runStats != null && runStats.completedRuns() != null ? runStats.completedRuns() : 0L)
            .failedRuns(runStats != null && runStats.failedRuns() != null ? runStats.failedRuns() : 0L)
            .currentGlobalStockValue(valuationStats != null && valuationStats.getTotalValue() != null ? valuationStats.getTotalValue() : java.math.BigDecimal.ZERO)
            .valuatedMaterialsCount(valuationStats != null && valuationStats.getMaterialsCount() != null ? valuationStats.getMaterialsCount() : 0L)
            .build();
    }
}
