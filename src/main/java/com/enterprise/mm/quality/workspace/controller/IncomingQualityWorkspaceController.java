package com.enterprise.mm.quality.workspace.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionDetail;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionListResponse;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueSummary;
import com.enterprise.mm.quality.workspace.service.IncomingQualityWorkspaceService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/incoming-quality")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','SUPPLIER_MANAGER','FINANCE_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
@RequiredArgsConstructor
public class IncomingQualityWorkspaceController {
    private final IncomingQualityWorkspaceService service;

    @GetMapping("/summary")
    public ApiResponse<List<QueueSummary>> summary(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID supplierId
    ) {
        return ApiResponse.ok(service.summary(
            new QueueFilter(null, search, warehouseId, supplierId)));
    }

    @GetMapping("/inspections")
    public ApiResponse<InspectionListResponse> inspections(
        @RequestParam(required = false) QueueKey queue,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(service.list(
            new QueueFilter(queue, search, warehouseId, supplierId),
            PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending())
        ));
    }

    @GetMapping("/inspections/{id}")
    public ApiResponse<InspectionDetail> inspection(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }
}
