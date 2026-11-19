package com.enterprise.mm.warehouse.execution.controller;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.warehouse.reports.WarehouseReport;
import com.enterprise.mm.warehouse.reports.WarehouseReportExport;
import com.enterprise.mm.warehouse.reports.WarehouseReportService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Accounting balances when imported, otherwise operational warehouse balances. */
@RestController
@RequestMapping("/api/workspaces/warehouse-execution/accounting-balances")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
@RequiredArgsConstructor
public class WarehouseExecutionAccountingBalanceController {
    private final WarehouseReportService reports;
    private final WarehouseReportExport exporter;
    private final RoleDataScopeService access;

    @GetMapping
    public ApiResponse<WarehouseReport> balances(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String accountCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID warehouse = warehouse(warehouseId);
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        WarehouseReport report = report(warehouse, accountCode, search, materialGroupId, date, page, size, false);
        return ApiResponse.ok(report);
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String accountCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(defaultValue = "ru") String language) {
        UUID warehouse = warehouse(warehouseId);
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        WarehouseReport report = report(warehouse, accountCode, search, materialGroupId, date, 0, 50, true);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=warehouse-accounting-balances-" + date + ".xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(exporter.xlsx(report, language));
    }

    @GetMapping("/accounts")
    public ApiResponse<PageResponse<Map<String, Object>>> accounts(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID warehouse = warehouse(warehouseId);
        if (page < 0 || size < 1 || size > 200) throw new BadRequestException("Use page >= 0 and size between 1 and 200");
        if (reports.options().accountSnapshotDate() == null) {
            return ApiResponse.ok(new PageResponse<>(java.util.List.of(), page, size, 0, 0));
        }
        return ApiResponse.ok(reports.references("ACCOUNT", new WarehouseReport.Filter(
                WarehouseReport.Type.STOCK_BALANCES, LocalDate.now(), LocalDate.now(),
                warehouse, null, null, search, page, size)));
    }

    private UUID warehouse(UUID requested) {
        UUID resolved = access.availabilityWarehouseId(requested);
        if (resolved == null) {
            throw new BadRequestException("Warehouse is required for accounting balances");
        }
        return resolved;
    }

    private WarehouseReport report(UUID warehouse, String accountCode, String search,
            UUID materialGroupId, LocalDate date, int page, int size, boolean all) {
        return reports.generate(new WarehouseReport.Filter(
                WarehouseReport.Type.STOCK_BALANCES, date, date, warehouse, null,
                materialGroupId, search, page, size, null, null, accountCode, null), all);
    }

}
