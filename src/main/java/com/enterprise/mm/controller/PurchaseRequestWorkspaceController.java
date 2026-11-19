package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.PurchaseRequestProcessStage;
import com.enterprise.mm.enums.PurchaseRequestWorkspaceView;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestWorkspaceDetail;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestWorkspaceRow;
import com.enterprise.mm.payload.PurchaseRequestWorkspaceDtos.PurchaseRequestWorkspaceSummary;
import com.enterprise.mm.service.PurchaseRequestWorkspaceService;
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
@RequestMapping("/api/purchase-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
public class PurchaseRequestWorkspaceController {
    private final PurchaseRequestWorkspaceService service;

    @GetMapping("/workspace")
    public ApiResponse<PageResponse<PurchaseRequestWorkspaceRow>> worklist(
        @RequestParam(defaultValue = "ALL") PurchaseRequestWorkspaceView view,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID demandPlanId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) PurchaseRequestProcessStage processStage,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) String requestedBy,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("updatedAt").descending());
        return ApiResponse.ok(service.worklist(
            filter(view, search, departmentId, demandPlanId, status, processStage, requestType, requestedBy, materialId),
            pageable
        ));
    }

    @GetMapping("/workspace/summary")
    public ApiResponse<PurchaseRequestWorkspaceSummary> summary(
        @RequestParam(defaultValue = "ALL") PurchaseRequestWorkspaceView view,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID demandPlanId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) PurchaseRequestProcessStage processStage,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) String requestedBy,
        @RequestParam(required = false) UUID materialId
    ) {
        return ApiResponse.ok(service.summary(
            filter(view, search, departmentId, demandPlanId, status, processStage, requestType, requestedBy, materialId)
        ));
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<PurchaseRequestWorkspaceDetail> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }

    private PurchaseRequestWorkspaceService.Filter filter(
        PurchaseRequestWorkspaceView view,
        String search,
        UUID departmentId,
        UUID demandPlanId,
        List<String> status,
        PurchaseRequestProcessStage processStage,
        String requestType,
        String requestedBy,
        UUID materialId
    ) {
        return new PurchaseRequestWorkspaceService.Filter(
            view,
            search,
            departmentId,
            demandPlanId,
            status == null ? List.of() : status,
            processStage,
            requestType,
            requestedBy,
            materialId
        );
    }
}
