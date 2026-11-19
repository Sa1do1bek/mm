package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QualityClaimResponse;
import com.enterprise.mm.payload.QualityClaimUpdateRequest;
import com.enterprise.mm.payload.QualityClaimCreateRequest;
import com.enterprise.mm.payload.QualityControlDtos.QualityActionRequest;
import com.enterprise.mm.payload.QualityClaimSearchRequest;
import com.enterprise.mm.service.QualityClaimService;
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
@RequestMapping("/api/quality/claims")
@RequiredArgsConstructor
public class QualityClaimController {
    private final QualityClaimService service;

    @GetMapping
    public ApiResponse<PageResponse<QualityClaimResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
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
        QualityClaimSearchRequest request = new QualityClaimSearchRequest(search, status, supplierId, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QualityClaimResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QualityClaimResponse> create(@Valid @RequestBody QualityClaimCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<QualityClaimResponse> update(@PathVariable UUID id, @Valid @RequestBody QualityClaimUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<QualityClaimResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/send")
    public ApiResponse<QualityClaimResponse> send(@PathVariable UUID id) {
        return ApiResponse.ok(service.send(id));
    }

    @PostMapping("/{id}/acknowledge")
    public ApiResponse<QualityClaimResponse> acknowledge(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.acknowledge(id, request));
    }

    @PostMapping("/{id}/require-capa")
    public ApiResponse<QualityClaimResponse> requireCapa(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.requireCapa(id, request));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<QualityClaimResponse> accept(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.accept(id, request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<QualityClaimResponse> reject(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<QualityClaimResponse> close(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.close(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<QualityClaimResponse> cancel(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.cancel(id, request));
    }

}

