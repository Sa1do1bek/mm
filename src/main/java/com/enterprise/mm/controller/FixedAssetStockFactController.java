package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.FixedAssetStockFactService;
import com.enterprise.mm.warehouse.reports.WarehouseReport;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/manual/fixed-asset-stock")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class FixedAssetStockFactController {
    private final FixedAssetStockFactService service;

    @GetMapping("/context")
    public ApiResponse<FixedAssetStockFactService.WarehouseContext> context() { return ApiResponse.ok(service.context()); }

    @GetMapping("/materials")
    public ApiResponse<PageResponse<FixedAssetStockFactService.MaterialOption>> materials(
        @RequestParam(required = false) String search, @RequestParam(required = false) String code,
        @RequestParam(required = false) String name, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.materials(search, code, name,
            PaginationUtils.pageRequest(page, size, Sort.by("code").ascending().and(Sort.by("id")))));
    }

    @GetMapping("/balances")
    public ApiResponse<WarehouseReport> balances(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
        @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.balances(asOfDate, search, page, size));
    }

    @GetMapping("/issue-options")
    public ApiResponse<WarehouseStockFactPickingDtos.Options> issueOptions(@RequestParam UUID materialId,
        @RequestParam(defaultValue = "FIFO") PickingMethod pickingMethod,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.issueOptions(materialId, pickingMethod, page, size));
    }

    @PostMapping("/issue-preview")
    public ApiResponse<WarehouseStockFactPickingDtos.Preview> preview(@Valid @RequestBody FixedAssetStockFactRequest request) {
        return ApiResponse.ok(service.previewIssue(request));
    }

    @PostMapping(value = "/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ManualWarehouseDocumentResponse> receipt(@Valid @RequestPart("request") FixedAssetStockFactRequest request,
        @RequestPart(value = "files", required = false) List<MultipartFile> files, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.record(request, files, key, true));
    }

    @PostMapping(value = "/issue", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ManualWarehouseDocumentResponse> issue(@Valid @RequestPart("request") FixedAssetStockFactRequest request,
        @RequestPart(value = "files", required = false) List<MultipartFile> files, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.record(request, files, key, false));
    }
}
