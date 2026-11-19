package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DemandForecastResponse;
import com.enterprise.mm.payload.DemandForecastUpdateRequest;
import com.enterprise.mm.payload.DemandForecastCreateRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.DemandForecastRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.ForecastBatchRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.ForecastBatchResult;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.DemandForecastService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.enterprise.mm.payload.DemandForecastSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/inventory/demand-forecasts")
@RequiredArgsConstructor
public class DemandForecastController {
    private final DemandForecastService service;

    @GetMapping
    public ApiResponse<PageResponse<DemandForecastResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime createdAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime createdAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedAtTo,
        @RequestParam(required = false) String createdBy,
        @RequestParam(required = false) String updatedBy,
        @RequestParam(required = false) java.util.UUID materialId,
        @RequestParam(required = false) java.util.List<String> forecastMethod,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodStartFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodStartTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodEndFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodEndTo,
        @RequestParam(required = false) Integer historyMonths,
        @RequestParam(required = false) Integer forecastMonths,
        @RequestParam(required = false) java.math.BigDecimal averageMonthlyDemandMin,
        @RequestParam(required = false) java.math.BigDecimal averageMonthlyDemandMax,
        @RequestParam(required = false) java.math.BigDecimal forecastQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal forecastQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal annualizedForecastQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal annualizedForecastQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal demandStdDevMin,
        @RequestParam(required = false) java.math.BigDecimal demandStdDevMax,
        @RequestParam(required = false) java.math.BigDecimal variationCoefficientMin,
        @RequestParam(required = false) java.math.BigDecimal variationCoefficientMax,
        @RequestParam(required = false) java.math.BigDecimal confidenceMin,
        @RequestParam(required = false) java.math.BigDecimal confidenceMax,
        @RequestParam(required = false) java.util.List<String> modelQuality,
        @RequestParam(required = false) java.math.BigDecimal trendSlopeMin,
        @RequestParam(required = false) java.math.BigDecimal trendSlopeMax,
        @RequestParam(required = false) java.math.BigDecimal seasonalityIndexMin,
        @RequestParam(required = false) java.math.BigDecimal seasonalityIndexMax,
        @RequestParam(required = false) Integer leadTimeDays,
        @RequestParam(required = false) java.math.BigDecimal serviceLevelMin,
        @RequestParam(required = false) java.math.BigDecimal serviceLevelMax,
        @RequestParam(required = false) java.math.BigDecimal leadTimeDemandMin,
        @RequestParam(required = false) java.math.BigDecimal leadTimeDemandMax,
        @RequestParam(required = false) java.math.BigDecimal recommendedSafetyStockMin,
        @RequestParam(required = false) java.math.BigDecimal recommendedSafetyStockMax,
        @RequestParam(required = false) java.math.BigDecimal recommendedReorderPointMin,
        @RequestParam(required = false) java.math.BigDecimal recommendedReorderPointMax,
        @RequestParam(required = false) java.math.BigDecimal recommendedMinStockMin,
        @RequestParam(required = false) java.math.BigDecimal recommendedMinStockMax,
        @RequestParam(required = false) java.math.BigDecimal recommendedMaxStockMin,
        @RequestParam(required = false) java.math.BigDecimal recommendedMaxStockMax,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime generatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime generatedAtTo,
        @RequestParam(required = false) String source,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        DemandForecastSearchRequest request = new DemandForecastSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, materialId, forecastMethod, periodStartFrom, periodStartTo, periodEndFrom, periodEndTo, historyMonths, forecastMonths, averageMonthlyDemandMin, averageMonthlyDemandMax, forecastQuantityMin, forecastQuantityMax, annualizedForecastQuantityMin, annualizedForecastQuantityMax, demandStdDevMin, demandStdDevMax, variationCoefficientMin, variationCoefficientMax, confidenceMin, confidenceMax, modelQuality, trendSlopeMin, trendSlopeMax, seasonalityIndexMin, seasonalityIndexMax, leadTimeDays, serviceLevelMin, serviceLevelMax, leadTimeDemandMin, leadTimeDemandMax, recommendedSafetyStockMin, recommendedSafetyStockMax, recommendedReorderPointMin, recommendedReorderPointMax, recommendedMinStockMin, recommendedMinStockMax, recommendedMaxStockMin, recommendedMaxStockMax, generatedAtFrom, generatedAtTo, source);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DemandForecastResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DemandForecastResponse> create(@Valid @RequestBody DemandForecastCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DemandForecastResponse> update(@PathVariable UUID id, @Valid @RequestBody DemandForecastUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<DemandForecastResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/generate")
    public ApiResponse<DemandForecastResponse> generate(@RequestBody DemandForecastRequest request) {
        return ApiResponse.ok(service.generate(request));
    }

    @PostMapping("/generate-batch")
    public ApiResponse<ForecastBatchResult> generateBatch(@RequestBody(required = false) ForecastBatchRequest request) {
        return ApiResponse.ok(service.generateBatch(request));
    }

    @GetMapping("/latest")
    public ApiResponse<DemandForecastResponse> latest(@RequestParam UUID materialId, @RequestParam(required = false) UUID warehouseId) {
        return ApiResponse.ok(service.latest(materialId, warehouseId));
    }

}

