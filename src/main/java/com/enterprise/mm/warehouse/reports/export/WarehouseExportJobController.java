package com.enterprise.mm.warehouse.reports.export;

import com.enterprise.mm.payload.ApiResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse-reports/export-jobs")
public class WarehouseExportJobController {

    private final WarehouseExportJobService exportJobService;

    public WarehouseExportJobController(WarehouseExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','OPERATOR')")
    public ApiResponse<WarehouseExportJobService.ExportJobResponse> submitJob(
        @RequestBody WarehouseExportJobService.ExportJobRequest request
    ) {
        return ApiResponse.ok(exportJobService.submitJob(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','OPERATOR')")
    public ApiResponse<WarehouseExportJobService.ExportJobResponse> getJob(
        @PathVariable UUID id
    ) {
        return ApiResponse.ok(exportJobService.getJob(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','OPERATOR')")
    public ApiResponse<List<WarehouseExportJobService.ExportJobResponse>> listJobs() {
        return ApiResponse.ok(exportJobService.listJobs());
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','OPERATOR')")
    public ResponseEntity<byte[]> downloadJobFile(@PathVariable UUID id) {
        WarehouseExportJobService.ExportJobResponse job = exportJobService.getJob(id);
        byte[] bytes = exportJobService.downloadJobFile(id);
        boolean isCsv = "csv".equalsIgnoreCase(job.format());

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.fileName() + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_TYPE, isCsv ? "text/csv; charset=UTF-8" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .body(bytes);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','OPERATOR')")
    public ApiResponse<WarehouseExportJobService.ExportJobResponse> cancelJob(@PathVariable UUID id) {
        return ApiResponse.ok(exportJobService.cancelJob(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','OPERATOR')")
    public ApiResponse<WarehouseExportJobService.ExportJobResponse> cancelJobPost(@PathVariable UUID id) {
        return ApiResponse.ok(exportJobService.cancelJob(id));
    }
}
