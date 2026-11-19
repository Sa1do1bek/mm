package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementCaseDtos.ProcurementCaseResponse;
import com.enterprise.mm.service.ProcurementCaseService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement-cases")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
public class ProcurementCaseController {
    private final ProcurementCaseService service;

    @GetMapping("/{rootType}/{id}")
    public ApiResponse<ProcurementCaseResponse> get(
        @PathVariable String rootType,
        @PathVariable UUID id
    ) {
        return ApiResponse.ok(service.get(rootType, id));
    }
}
