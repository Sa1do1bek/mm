package com.enterprise.mm.supplier.governance.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.GovernanceOverview;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.QueueDefinition;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.QueueResponse;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceWorkspaceService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/supplier-governance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','SUPPLIER_MANAGER','COMPLIANCE_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','AUDITOR','VIEWER')")
public class SupplierGovernanceWorkspaceController {
    private final SupplierGovernanceWorkspaceService service;

    @GetMapping("/overview")
    public ApiResponse<GovernanceOverview> overview(
        @RequestParam(required = false) Integer horizonDays
    ) {
        return ApiResponse.ok(service.overviewForCompany(horizonDays));
    }

    @GetMapping("/queues")
    public ApiResponse<List<QueueDefinition>> queues(
        @RequestParam(required = false) Integer horizonDays,
        @RequestParam(required = false) UUID purchaseOrderId
    ) {
        return ApiResponse.ok(service.queuesForCompany(horizonDays, purchaseOrderId));
    }

    @GetMapping("/cases")
    public ApiResponse<QueueResponse> cases(
        @RequestParam(required = false) SupplierGovernanceQueueKey queue,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Integer horizonDays,
        @RequestParam(required = false) UUID purchaseOrderId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(service.queueForCompany(
            new SupplierGovernanceFilter(queue, search, horizonDays, purchaseOrderId), pageable));
    }

    @GetMapping("/cases/{supplierId}")
    public ApiResponse<SupplierGovernanceContext> context(
        @PathVariable UUID supplierId,
        @RequestParam(required = false) UUID purchaseOrderId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate performanceFrom,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate performanceTo,
        @RequestParam(required = false) List<RequestType> requestType,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate
    ) {
        return ApiResponse.ok(service.context(
            supplierId, purchaseOrderId, performanceFrom, performanceTo, requestType, materialGroupId, asOfDate));
    }
}
