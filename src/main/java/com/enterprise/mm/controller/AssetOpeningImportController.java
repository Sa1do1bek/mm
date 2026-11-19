package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AssetOpeningImportCreateRequest;
import com.enterprise.mm.payload.AssetOpeningImportPostRequest;
import com.enterprise.mm.payload.AssetOpeningImportResponse;
import com.enterprise.mm.payload.AssetOpeningImportValidateRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.service.AssetOpeningImportService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-opening-imports")
@RequiredArgsConstructor
public class AssetOpeningImportController {
    private final AssetOpeningImportService service;

    @PostMapping("/validate")
    public ApiResponse<AssetOpeningImportResponse> validate(@Valid @RequestBody AssetOpeningImportValidateRequest request) {
        return ApiResponse.ok(service.validate(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssetOpeningImportResponse> create(
        @Valid @RequestBody AssetOpeningImportCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetOpeningImportResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<AssetOpeningImportResponse> submit(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request
    ) {
        return ApiResponse.ok(service.submit(id, request));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<AssetOpeningImportResponse> post(
        @PathVariable UUID id,
        @Valid @RequestBody AssetOpeningImportPostRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.post(id, request, idempotencyKey));
    }
}
