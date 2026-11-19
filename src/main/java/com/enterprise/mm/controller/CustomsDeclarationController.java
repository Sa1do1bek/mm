package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CustomsDeclarationResponse;
import com.enterprise.mm.payload.CustomsDeclarationUpdateRequest;
import com.enterprise.mm.payload.CustomsDeclarationCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.CustomsDeclarationSearchRequest;
import com.enterprise.mm.service.CustomsDeclarationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/customs-declarations")
@RequiredArgsConstructor
public class CustomsDeclarationController {
    private final CustomsDeclarationService service;

    @GetMapping
    public ApiResponse<PageResponse<CustomsDeclarationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
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
        CustomsDeclarationSearchRequest request = new CustomsDeclarationSearchRequest(search, status, supplierId, purchaseOrderId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomsDeclarationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CustomsDeclarationResponse> create(@Valid @RequestBody CustomsDeclarationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CustomsDeclarationResponse> update(@PathVariable UUID id, @Valid @RequestBody CustomsDeclarationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<CustomsDeclarationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<CustomsDeclarationResponse> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<CustomsDeclarationResponse> accept(@PathVariable UUID id) {
        return ApiResponse.ok(service.accept(id));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<CustomsDeclarationResponse> release(@PathVariable UUID id) {
        return ApiResponse.ok(service.release(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<CustomsDeclarationResponse> reject(@PathVariable UUID id) {
        return ApiResponse.ok(service.reject(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CustomsDeclarationResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

}

