package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ManualReceiptMaterialDtos;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.service.ManualReceiptMaterialService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/manual/materials")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER')")
public class ManualReceiptMaterialController {
    private final ManualReceiptMaterialService service;

    @GetMapping("/create-context")
    public ApiResponse<ManualReceiptMaterialDtos.CreateContext> context(
        @RequestParam UUID warehouseId, @RequestParam(required = false) UUID materialGroupId) {
        return ApiResponse.ok(service.context(warehouseId, materialGroupId));
    }

    @GetMapping("/code-availability")
    public ApiResponse<ManualReceiptMaterialDtos.CodeAvailability> codeAvailability(
        @RequestParam UUID warehouseId, @RequestParam String code) {
        return ApiResponse.ok(service.codeAvailability(warehouseId, code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialResponse> create(@Valid @RequestBody ManualReceiptMaterialDtos.CreateRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.created(service.create(request, key));
    }
}
