package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.WmsValuationReconciliationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/inventory-valuation/wms-reconciliation", "/api/v1/inventory-valuation/wms-reconciliation"})
@RequiredArgsConstructor
public class WmsValuationReconciliationController {
    private final WmsValuationReconciliationService service;

    @PostMapping("/run")
    public ApiResponse<List<InventoryValuationReconciliationIssueResponse>> run(@RequestParam(required = false) UUID warehouseId) {
        return ApiResponse.ok(service.run(warehouseId));
    }

    @GetMapping("/issues")
    public ApiResponse<PageResponse<InventoryValuationReconciliationIssueResponse>> issues(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String severity,
        @RequestParam(required = false) String issueType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(pageIssues(service.run(warehouseId), severity, issueType, page, size));
    }

    @GetMapping("/issues/{id}")
    public ApiResponse<InventoryValuationReconciliationIssueResponse> issue(@PathVariable UUID id, @RequestParam(required = false) UUID warehouseId) {
        return service.run(warehouseId).stream()
            .filter(issue -> id.equals(issue.id()))
            .findFirst()
            .map(ApiResponse::ok)
            .orElseThrow(() -> new com.enterprise.mm.error.NotFoundException("WMS reconciliation issue not found: " + id));
    }

    private PageResponse<InventoryValuationReconciliationIssueResponse> pageIssues(
        List<InventoryValuationReconciliationIssueResponse> issues,
        String severity,
        String issueType,
        int page,
        int size
    ) {
        List<InventoryValuationReconciliationIssueResponse> filtered = issues.stream()
            .filter(issue -> severity == null || severity.equalsIgnoreCase(issue.severity()))
            .filter(issue -> issueType == null || issueType.equalsIgnoreCase(issue.issueType()))
            .toList();
        int safeSize = Math.max(1, Math.min(size, com.enterprise.mm.common.PaginationUtils.MAX_PAGE_SIZE));
        int from = Math.min(Math.max(page, 0) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PageResponse<>(filtered.subList(from, to), Math.max(page, 0), safeSize, filtered.size(), (int) Math.ceil(filtered.size() / (double) safeSize));
    }
}
