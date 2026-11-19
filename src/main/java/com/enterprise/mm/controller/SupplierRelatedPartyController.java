package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierRelatedPartyResponse;
import com.enterprise.mm.payload.SupplierRelatedPartyUpdateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartyCreateRequest;
import com.enterprise.mm.payload.SupplierRelatedPartySearchRequest;
import com.enterprise.mm.service.SupplierRelatedPartyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/supplier-related-parties")
@RequiredArgsConstructor
public class SupplierRelatedPartyController {
    private final SupplierRelatedPartyService service;

    @GetMapping
    public ApiResponse<PageResponse<SupplierRelatedPartyResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) String partyType,
        @RequestParam(required = false) String riskLevel,
        @RequestParam(required = false) String fullName,
        @RequestParam(required = false) String legalName,
        @RequestParam(required = false) String taxId,
        @RequestParam(required = false) String countryCode,
        @RequestParam(required = false) String positionTitle,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) Boolean pepFlag,
        @RequestParam(required = false) Boolean sanctionsMatch,
        @RequestParam(required = false) Boolean conflictOfInterestFlag,
        @RequestParam(required = false) java.math.BigDecimal ownershipMin,
        @RequestParam(required = false) java.math.BigDecimal ownershipMax,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validTo,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        SupplierRelatedPartySearchRequest request = new SupplierRelatedPartySearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, partyType, riskLevel, fullName, legalName, taxId, countryCode, positionTitle, phone, email, pepFlag, sanctionsMatch, conflictOfInterestFlag, ownershipMin, ownershipMax, validFrom, validTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierRelatedPartyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierRelatedPartyResponse> create(@Valid @RequestBody SupplierRelatedPartyCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierRelatedPartyResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierRelatedPartyUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierRelatedPartyResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}
