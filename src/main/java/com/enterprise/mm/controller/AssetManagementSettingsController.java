package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AssetSettingsResponse;
import com.enterprise.mm.payload.AssetSettingsUpdateRequest;
import com.enterprise.mm.service.AssetManagementSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-management/settings")
@RequiredArgsConstructor
public class AssetManagementSettingsController {
    private final AssetManagementSettingsService service;

    @GetMapping
    public ApiResponse<AssetSettingsResponse> get() {
        return ApiResponse.ok(service.get());
    }

    @PutMapping
    public ApiResponse<AssetSettingsResponse> update(@Valid @RequestBody AssetSettingsUpdateRequest request) {
        return ApiResponse.ok(service.update(request));
    }
}
