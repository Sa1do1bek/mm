package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AssetHandoverCreateRequest;
import com.enterprise.mm.payload.AssetHandoverResponse;
import com.enterprise.mm.payload.AssetHandoverSearchRequest;
import com.enterprise.mm.payload.AssetHandoverUpdateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.service.AssetHandoverService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-handovers")
@RequiredArgsConstructor
public class AssetHandoverController {
    private final AssetHandoverService service;

    @GetMapping
    public ApiResponse<PageResponse<AssetHandoverResponse>> search(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String handoverType,
        @RequestParam(required = false) UUID fixedAssetId,
        @RequestParam(required = false) UUID fromWarehouseId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(new AssetHandoverSearchRequest(status, handoverType, fixedAssetId, fromWarehouseId), pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetHandoverResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssetHandoverResponse> create(
        @Valid @RequestBody AssetHandoverCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @PutMapping("/{id}")
    public ApiResponse<AssetHandoverResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody AssetHandoverUpdateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.update(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<AssetHandoverResponse> submit(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.submit(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/dispatch")
    public ApiResponse<AssetHandoverResponse> dispatch(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.dispatch(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/acknowledge")
    public ApiResponse<AssetHandoverResponse> acknowledge(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.acknowledge(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/report-exception")
    public ApiResponse<AssetHandoverResponse> reportException(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.reportException(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/acknowledge-return")
    public ApiResponse<AssetHandoverResponse> acknowledgeReturn(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.acknowledgeReturn(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/return-resolution")
    public ApiResponse<AssetHandoverResponse> returnResolution(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.returnResolution(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AssetHandoverResponse> cancel(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.cancel(id, request, idempotencyKey));
    }
}
