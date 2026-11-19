package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryAccountingSettingsOptionsResponse;
import com.enterprise.mm.payload.InventoryAccountingSettingsResponse;
import com.enterprise.mm.payload.InventoryAccountingSettingsUpdateRequest;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/inventory-accounting")
@RequiredArgsConstructor
public class InventoryAccountingSettingsController {
    private final InventoryAccountingSettingsService service;

    @GetMapping
    public ApiResponse<InventoryAccountingSettingsResponse> get() {
        return ApiResponse.ok(service.getSettings());
    }

    @GetMapping("/options")
    public ApiResponse<InventoryAccountingSettingsOptionsResponse> options() {
        return ApiResponse.ok(service.getOptions());
    }

    @PutMapping
    public ApiResponse<InventoryAccountingSettingsResponse> save(@Valid @RequestBody InventoryAccountingSettingsUpdateRequest request) {
        return ApiResponse.ok(service.saveSettings(request));
    }
}
