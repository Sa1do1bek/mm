package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.ProcurementFlowResponse;
import com.enterprise.mm.service.ProcurementWorkspaceService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement-workspaces")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','ACCOUNTANT','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
@RequiredArgsConstructor
public class ProcurementWorkspaceController {
    private final ProcurementWorkspaceService service;

    @GetMapping("/purchase-orders/{purchaseOrderId}/flow")
    public ApiResponse<ProcurementFlowResponse> purchaseOrderFlow(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(service.byPurchaseOrder(purchaseOrderId));
    }

    @GetMapping("/demand-plans/{demandPlanId}/flow")
    public ApiResponse<List<ProcurementFlowResponse>> demandPlanFlow(@PathVariable UUID demandPlanId) {
        return ApiResponse.ok(service.byDemandPlan(demandPlanId));
    }
}
