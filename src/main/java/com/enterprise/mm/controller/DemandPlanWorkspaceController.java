package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.DemandPlanProcessStage;
import com.enterprise.mm.enums.DemandPlanWorkspaceView;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanWorkspaceDetail;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanWorkspaceRow;
import com.enterprise.mm.payload.DemandPlanWorkspaceDtos.DemandPlanWorkspaceSummary;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.DemandPlanWorkspaceService;
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
@RequestMapping("/api/demand-plans")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
public class DemandPlanWorkspaceController {
    private final DemandPlanWorkspaceService service;

    @GetMapping("/workspace")
    public ApiResponse<PageResponse<DemandPlanWorkspaceRow>> worklist(
        @RequestParam(defaultValue = "ALL") DemandPlanWorkspaceView view,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Integer planYear,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) DemandPlanProcessStage processStage,
        @RequestParam(required = false) UUID planningCycleId,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) String periodType,
        @RequestParam(required = false) Integer quarter,
        @RequestParam(required = false) String ownerUsername,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("updatedAt").descending());
        return ApiResponse.ok(service.worklist(
            filter(view, search, planYear, departmentId, status, processStage, planningCycleId, requestType, periodType, quarter, ownerUsername, materialId),
            pageable
        ));
    }

    @GetMapping("/workspace/summary")
    public ApiResponse<DemandPlanWorkspaceSummary> summary(
        @RequestParam(defaultValue = "ALL") DemandPlanWorkspaceView view,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Integer planYear,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) DemandPlanProcessStage processStage,
        @RequestParam(required = false) UUID planningCycleId,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) String periodType,
        @RequestParam(required = false) Integer quarter,
        @RequestParam(required = false) String ownerUsername,
        @RequestParam(required = false) UUID materialId
    ) {
        return ApiResponse.ok(service.summary(
            filter(view, search, planYear, departmentId, status, processStage, planningCycleId, requestType, periodType, quarter, ownerUsername, materialId)
        ));
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<DemandPlanWorkspaceDetail> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }

    private DemandPlanWorkspaceService.Filter filter(
        DemandPlanWorkspaceView view,
        String search,
        Integer planYear,
        UUID departmentId,
        List<String> status,
        DemandPlanProcessStage processStage,
        UUID planningCycleId,
        String requestType,
        String periodType,
        Integer quarter,
        String ownerUsername,
        UUID materialId
    ) {
        return new DemandPlanWorkspaceService.Filter(
            view,
            search,
            planYear,
            departmentId,
            status == null ? List.of() : status,
            processStage,
            planningCycleId,
            requestType,
            periodType,
            quarter,
            ownerUsername,
            materialId
        );
    }
}
