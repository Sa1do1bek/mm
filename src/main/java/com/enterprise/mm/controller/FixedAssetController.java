package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AssetDepreciationEntryResponse;
import com.enterprise.mm.payload.AssetDepreciationRunRequest;
import com.enterprise.mm.payload.AssetDepreciationRunResponse;
import com.enterprise.mm.payload.AssetDepreciationScheduleResponse;
import com.enterprise.mm.payload.AssetEventResponse;
import com.enterprise.mm.payload.AssetInventoryNumberAllocateRequest;
import com.enterprise.mm.payload.AssetInventoryNumberAssignRequest;
import com.enterprise.mm.payload.AssetInventoryNumberCorrectionRequest;
import com.enterprise.mm.payload.FixedAssetAnalyticsSummaryResponse;
import com.enterprise.mm.payload.FixedAssetBulkCommissionRequest;
import com.enterprise.mm.payload.FixedAssetBulkCommissionResponse;
import com.enterprise.mm.payload.FixedAssetCommissionRequest;
import com.enterprise.mm.payload.FixedAssetDepreciationConfigRequest;
import com.enterprise.mm.payload.FixedAssetEquipmentLinkRequest;
import com.enterprise.mm.payload.FixedAssetResponse;
import com.enterprise.mm.payload.FixedAssetRevaluationRequest;
import com.enterprise.mm.payload.FixedAssetSearchRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.service.FixedAssetDepreciationService;
import com.enterprise.mm.service.FixedAssetService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fixed-assets")
@RequiredArgsConstructor
public class FixedAssetController {
    private final FixedAssetService service;
    private final FixedAssetDepreciationService depreciationService;

    @GetMapping
    public ApiResponse<PageResponse<FixedAssetResponse>> search(
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID equipmentId,
        @RequestParam(required = false) String lifecycleStatus,
        @RequestParam(required = false) String custodyKind,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String inventoryNumber,
        @RequestParam(required = false) String serialNumber,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String depreciationMethod,
        @RequestParam(required = false) String query,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        FixedAssetSearchRequest request = new FixedAssetSearchRequest(
            materialId, equipmentId, lifecycleStatus, custodyKind, warehouseId, departmentId,
            search, inventoryNumber, serialNumber, category, depreciationMethod, query
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/analytics/summary")
    public ApiResponse<FixedAssetAnalyticsSummaryResponse> getAnalyticsSummary() {
        return ApiResponse.ok(depreciationService.getAnalyticsSummary());
    }

    @GetMapping("/{id}")
    public ApiResponse<FixedAssetResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<AssetEventResponse>> events(@PathVariable UUID id) {
        return ApiResponse.ok(service.events(id));
    }

    @GetMapping("/{id}/depreciation-schedule")
    public ApiResponse<AssetDepreciationScheduleResponse> getDepreciationSchedule(@PathVariable UUID id) {
        return ApiResponse.ok(depreciationService.getDepreciationSchedule(id));
    }

    @GetMapping("/{id}/depreciation-entries")
    public ApiResponse<PageResponse<AssetDepreciationEntryResponse>> getDepreciationEntries(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("periodDate").descending());
        return ApiResponse.ok(PageResponse.from(depreciationService.getDepreciationEntries(id, pageable)));
    }

    @PostMapping("/{id}/depreciation-config")
    public ApiResponse<FixedAssetResponse> configureDepreciation(
        @PathVariable UUID id,
        @Valid @RequestBody FixedAssetDepreciationConfigRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(depreciationService.configureDepreciation(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/revalue")
    public ApiResponse<FixedAssetResponse> revalue(
        @PathVariable UUID id,
        @Valid @RequestBody FixedAssetRevaluationRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(depreciationService.revalue(id, request, idempotencyKey));
    }

    @PostMapping("/depreciation/run")
    public ApiResponse<AssetDepreciationRunResponse> runDepreciation(
        @Valid @RequestBody AssetDepreciationRunRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(depreciationService.executeDepreciationRun(request, idempotencyKey));
    }

    @PostMapping("/{id}/inventory-number")
    public ApiResponse<FixedAssetResponse> assignInventoryNumber(
        @PathVariable UUID id,
        @Valid @RequestBody AssetInventoryNumberAssignRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.assignInventoryNumber(id, request, idempotencyKey));
    }

    @PostMapping("/inventory-numbers/allocate")
    public ApiResponse<List<String>> allocateInventoryNumbers(
        @Valid @RequestBody AssetInventoryNumberAllocateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.allocateInventoryNumbers(request, idempotencyKey));
    }

    @PostMapping("/{id}/inventory-number/correction")
    public ApiResponse<FixedAssetResponse> correctInventoryNumber(
        @PathVariable UUID id,
        @Valid @RequestBody AssetInventoryNumberCorrectionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.correctInventoryNumber(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/commission")
    public ApiResponse<FixedAssetResponse> commission(
        @PathVariable UUID id,
        @Valid @RequestBody FixedAssetCommissionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.commission(id, request, idempotencyKey));
    }

    @PostMapping("/bulk-commission")
    public ApiResponse<FixedAssetBulkCommissionResponse> bulkCommission(
        @Valid @RequestBody FixedAssetBulkCommissionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.bulkCommission(request, idempotencyKey));
    }

    @PostMapping("/{id}/mark-idle")
    public ApiResponse<FixedAssetResponse> markIdle(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.markIdle(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/reactivate")
    public ApiResponse<FixedAssetResponse> reactivate(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.reactivate(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/send-to-repair")
    public ApiResponse<FixedAssetResponse> sendToRepair(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.sendToRepair(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/complete-repair")
    public ApiResponse<FixedAssetResponse> completeRepair(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.completeRepair(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/return-to-supplier")
    public ApiResponse<FixedAssetResponse> returnToSupplier(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.returnToSupplier(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/dispose")
    public ApiResponse<FixedAssetResponse> dispose(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.dispose(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/link-equipment")
    public ApiResponse<FixedAssetResponse> linkEquipment(
        @PathVariable UUID id,
        @Valid @RequestBody FixedAssetEquipmentLinkRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.linkEquipment(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/unlink-equipment")
    public ApiResponse<FixedAssetResponse> unlinkEquipment(
        @PathVariable UUID id,
        @Valid @RequestBody VersionedAssetCommand request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.unlinkEquipment(id, request, idempotencyKey));
    }
}
