package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ReportDtos.AbcReportDto;
import com.enterprise.mm.payload.ReportDtos.AbcXyzMatrixReportDto;
import com.enterprise.mm.payload.ReportDtos.DashboardReportDto;
import com.enterprise.mm.payload.ReportDtos.InventoryValuationDashboardDto;
import com.enterprise.mm.payload.ReportDtos.InventoryValuationReportDto;
import com.enterprise.mm.payload.ReportDtos.MovementReportDto;
import com.enterprise.mm.payload.ReportDtos.NonMovingReportDto;
import com.enterprise.mm.payload.ReportDtos.OverdueDeliveryReportDto;
import com.enterprise.mm.payload.ReportDtos.PlanFactReportDto;
import com.enterprise.mm.payload.ReportDtos.ReportsSnapshotDto;
import com.enterprise.mm.payload.ReportDtos.SavingsCurrencyBucketDto;
import com.enterprise.mm.payload.ReportDtos.StockReportDto;
import com.enterprise.mm.payload.ReportDtos.SupplierRatingReportDto;
import com.enterprise.mm.payload.ReportDtos.TurnoverReportDto;
import com.enterprise.mm.payload.ReportDtos.XyzReportDto;
import com.enterprise.mm.payload.ReportFilter;
import com.enterprise.mm.service.ImportService;
import com.enterprise.mm.service.ReportService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reportService;
    private final ImportService importService;

    @GetMapping("/stock-balances.xlsx")
    public ResponseEntity<byte[]> stockBalancesExcel(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return file("stock-balances.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reportService.stockBalancesExcel(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/supplier-rating.xlsx")
    public ResponseEntity<byte[]> supplierRatingExcel(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return file("supplier-rating.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reportService.supplierRatingExcel(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/dashboard.pdf")
    public ResponseEntity<byte[]> dashboardPdf(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return file("mm-dashboard.pdf", MediaType.APPLICATION_PDF_VALUE, reportService.dashboardPdf(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/analytics.xlsx")
    public ResponseEntity<byte[]> analyticsExcel(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return file("mm-analytics.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reportService.analyticsExcel(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/reference-mappings.xlsx")
    public ResponseEntity<byte[]> referenceMappingsExcel() {
        return file("reference-mappings.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reportService.referenceMappingsExcel());
    }

    @GetMapping("/snapshot")
    public ApiResponse<ReportsSnapshotDto> snapshot(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return ApiResponse.ok(reportService.snapshot(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardReportDto> dashboard() {
        return ApiResponse.ok(reportService.dashboard());
    }

    @GetMapping("/plan-fact")
    public ApiResponse<PlanFactReportDto> planFact(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return ApiResponse.ok(reportService.planFact(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/savings")
    public ApiResponse<java.math.BigDecimal> savings(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return ApiResponse.ok(reportService.savings(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/savings/by-currency")
    public ApiResponse<List<SavingsCurrencyBucketDto>> savingsByCurrency(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return ApiResponse.ok(reportService.savingsByCurrency(new ReportFilter(from, to, currency)));
    }

    @GetMapping("/supplier-rating")
    public ApiResponse<PageResponse<SupplierRatingReportDto>> supplierRating() {
        return ApiResponse.ok(PageResponse.from(reportService.supplierRating()));
    }

    @GetMapping("/stock")
    public ApiResponse<PageResponse<StockReportDto>> stock() {
        return ApiResponse.ok(PageResponse.from(reportService.stock()));
    }

    @GetMapping("/movements")
    public ApiResponse<PageResponse<MovementReportDto>> movements() {
        return ApiResponse.ok(PageResponse.from(reportService.movements()));
    }

    @GetMapping("/turnover")
    public ApiResponse<PageResponse<TurnoverReportDto>> turnover() {
        return ApiResponse.ok(PageResponse.from(reportService.turnover()));
    }

    @GetMapping("/abc")
    public ApiResponse<PageResponse<AbcReportDto>> abc() {
        return ApiResponse.ok(PageResponse.from(reportService.abc()));
    }

    @GetMapping("/xyz")
    public ApiResponse<PageResponse<XyzReportDto>> xyz() {
        return ApiResponse.ok(PageResponse.from(reportService.xyz()));
    }

    @GetMapping("/abc-xyz")
    public ApiResponse<PageResponse<AbcXyzMatrixReportDto>> abcXyzMatrix() {
        return ApiResponse.ok(PageResponse.from(reportService.abcXyzMatrix()));
    }

    @GetMapping("/non-moving")
    public ApiResponse<PageResponse<NonMovingReportDto>> nonMoving() {
        return ApiResponse.ok(PageResponse.from(reportService.nonMoving()));
    }

    @GetMapping("/inventory-valuation")
    public ApiResponse<PageResponse<InventoryValuationReportDto>> inventoryValuation(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID materialId
    ) {
        return ApiResponse.ok(PageResponse.from(reportService.inventoryValuation(warehouseId, materialId)));
    }

    @GetMapping("/inventory-valuation/dashboard")
    public ApiResponse<InventoryValuationDashboardDto> inventoryValuationDashboard(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID materialId
    ) {
        return ApiResponse.ok(reportService.inventoryValuationDashboard(warehouseId, materialId));
    }

    @GetMapping("/overdue-deliveries")
    public ApiResponse<PageResponse<OverdueDeliveryReportDto>> overdueDeliveries() {
        return ApiResponse.ok(PageResponse.from(reportService.overdueDeliveries()));
    }

    @PostMapping("/import/materials")
    public ApiResponse<Integer> importMaterials(@RequestParam MultipartFile file) {
        return ApiResponse.ok(importService.importMaterials(file));
    }

    @PostMapping("/import/stock-balances")
    public ApiResponse<Integer> importStockBalances(@RequestParam MultipartFile file) {
        return ApiResponse.ok(importService.importStockBalances(file));
    }

    @PostMapping("/import/reference-mappings")
    public ApiResponse<Integer> importReferenceMappings(@RequestParam MultipartFile file) {
        return ApiResponse.ok(importService.importReferenceMappings(file));
    }

    @PostMapping("/import/ikpu-codes")
    public ApiResponse<Integer> importIkpuCodes(@RequestParam MultipartFile file) {
        return ApiResponse.ok(importService.importIkpuCodes(file));
    }

    @PostMapping("/import/mxik-codes")
    public ApiResponse<Integer> importMxikCodes(@RequestParam MultipartFile file) {
        return ApiResponse.ok(importService.importIkpuCodes(file));
    }

    @PostMapping("/import/tnved-codes")
    public ApiResponse<Integer> importTnvedCodes(@RequestParam MultipartFile file) {
        return ApiResponse.ok(importService.importTnvedCodes(file));
    }

    private ResponseEntity<byte[]> file(String fileName, String contentType, byte[] bytes) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
            .contentType(MediaType.parseMediaType(contentType))
            .body(bytes);
    }
}
