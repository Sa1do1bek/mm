package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ConsignmentStockTransactionResponse;
import com.enterprise.mm.payload.InventoryPlanningDtos.ConsignmentConsumptionRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.VmiReviewResult;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.VmiStockAgreementResponse;
import com.enterprise.mm.payload.VmiStockAgreementUpdateRequest;
import com.enterprise.mm.payload.VmiStockAgreementCreateRequest;
import com.enterprise.mm.payload.VmiStockAgreementSearchRequest;
import com.enterprise.mm.service.VmiStockAgreementService;
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
@RequestMapping("/api/inventory/vmi-agreements")
@RequiredArgsConstructor
public class VmiStockAgreementController {
    private final VmiStockAgreementService service;

    @GetMapping
    public ApiResponse<PageResponse<VmiStockAgreementResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.UUID supplierId,
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
        VmiStockAgreementSearchRequest request = new VmiStockAgreementSearchRequest(search, status, warehouseId, supplierId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<VmiStockAgreementResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VmiStockAgreementResponse> create(@Valid @RequestBody VmiStockAgreementCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<VmiStockAgreementResponse> update(@PathVariable UUID id, @Valid @RequestBody VmiStockAgreementUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<VmiStockAgreementResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<VmiReviewResult> review(@PathVariable UUID id) {
        return ApiResponse.ok(service.review(id));
    }

    @PostMapping("/review-all")
    public ApiResponse<List<VmiReviewResult>> reviewAll() {
        return ApiResponse.ok(service.reviewAll());
    }

    @PostMapping("/{id}/consume-consignment")
    public ApiResponse<ConsignmentStockTransactionResponse> consumeConsignment(@PathVariable UUID id, @RequestBody ConsignmentConsumptionRequest request) {
        return ApiResponse.ok(service.consumeConsignment(id, request));
    }

}

