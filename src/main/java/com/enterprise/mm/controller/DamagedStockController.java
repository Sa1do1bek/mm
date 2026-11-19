package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.entity.DamagedStockCase.Status;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DamagedStockDtos.*;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.DamagedStockService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/damaged-stock")
public class DamagedStockController {
    private final DamagedStockService service;

    @GetMapping
    public ApiResponse<PageResponse<CaseView>> list(
        @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) Status status,
        @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.list(warehouseId, status, search,
            PaginationUtils.pageRequest(page, size, Sort.unsorted()))));
    }

    @GetMapping("/{id}")
    public ApiResponse<CaseView> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CaseView> report(@Valid @RequestBody Report request, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.created(service.report(request, key));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<CaseView> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<CaseView> release(@PathVariable UUID id, @Valid @RequestBody Release request) {
        return ApiResponse.ok(service.release(id, request));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<CaseView> post(@PathVariable UUID id, @Valid @RequestBody Post request) {
        return ApiResponse.ok(service.post(id, request));
    }
}
