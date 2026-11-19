package com.enterprise.mm.warehouse.activation;

import com.enterprise.mm.payload.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/warehouse/source-activations")
public class ActivationPreviewController {
    private final ActivationPreviewService previewService;
    private final ActivationApplyService applyService;

    public ActivationPreviewController(
        ActivationPreviewService previewService,
        ActivationApplyService applyService
    ) {
        this.previewService = previewService;
        this.applyService = applyService;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER', 'FINANCE_CONTROLLER', 'ACCOUNTANT', 'WAREHOUSE_MANAGER', 'WAREHOUSE_HEAD', 'AUDITOR', 'VIEWER')")
    public ApiResponse<ActivationPreviewResult> preview(
        @RequestParam(required = false) String accountCode,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID importId,
        @RequestParam(required = false) java.time.LocalDate cutoverDate,
        @RequestParam(required = false) SourcePositionClassification classification,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        var query = new ActivationPreviewQuery(accountCode, warehouseId, classification, page, size, importId, cutoverDate);
        return ApiResponse.ok(previewService.preview(query));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER', 'FINANCE_CONTROLLER', 'ACCOUNTANT')")
    public ApiResponse<ActivationApplyService.ActivationApplyResult> apply(
        @RequestBody ActivationApplyService.ActivationApplyRequest request
    ) {
        return ApiResponse.ok(applyService.apply(request));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER', 'FINANCE_CONTROLLER', 'ACCOUNTANT')")
    public ApiResponse<ActivationApplyService.ActivationPlanResponse> createPlan(
        @RequestBody ActivationApplyService.CreatePlanRequest request
    ) {
        return ApiResponse.ok(applyService.createPlan(request));
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER', 'FINANCE_CONTROLLER', 'ACCOUNTANT', 'WAREHOUSE_MANAGER', 'WAREHOUSE_HEAD', 'AUDITOR', 'VIEWER')")
    public ApiResponse<java.util.List<ActivationApplyService.ActivationPlanResponse>> listPlans(
        @RequestParam(required = false) String accountCode,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(applyService.listPlans(accountCode, warehouseId, status));
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER', 'FINANCE_CONTROLLER', 'ACCOUNTANT', 'WAREHOUSE_MANAGER', 'WAREHOUSE_HEAD', 'AUDITOR', 'VIEWER')")
    public ApiResponse<ActivationApplyService.ActivationPlanResponse> getPlan(
        @PathVariable UUID id
    ) {
        return ApiResponse.ok(applyService.getPlan(id));
    }

    @PostMapping("/plans/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE_CONTROLLER', 'ACCOUNTANT')")
    public ApiResponse<ActivationApplyService.ActivationPlanResponse> approvePlan(
        @PathVariable UUID id,
        @RequestBody(required = false) ActivationApplyService.ApprovePlanRequest request
    ) {
        String notes = request != null ? request.approvalNotes() : null;
        return ApiResponse.ok(applyService.approvePlan(id, notes));
    }
}
