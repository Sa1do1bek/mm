package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.payload.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse-reports/overview")
@RequiredArgsConstructor
public class WarehouseOverviewController {
    private final WarehouseOverviewService overview;
    private final WarehouseReportApprovalService approvals;
    private final WarehouseOverviewExport exporter;

    public record Period(LocalDate from, LocalDate to) {}

    @GetMapping
    public ApiResponse<WarehouseOverview> list(
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) UUID warehouseId) {
        return ApiResponse.ok(overview.overview(from,to,warehouseId));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) UUID warehouseId,
        @RequestParam(defaultValue="ru") String language) {
        byte[] bytes=exporter.xlsx(overview.overview(from,to,warehouseId),language);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"warehouse-overview-"+from+"-"+to+".xlsx\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .body(bytes);
    }

    @GetMapping("/{warehouseId}")
    public ApiResponse<WarehouseReport> detail(@PathVariable UUID warehouseId,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(overview.liveDetail(from,to,warehouseId));
    }

    @GetMapping("/approvals/{approvalId}")
    public ApiResponse<WarehouseReportApprovalService.ApprovedReport> approved(@PathVariable UUID approvalId) {
        return ApiResponse.ok(approvals.get(approvalId));
    }

    @GetMapping("/{warehouseId}/approvals")
    public ApiResponse<List<WarehouseOverview.Approval>> history(@PathVariable UUID warehouseId,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(approvals.history(warehouseId,from,to));
    }

    @PostMapping("/{warehouseId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<WarehouseOverview.Approval> approve(@PathVariable UUID warehouseId,@RequestBody Period period) {
        return ApiResponse.ok(approvals.approve(warehouseId,period.from(),period.to()).approval());
    }

    @PostMapping("/approve-all")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<WarehouseReportApprovalService.BatchResult> approveAll(@RequestBody Period period) {
        return ApiResponse.ok(approvals.approveAll(period.from(),period.to()));
    }
}
