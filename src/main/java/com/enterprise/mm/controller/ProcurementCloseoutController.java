package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementCloseoutDtos;
import com.enterprise.mm.service.ProcurementCloseoutReadinessService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProcurementCloseoutController {
    private final ProcurementCloseoutReadinessService service;

    @GetMapping("/api/procurement/procedures/{procedureId}/closeout-readiness")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER')")
    public ApiResponse<ProcurementCloseoutDtos.Readiness> readiness(@PathVariable UUID procedureId) {
        return ApiResponse.ok(service.readiness(procedureId));
    }

    @GetMapping("/api/procurement/procedures/{procedureId}/closeout-summary")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER')")
    public ApiResponse<ProcurementCloseoutDtos.Summary> summary(@PathVariable UUID procedureId) {
        return ApiResponse.ok(service.summary(procedureId));
    }

    @PostMapping("/api/procurement/procedures/{procedureId}/closeouts")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementCloseoutDtos.Response> create(
        @PathVariable UUID procedureId,
        @Valid @RequestBody(required = false) ProcurementCloseoutDtos.CreateRequest request
    ) {
        return ApiResponse.created(service.create(procedureId, request));
    }

    @GetMapping("/api/procurement/closeouts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER')")
    public ApiResponse<ProcurementCloseoutDtos.Response> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/api/procurement/closeouts/{id}/recheck")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<ProcurementCloseoutDtos.Response> recheck(@PathVariable UUID id) {
        return ApiResponse.ok(service.recheck(id));
    }

    @PostMapping("/api/procurement/closeouts/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementCloseoutDtos.Response> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/api/procurement/closeouts/{id}/finalize")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementCloseoutDtos.Response> finalizeCloseout(@PathVariable UUID id) {
        return ApiResponse.ok(service.finalize(id));
    }

    @PostMapping("/api/procurement/closeouts/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementCloseoutDtos.Response> cancel(
        @PathVariable UUID id,
        @RequestBody ProcurementCloseoutDtos.CommandRequest request
    ) {
        return ApiResponse.ok(service.cancel(id, request == null ? null : request.reason()));
    }

    @PostMapping("/api/procurement/closeouts/{id}/request-reopen")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<ProcurementCloseoutDtos.Response> requestReopen(
        @PathVariable UUID id,
        @RequestBody ProcurementCloseoutDtos.CommandRequest request
    ) {
        return ApiResponse.ok(service.requestReopen(id, request));
    }

    @PostMapping("/api/procurement/closeouts/{id}/apply-reopen")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementCloseoutDtos.Response> applyReopen(@PathVariable UUID id) {
        return ApiResponse.ok(service.applyReopen(id));
    }

    @PostMapping("/api/purchase-orders/{id}/cancel-remainder")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<Map<String, Object>> cancelPoRemainder(
        @PathVariable UUID id,
        @Valid @RequestBody ProcurementCloseoutDtos.CancelPoRemainderRequest request
    ) {
        return ApiResponse.ok(service.cancelPoRemainder(id, request));
    }

    @PostMapping("/api/contracts/{id}/resolve-remaining-obligation")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<Map<String, Object>> resolveRemainingObligation(
        @PathVariable UUID id,
        @Valid @RequestBody ProcurementCloseoutDtos.ResolveRemainingObligationRequest request
    ) {
        return ApiResponse.ok(service.resolveRemainingObligation(id, request));
    }

    @PostMapping("/api/procurement/closeouts/reconciliation/historical")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR')")
    public ApiResponse<ProcurementCloseoutDtos.HistoricalReport> historical(
        @RequestBody(required = false) Map<String, Object> body,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        boolean dryRun = body == null || !Boolean.FALSE.equals(body.get("dryRun"));
        return ApiResponse.ok(service.historical(dryRun, page, size));
    }
}
