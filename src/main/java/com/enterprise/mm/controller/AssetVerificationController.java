package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AssetVerificationCompleteRequest;
import com.enterprise.mm.payload.AssetVerificationCreateRequest;
import com.enterprise.mm.payload.AssetVerificationDiscrepancyRequest;
import com.enterprise.mm.payload.AssetVerificationDiscrepancyReviewRequest;
import com.enterprise.mm.payload.AssetVerificationResponse;
import com.enterprise.mm.payload.AssetVerificationScanRequest;
import com.enterprise.mm.payload.AssetVerificationSearchRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.service.AssetVerificationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-verifications")
@RequiredArgsConstructor
public class AssetVerificationController {
    private final AssetVerificationService service;

    @GetMapping
    public ApiResponse<PageResponse<AssetVerificationResponse>> search(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String verificationType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(
            new AssetVerificationSearchRequest(warehouseId, departmentId, status, verificationType), pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetVerificationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssetVerificationResponse> create(
        @Valid @RequestBody AssetVerificationCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @PostMapping("/{id}/scan")
    public ApiResponse<AssetVerificationResponse> scan(
        @PathVariable UUID id,
        @Valid @RequestBody AssetVerificationScanRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.scan(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/record-discrepancy")
    public ApiResponse<AssetVerificationResponse> recordDiscrepancy(
        @PathVariable UUID id,
        @Valid @RequestBody AssetVerificationDiscrepancyRequest request
    ) {
        return ApiResponse.ok(service.recordDiscrepancy(id, request));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<AssetVerificationResponse> complete(
        @PathVariable UUID id,
        @Valid @RequestBody AssetVerificationCompleteRequest request
    ) {
        return ApiResponse.ok(service.complete(id, request));
    }

    @PostMapping("/{id}/review-discrepancies")
    public ApiResponse<AssetVerificationResponse> reviewDiscrepancies(
        @PathVariable UUID id,
        @Valid @RequestBody AssetVerificationDiscrepancyReviewRequest request
    ) {
        return ApiResponse.ok(service.reviewDiscrepancies(id, request));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<AssetVerificationResponse> close(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request
    ) {
        return ApiResponse.ok(service.close(id, request));
    }
}
