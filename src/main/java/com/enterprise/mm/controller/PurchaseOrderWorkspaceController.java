package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.PurchaseOrderProcessStage;
import com.enterprise.mm.enums.PurchaseOrderWorkspaceView;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceDetail;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceRow;
import com.enterprise.mm.payload.PurchaseOrderWorkspaceDtos.PurchaseOrderWorkspaceSummary;
import com.enterprise.mm.service.PurchaseOrderWorkspaceService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
public class PurchaseOrderWorkspaceController {
    private final PurchaseOrderWorkspaceService service;

    @GetMapping("/workspace")
    public ApiResponse<PageResponse<PurchaseOrderWorkspaceRow>> worklist(
        @RequestParam(defaultValue = "ALL") PurchaseOrderWorkspaceView view,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID demandPlanId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID purchaseRequestId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) PurchaseOrderProcessStage processStage,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("updatedAt").descending());
        return ApiResponse.ok(service.worklist(
            filter(view, search, departmentId, demandPlanId, supplierId, purchaseRequestId, status, processStage, requestType, materialId),
            pageable
        ));
    }

    @GetMapping("/workspace/summary")
    public ApiResponse<PurchaseOrderWorkspaceSummary> summary(
        @RequestParam(defaultValue = "ALL") PurchaseOrderWorkspaceView view,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID demandPlanId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID purchaseRequestId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) PurchaseOrderProcessStage processStage,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) UUID materialId
    ) {
        return ApiResponse.ok(service.summary(
            filter(view, search, departmentId, demandPlanId, supplierId, purchaseRequestId, status, processStage, requestType, materialId)
        ));
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<PurchaseOrderWorkspaceDetail> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }

    private PurchaseOrderWorkspaceService.Filter filter(
        PurchaseOrderWorkspaceView view,
        String search,
        UUID departmentId,
        UUID demandPlanId,
        UUID supplierId,
        UUID purchaseRequestId,
        List<String> status,
        PurchaseOrderProcessStage processStage,
        String requestType,
        UUID materialId
    ) {
        return new PurchaseOrderWorkspaceService.Filter(
            view,
            search,
            departmentId,
            demandPlanId,
            supplierId,
            purchaseRequestId,
            status == null ? List.of() : status,
            processStage,
            requestType,
            materialId
        );
    }
}
