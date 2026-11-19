package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.MaterialUnitResponse;
import com.enterprise.mm.payload.MaterialUnitSearchRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.MaterialUnitQueryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-units")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
@RequiredArgsConstructor
public class MaterialUnitController {
    private final MaterialUnitQueryService service;

    @GetMapping
    public ApiResponse<PageResponse<MaterialUnitResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID storageLocationId,
        @RequestParam(required = false) UUID storageBinId,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam(required = false) List<MaterialUnitStatus> status,
        @RequestParam(required = false) String sourceReferenceType,
        @RequestParam(required = false) UUID sourceReferenceId,
        @RequestParam(required = false) MaterialUnitOperationType sourceOperationType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        MaterialUnitSearchRequest request = new MaterialUnitSearchRequest(
            search,
            materialId,
            warehouseId,
            storageLocationId,
            storageBinId,
            stockBatchId,
            status,
            sourceReferenceType,
            sourceReferenceId,
            sourceOperationType
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }
}
