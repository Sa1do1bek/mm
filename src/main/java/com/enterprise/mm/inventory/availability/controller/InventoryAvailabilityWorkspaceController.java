package com.enterprise.mm.inventory.availability.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.inventory.availability.enums.DemandUrgencyBucket;
import com.enterprise.mm.inventory.availability.enums.InventoryAvailabilityQueueKey;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityOverview;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityQueueDefinition;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityRow;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityWorkspaceResponse;
import com.enterprise.mm.inventory.availability.service.InventoryAvailabilityWorkspaceService;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/inventory-availability")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST',"
    + "'WAREHOUSE_OPERATOR','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT',"
    + "'EXECUTIVE','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
@RequiredArgsConstructor
public class InventoryAvailabilityWorkspaceController {
    private final InventoryAvailabilityWorkspaceService service;

    @GetMapping
    public ApiResponse<InventoryAvailabilityWorkspaceResponse> workspace(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) Boolean replacementMissing,
            @RequestParam(required = false) Boolean staleBalance,
            @RequestParam(required = false) Boolean nonMoving,
            @RequestParam(required = false) Boolean negativeProjected,
            @RequestParam(required = false) DemandUrgencyBucket urgency,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String materialName,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) List<UUID> warehouseIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return ApiResponse.ok(service.workspace(filter(null, search, warehouseId, materialGroupId,
                replacementMissing, staleBalance, nonMoving, negativeProjected, urgency,
                materialCode, materialName, materialId, warehouseIds, asOfDate, dateFrom, dateTo)));
    }

    @GetMapping("/overview")
    public ApiResponse<InventoryAvailabilityOverview> overview(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) Boolean replacementMissing,
            @RequestParam(required = false) Boolean staleBalance,
            @RequestParam(required = false) Boolean nonMoving,
            @RequestParam(required = false) Boolean negativeProjected,
            @RequestParam(required = false) DemandUrgencyBucket urgency,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String materialName,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) List<UUID> warehouseIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return ApiResponse.ok(service.overview(filter(null, search, warehouseId, materialGroupId,
                replacementMissing, staleBalance, nonMoving, negativeProjected, urgency,
                materialCode, materialName, materialId, warehouseIds, asOfDate, dateFrom, dateTo)));
    }

    @GetMapping("/queues")
    public ApiResponse<List<InventoryAvailabilityQueueDefinition>> queues(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) Boolean replacementMissing,
            @RequestParam(required = false) Boolean staleBalance,
            @RequestParam(required = false) Boolean nonMoving,
            @RequestParam(required = false) Boolean negativeProjected,
            @RequestParam(required = false) DemandUrgencyBucket urgency,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String materialName,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) List<UUID> warehouseIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return ApiResponse.ok(service.queues(filter(null, search, warehouseId, materialGroupId,
                replacementMissing, staleBalance, nonMoving, negativeProjected, urgency,
                materialCode, materialName, materialId, warehouseIds, asOfDate, dateFrom, dateTo)));
    }

    @GetMapping("/positions")
    public ApiResponse<PageResponse<InventoryAvailabilityRow>> positions(
            @RequestParam(required = false) InventoryAvailabilityQueueKey queue,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) Boolean replacementMissing,
            @RequestParam(required = false) Boolean staleBalance,
            @RequestParam(required = false) Boolean nonMoving,
            @RequestParam(required = false) Boolean negativeProjected,
            @RequestParam(required = false) DemandUrgencyBucket urgency,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String materialName,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) List<UUID> warehouseIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page, size, Sort.by(Sort.Order.desc("shortage"), Sort.Order.asc("materialCode")));
        return ApiResponse.ok(PageResponse.from(service.positions(
                filter(queue, search, warehouseId, materialGroupId,
                        replacementMissing, staleBalance, nonMoving, negativeProjected, urgency,
                        materialCode, materialName, materialId, warehouseIds, asOfDate, dateFrom, dateTo), pageable)));
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) InventoryAvailabilityQueueKey queue,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialGroupId,
            @RequestParam(required = false) Boolean replacementMissing,
            @RequestParam(required = false) Boolean staleBalance,
            @RequestParam(required = false) Boolean nonMoving,
            @RequestParam(required = false) Boolean negativeProjected,
            @RequestParam(required = false) DemandUrgencyBucket urgency,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String materialName,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) List<UUID> warehouseIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        byte[] bytes = service.exportExcel(filter(queue, search, warehouseId, materialGroupId,
                replacementMissing, staleBalance, nonMoving, negativeProjected, urgency,
                materialCode, materialName, materialId, warehouseIds, asOfDate, dateFrom, dateTo));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-availability.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    private InventoryAvailabilityFilter filter(
            InventoryAvailabilityQueueKey queue,
            String search,
            UUID warehouseId,
            UUID materialGroupId,
            Boolean replacementMissing,
            Boolean staleBalance,
            Boolean nonMoving,
            Boolean negativeProjected,
            DemandUrgencyBucket urgency,
            String materialCode,
            String materialName,
            UUID materialId,
            List<UUID> warehouseIds,
            LocalDate asOfDate,
            LocalDate dateFrom,
            LocalDate dateTo
    ) {
        return new InventoryAvailabilityFilter(queue, search, warehouseId, materialGroupId,
                replacementMissing, staleBalance, nonMoving, negativeProjected, urgency,
                materialCode, materialName, materialId, warehouseIds, asOfDate, dateFrom, dateTo);
    }
}
