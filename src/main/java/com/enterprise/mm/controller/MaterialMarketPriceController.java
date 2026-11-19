package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchJobRequest;
import com.enterprise.mm.payload.MaterialMarketPriceCreateRequest;
import com.enterprise.mm.payload.MaterialMarketPriceResponse;
import com.enterprise.mm.payload.MaterialMarketPriceUpdateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.MaterialMarketPriceSearchRequest;
import com.enterprise.mm.service.MaterialMarketPriceService;
import com.enterprise.mm.service.AiAnalysisJobService;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market-prices")
@RequiredArgsConstructor
public class MaterialMarketPriceController {
    private static final Set<String> SORT_FIELDS = Set.of(
        "code",
        "name",
        "status",
        "materialId",
        "warehouseId",
        "marketUnitPrice",
        "netRealizableUnitPrice",
        "currencyCode",
        "effectiveDate",
        "sourceType",
        "sourceReference",
        "approvedAt",
        "createdAt",
        "updatedAt"
    );
    private final MaterialMarketPriceService service;
    private final AiAnalysisJobService aiAnalysisJobService;

    @GetMapping
    public ApiResponse<PageResponse<MaterialMarketPriceResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir
    ) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir, SORT_FIELDS, Sort.by("createdAt").descending());
        Pageable pageable = PaginationUtils.pageRequest(page, size, sort);
        MaterialMarketPriceSearchRequest request = new MaterialMarketPriceSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialMarketPriceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialMarketPriceResponse> create(@Valid @RequestBody MaterialMarketPriceCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PostMapping("/ai/research/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> researchAiJob(
        @Valid @RequestBody MarketPriceResearchJobRequest request,
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        return ApiResponse.ok(aiAnalysisJobService.startMarketPriceResearchJob(request, language));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaterialMarketPriceResponse> update(@PathVariable UUID id, @Valid @RequestBody MaterialMarketPriceUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<MaterialMarketPriceResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<MaterialMarketPriceResponse> approve(@PathVariable UUID id) {
        return ApiResponse.ok(service.approve(id));
    }
}
