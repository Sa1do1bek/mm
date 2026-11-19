package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncRequest;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncResult;
import com.enterprise.mm.service.ProductCodeSyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/product-codes")
@RequiredArgsConstructor
public class ProductCodeSyncController {
    private final ProductCodeSyncService service;

    @PostMapping("/ikpu/sync")
    public ApiResponse<ProductCodeSyncResult> syncIkpu(@RequestBody(required = false) ProductCodeSyncRequest request) {
        return ApiResponse.ok(service.syncIkpu(request));
    }

    @PostMapping("/mxik/sync")
    public ApiResponse<ProductCodeSyncResult> syncMxik(@RequestBody(required = false) ProductCodeSyncRequest request) {
        return ApiResponse.ok(service.syncMxik(request));
    }

    @PostMapping("/tnved/sync")
    public ApiResponse<ProductCodeSyncResult> syncTnved(@RequestBody(required = false) ProductCodeSyncRequest request) {
        return ApiResponse.ok(service.syncTnved(request));
    }
}

