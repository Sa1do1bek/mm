package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.UUID;
import com.enterprise.mm.enums.StorageZoneType;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.StorageLocationResponse;
import com.enterprise.mm.payload.StorageLocationUpdateRequest;
import com.enterprise.mm.payload.StorageLocationCreateRequest;
import com.enterprise.mm.payload.StorageLocationSearchRequest;
import com.enterprise.mm.service.StorageLocationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/storage-locations")
@RequiredArgsConstructor
public class StorageLocationController {
    private final StorageLocationService service;

    @GetMapping
    public ApiResponse<PageResponse<StorageLocationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) StorageZoneType storageZoneType,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) UUID id,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) Boolean deleted,
        @RequestParam(required = false) String createdBy,
        @RequestParam(required = false) String updatedBy,
        @RequestParam(required = false) Long version,
        @RequestParam(required = false) String zoneCode,
        @RequestParam(required = false) String binCode,
        @RequestParam(required = false) String barcode,
        @RequestParam(required = false) String qrCode,
        @RequestParam(required = false) String rfidTag,
        @RequestParam(required = false) String temperatureZone,
        @RequestParam(required = false) String hazardClassAllowed,
        @RequestParam(required = false) java.math.BigDecimal capacityQuantity,
        @RequestParam(required = false) java.math.BigDecimal maxWeight,
        @RequestParam(required = false) Boolean allowMixedMaterials,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        StorageLocationSearchRequest request = new StorageLocationSearchRequest(
            search, status, warehouseId, storageZoneType, dateFrom, dateTo, includeDeleted, id, code, name, description, deleted,
            createdBy, updatedBy, version, zoneCode, binCode, barcode, qrCode, rfidTag, temperatureZone, hazardClassAllowed,
            capacityQuantity, maxWeight, allowMixedMaterials
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<StorageLocationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StorageLocationResponse> create(@Valid @RequestBody StorageLocationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<StorageLocationResponse> update(@PathVariable UUID id, @Valid @RequestBody StorageLocationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<StorageLocationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

