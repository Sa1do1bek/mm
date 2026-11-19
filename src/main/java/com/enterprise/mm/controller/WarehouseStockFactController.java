package com.enterprise.mm.controller;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.WarehouseStockFactService;
import com.enterprise.mm.service.ManualReceiptAccountDraftService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.enums.PickingMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/manual")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class WarehouseStockFactController {
    private final WarehouseStockFactService service;
    private final ManualReceiptAccountDraftService receiptDrafts;

    @PostMapping("/stock-receipt/drafts")
    public ApiResponse<ManualReceiptAccountDraftService.Draft> createReceiptDraft(
        @RequestBody ManualReceiptAccountDraftService.Create request) {
        return ApiResponse.created(receiptDrafts.create(request));
    }

    @GetMapping("/stock-receipt/drafts/{id}")
    public ApiResponse<ManualReceiptAccountDraftService.Draft> receiptDraft(@PathVariable UUID id) {
        return ApiResponse.ok(receiptDrafts.get(id));
    }

    @GetMapping("/stock-issue/options")
    public ApiResponse<WarehouseStockFactPickingDtos.Options> issueOptions(
        @RequestParam UUID warehouseId, @RequestParam UUID materialId,
        @RequestParam(defaultValue = "FIFO") PickingMethod pickingMethod,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.issueOptions(warehouseId, materialId, pickingMethod, page, size));
    }

    @PostMapping("/stock-issue/preview")
    public ApiResponse<WarehouseStockFactPickingDtos.Preview> previewIssue(@Valid @RequestBody WarehouseStockFactRequest request) {
        return ApiResponse.ok(service.previewIssue(request));
    }

    @PostMapping(value = "/stock-receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ManualWarehouseDocumentResponse> receipt(
        @Valid @RequestPart("request") WarehouseStockFactRequest request,
        @RequestPart(value = "files", required = false) List<MultipartFile> files,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.record(request, files, key, true));
    }

    @PostMapping(value = "/stock-issue", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ManualWarehouseDocumentResponse> issue(
        @Valid @RequestPart("request") WarehouseStockFactRequest request,
        @RequestPart(value = "files", required = false) List<MultipartFile> files,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.record(request, files, key, false));
    }
}
