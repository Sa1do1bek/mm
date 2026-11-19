package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.CallOffRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.CreateRequest;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.LedgerResponse;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.Readiness;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.Response;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.ReversalRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.service.ProcurementFrameworkService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement/frameworks")
@RequiredArgsConstructor
public class ProcurementFrameworkController {
    private final ProcurementFrameworkService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<Response> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @GetMapping("/by-procedure/{procedureId}")
    public ApiResponse<Response> getByProcedure(@PathVariable UUID procedureId) {
        return ApiResponse.ok(service.getByProcedure(procedureId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Response> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<Response> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Response> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Response> suspend(@PathVariable UUID id) {
        return ApiResponse.ok(service.suspend(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Response> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Response> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @GetMapping("/{id}/readiness")
    public ApiResponse<Readiness> readiness(@PathVariable UUID id) {
        return ApiResponse.ok(service.readiness(id));
    }

    @GetMapping("/{id}/remaining-capacity")
    public ApiResponse<BigDecimal> remainingCapacity(@PathVariable UUID id) {
        return ApiResponse.ok(service.remainingCapacity(id));
    }

    @GetMapping("/{id}/ledger")
    public ApiResponse<List<LedgerResponse>> ledger(@PathVariable UUID id) {
        return ApiResponse.ok(service.ledger(id));
    }

    @PostMapping("/{id}/call-offs")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<PurchaseOrderResponse> callOff(
        @PathVariable UUID id,
        @Valid @RequestBody CallOffRequest request
    ) {
        return ApiResponse.ok(service.callOff(id, request));
    }

    @PostMapping("/{id}/call-offs/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Response> reverseCallOff(
        @PathVariable UUID id,
        @RequestBody ReversalRequest request
    ) {
        return ApiResponse.ok(service.reverseCallOff(id, request));
    }
}
