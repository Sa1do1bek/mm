package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AssetAcceptanceCreateRequest;
import com.enterprise.mm.payload.AssetAcceptancePostRequest;
import com.enterprise.mm.payload.AssetAcceptanceQualityDecisionRequest;
import com.enterprise.mm.payload.AssetAcceptanceResponse;
import com.enterprise.mm.payload.AssetAcceptanceRestrictedReversalRequest;
import com.enterprise.mm.payload.AssetAcceptanceSearchRequest;
import com.enterprise.mm.payload.AssetAcceptanceUpdateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.service.AssetAcceptanceService;
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
@RequestMapping("/api/asset-acceptances")
@RequiredArgsConstructor
public class AssetAcceptanceController {
    private final AssetAcceptanceService service;

    @GetMapping
    public ApiResponse<PageResponse<AssetAcceptanceResponse>> search(
        @RequestParam(required = false) UUID purchaseOrderId,
        @RequestParam(required = false) String approvalStatus,
        @RequestParam(required = false) String postingStatus,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(
            new AssetAcceptanceSearchRequest(purchaseOrderId, approvalStatus, postingStatus), pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetAcceptanceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssetAcceptanceResponse> create(
        @Valid @RequestBody AssetAcceptanceCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @PutMapping("/{id}")
    public ApiResponse<AssetAcceptanceResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody AssetAcceptanceUpdateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.update(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/quality-decisions")
    public ApiResponse<AssetAcceptanceResponse> recordQualityDecision(
        @PathVariable UUID id,
        @Valid @RequestBody AssetAcceptanceQualityDecisionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.recordQualityDecision(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<AssetAcceptanceResponse> submit(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.submit(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<AssetAcceptanceResponse> post(
        @PathVariable UUID id,
        @Valid @RequestBody AssetAcceptancePostRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.post(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/restricted-reversal")
    public ApiResponse<AssetAcceptanceResponse> restrictedReversal(
        @PathVariable UUID id,
        @Valid @RequestBody AssetAcceptanceRestrictedReversalRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.restrictedReversal(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AssetAcceptanceResponse> cancel(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.cancel(id, request, idempotencyKey));
    }
}
