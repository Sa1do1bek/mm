package com.enterprise.mm.controller.masterdata;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BudgetHistoricalReconciliationDtos.Report;
import com.enterprise.mm.service.BudgetHistoricalReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budget-items/reconciliation")
@RequiredArgsConstructor
public class BudgetHistoricalReconciliationController {
    private final BudgetHistoricalReconciliationService service;

    @PostMapping("/historical")
    public ApiResponse<Report> reconcileHistorical() {
        return ApiResponse.ok(service.reconcile());
    }
}
