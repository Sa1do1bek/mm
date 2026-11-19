package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationResponse;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationUpdateRequest;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationCreateRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.EoqCalculationRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.InventoryOptimizationRecommendationSearchRequest;
import com.enterprise.mm.service.InventoryOptimizationRecommendationService;
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

@RestController
@RequestMapping("/api/inventory/optimization")
@RequiredArgsConstructor
public class InventoryOptimizationRecommendationController {
    private final InventoryOptimizationRecommendationService service;

    @GetMapping
    public ApiResponse<PageResponse<InventoryOptimizationRecommendationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        InventoryOptimizationRecommendationSearchRequest request = new InventoryOptimizationRecommendationSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryOptimizationRecommendationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryOptimizationRecommendationResponse> create(@Valid @RequestBody InventoryOptimizationRecommendationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryOptimizationRecommendationResponse> update(@PathVariable UUID id, @Valid @RequestBody InventoryOptimizationRecommendationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<InventoryOptimizationRecommendationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/eoq")
    public ApiResponse<InventoryOptimizationRecommendationResponse> calculateEoq(@RequestBody EoqCalculationRequest request) {
        return ApiResponse.ok(service.calculateEoq(request));
    }

    @PostMapping("/{id}/apply-to-material")
    public ApiResponse<InventoryOptimizationRecommendationResponse> applyToMaterial(@PathVariable UUID id) {
        return ApiResponse.ok(service.applyToMaterial(id));
    }

}

