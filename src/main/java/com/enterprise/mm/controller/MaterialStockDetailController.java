package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.MaterialStockDetailDtos.*;
import com.enterprise.mm.service.MaterialStockDetailService;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Service-level warehouse/field authorization also applies to direct Java callers. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/materials/{materialId}/command-view")
public class MaterialStockDetailController {
    private final MaterialStockDetailService service;

    @GetMapping({"/stock-detail", "/receipt-origins"})
    public ResponseEntity<ApiResponse<Detail>> detail(@PathVariable UUID materialId,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(defaultValue = "true") boolean positiveOnly,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
            service.detail(materialId, new Filter(warehouseId, stockBatchId, asOf, positiveOnly), page, size)));
    }

    @GetMapping("/movements/{movementId}/allocations")
    public ResponseEntity<ApiResponse<MovementDetail>> allocations(@PathVariable UUID materialId,
        @PathVariable UUID movementId, @RequestParam(required = false) UUID warehouseId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
            service.movement(materialId, movementId, warehouseId, page, size)));
    }
}
