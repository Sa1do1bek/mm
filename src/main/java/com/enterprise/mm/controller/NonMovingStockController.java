package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BusinessActionDtos.NonMovingStockActionRequest;
import com.enterprise.mm.payload.NonMovingStockCaseResponse;
import com.enterprise.mm.payload.NonMovingStockResponse;
import com.enterprise.mm.payload.NonMovingStockUpdateRequest;
import com.enterprise.mm.payload.NonMovingStockCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.NonMovingStockSearchRequest;
import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.service.NonMovingStockCaseService;
import com.enterprise.mm.service.NonMovingStockService;
import com.enterprise.mm.service.AiAnalysisJobService;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/non-moving-stock")
@RequiredArgsConstructor
public class NonMovingStockController {
    private final NonMovingStockService service;
    private final NonMovingStockCaseService caseService;
    private final AiAnalysisJobService aiAnalysisJobService;

    @GetMapping
    public ApiResponse<PageResponse<NonMovingStockResponse>> search(
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
        NonMovingStockSearchRequest request = new NonMovingStockSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/transfer-warehouses")
    public ApiResponse<PageResponse<WarehouseResponse>> transferWarehouses(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "200") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        return ApiResponse.ok(
            PageResponse.from(service.searchTransferWarehouses(search, pageable))
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<NonMovingStockResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NonMovingStockResponse> create(@Valid @RequestBody NonMovingStockCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<NonMovingStockResponse> update(@PathVariable UUID id, @Valid @RequestBody NonMovingStockUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<NonMovingStockResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/analyze")
    public ApiResponse<List<NonMovingStockResponse>> analyze() {
        return ApiResponse.ok(service.analyze());
    }

    @PostMapping("/ai/recommendations/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> recommendationsAiJob(
        @RequestBody(required = false) AiAnalysisJobService.NonMovingStockAiRequest request,
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        var scope = request == null ? new AiAnalysisJobService.NonMovingStockAiRequest(null, null, null) : request;
        return ApiResponse.ok(aiAnalysisJobService.startNonMovingStockJob(
            scope.materialGroupId(),
            scope.departmentId(),
            scope.warehouseId(),
            language
        ));
    }

    @PostMapping("/{id}/action")
    public ApiResponse<NonMovingStockResponse> action(@PathVariable UUID id, @RequestBody NonMovingStockActionRequest request) {
        return ApiResponse.ok(service.applyAction(id, request.action(), request.comment()));
    }

    @PostMapping("/{id}/create-case")
    public ApiResponse<NonMovingStockCaseResponse> createCase(@PathVariable UUID id) {
        return ApiResponse.created(caseService.createCaseFromDetected(List.of(id)));
    }

}
