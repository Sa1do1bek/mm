package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryCommissionMemberResponse;
import com.enterprise.mm.payload.InventoryCommissionSettingsRequest;
import com.enterprise.mm.service.InventoryCommissionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/commissions")
@RequiredArgsConstructor
public class InventoryCommissionController {
    private final InventoryCommissionService service;

    @GetMapping("/{commissionId}")
    public ApiResponse<List<InventoryCommissionMemberResponse>> get(@PathVariable UUID commissionId) {
        return ApiResponse.ok(service.get(commissionId));
    }

    @PutMapping("/{commissionId}")
    public ApiResponse<List<InventoryCommissionMemberResponse>> update(
        @PathVariable UUID commissionId,
        @Valid @RequestBody InventoryCommissionSettingsRequest request
    ) {
        return ApiResponse.ok(service.update(commissionId, request));
    }
}
