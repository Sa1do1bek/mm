package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.IlliquidStockPolicyCreateRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyResolvePreviewRequest;
import com.enterprise.mm.payload.IlliquidStockPolicyResolvePreviewResponse;
import com.enterprise.mm.payload.IlliquidStockPolicyResponse;
import com.enterprise.mm.payload.IlliquidStockPolicyUpdateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.IlliquidStockPolicyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/illiquid-stock/policies")
@RequiredArgsConstructor
public class IlliquidStockPolicyController {
    private final IlliquidStockPolicyService service;

    @GetMapping
    public ApiResponse<PageResponse<IlliquidStockPolicyResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Boolean active,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(search, status, active, includeDeleted, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<IlliquidStockPolicyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IlliquidStockPolicyResponse> create(@Valid @RequestBody IlliquidStockPolicyCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<IlliquidStockPolicyResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody IlliquidStockPolicyUpdateRequest request
    ) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<IlliquidStockPolicyResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    public ApiResponse<IlliquidStockPolicyResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok(service.deactivate(id));
    }

    @PostMapping("/resolve-preview")
    public ApiResponse<IlliquidStockPolicyResolvePreviewResponse> resolvePreview(
        @Valid @RequestBody IlliquidStockPolicyResolvePreviewRequest request
    ) {
        return ApiResponse.ok(service.resolvePreview(request));
    }
}
