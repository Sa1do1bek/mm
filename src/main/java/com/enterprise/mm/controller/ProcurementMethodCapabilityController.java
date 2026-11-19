package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementMethodCapabilityDtos;
import com.enterprise.mm.service.ProcurementMethodCapabilityService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement/method-capabilities")
@RequiredArgsConstructor
public class ProcurementMethodCapabilityController {
    private final ProcurementMethodCapabilityService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<List<ProcurementMethodCapabilityDtos.Response>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping({"/available", "/availability"})
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<List<ProcurementMethodCapabilityDtos.Evaluation>> availability(
        @RequestParam(required = false) BigDecimal amount,
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) String categoryId
    ) {
        return ApiResponse.ok(service.availability(amount, currency, requestType, categoryId));
    }

    @GetMapping("/{methodCode}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ProcurementMethodCapabilityDtos.Response> get(@PathVariable String methodCode) {
        return ApiResponse.ok(service.get(methodCode));
    }

    @GetMapping("/{methodCode}/evaluate")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ProcurementMethodCapabilityDtos.Evaluation> evaluate(
        @PathVariable String methodCode,
        @RequestParam(required = false) BigDecimal amount,
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) String categoryId
    ) {
        return ApiResponse.ok(service.evaluate(methodCode, amount, currency, requestType, categoryId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementMethodCapabilityDtos.Response> create(@Valid @RequestBody ProcurementMethodCapabilityDtos.UpsertRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementMethodCapabilityDtos.Response> update(@PathVariable UUID id, @Valid @RequestBody ProcurementMethodCapabilityDtos.UpsertRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }
}
