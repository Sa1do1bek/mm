package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementLineAllocationRequest;
import com.enterprise.mm.payload.ProcurementLineAllocationResponse;
import com.enterprise.mm.service.ProcurementLineAllocationService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement/lineage")
@RequiredArgsConstructor
public class ProcurementLineageController {
    private final ProcurementLineAllocationService service;

    @GetMapping("/{entityType}/{entityId}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER')")
    public ApiResponse<List<ProcurementLineAllocationResponse>> lineage(
        @PathVariable String entityType,
        @PathVariable UUID entityId
    ) {
        return ApiResponse.ok(service.findByDocument(entityType, entityId));
    }

    @PostMapping("/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementLineAllocationResponse> allocate(
        @Valid @RequestBody ProcurementLineAllocationRequest request
    ) {
        return ApiResponse.created(service.allocate(request));
    }

    @PostMapping("/allocations/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementLineAllocationResponse> cancel(
        @PathVariable UUID id,
        @RequestParam BigDecimal quantity,
        @RequestParam(required = false) BigDecimal amount
    ) {
        return ApiResponse.ok(service.cancel(id, quantity, amount));
    }
}
