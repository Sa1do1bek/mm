package com.enterprise.mm.controller;

import java.time.LocalDate;
import java.util.List;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.MXIKPathResponse;
import com.enterprise.mm.payload.MXIKSearchResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.mxik.MXIKCreateRequest;
import com.enterprise.mm.payload.mxik.MXIKResponse;
import com.enterprise.mm.payload.mxik.MXIKUpdateRequest;
import com.enterprise.mm.payload.mxikImport.MXIKNameCodeCountProjection;
import com.enterprise.mm.payload.mxikImport.MXIKNameCountProjection;
import com.enterprise.mm.service.MXIKService;
import com.enterprise.mm.service.MXIKProductImportService;
import com.google.protobuf.Api;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;

@RestController
@RequestMapping("/api/mxik")
@RequiredArgsConstructor
public class MXIKController {
    private final MXIKService mxikService;
    private final MXIKProductImportService mxikProductImportService;

    @GetMapping
    public ApiResponse<PageResponse<MXIKResponse>> searchMXIK(
            @RequestParam(required = false) String searchParam,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
                page,
                size,
                Sort.by("createdAt").descending()
        );
        String effectiveSearch = searchParam == null || searchParam.isBlank() ? search : searchParam;
        return ApiResponse.ok(PageResponse.fromSlice(mxikService.searchMXIK(effectiveSearch, type, dateFrom, dateTo, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MXIKResponse> getMXIKById(@PathVariable Long id) {
        return ApiResponse.ok(mxikService.getMXIKById(id));
    }

    @GetMapping("/code/{code}")
    public ApiResponse<MXIKResponse> getMXIKByCode(@PathVariable String code) {
        return ApiResponse.ok(mxikService.getMXIKByCode(code));
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MXIKResponse> createMXIK(@Valid @RequestBody MXIKCreateRequest mxik) {
        return ApiResponse.created(mxikService.createMXIK(mxik));
    }

    @PutMapping("/update/{id}")
    public ApiResponse<MXIKResponse> updateMXIK(@PathVariable Long id, @Valid @RequestBody MXIKUpdateRequest request) {
        return ApiResponse.ok(mxikService.updateMXIK(id, request));
    }

    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteMXIK(@PathVariable Long id) {
        mxikService.deleteMXIK(id);
        return ApiResponse.message("deleted");
    }

    @Operation(summary = "Get mxik catalog step 1")
    @GetMapping("/catalog/groups")
    public ApiResponse<PageResponse<MXIKNameCountProjection>> getImportGroupCounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.unsorted());
        return ApiResponse.ok(PageResponse.from(mxikProductImportService.listGroupCounts(pageable)));
    }

    @Operation(summary = "Get mxik catalog step 2")
    @GetMapping("/catalog/classes")
    public ApiResponse<PageResponse<MXIKNameCountProjection>> getImportClassCounts(@RequestParam String groupName) {
        return ApiResponse.ok(PageResponse.from(mxikProductImportService.listClassCounts(groupName)));
    }

    @Operation(summary = "Get mxik catalog step 3")
    @GetMapping("/catalog/positions")
    public ApiResponse<PageResponse<MXIKNameCountProjection>> getImportPositionCounts(@RequestParam String className) {
        return ApiResponse.ok(PageResponse.from(mxikProductImportService.listPositionCounts(className)));
    }

    @Operation(summary = "Get mxik catalog step 4")
    @GetMapping("/catalog/sub-positions")
    public ApiResponse<PageResponse<MXIKNameCountProjection>> getImportSubPositionCounts(@RequestParam String positionName) {
        return ApiResponse.ok(PageResponse.from(mxikProductImportService.listSubPositionCounts(positionName)));
    }

    @Operation(summary = "Get mxik catalog step 5")
    @GetMapping("/catalog/mxiks")
    public ApiResponse<PageResponse<MXIKNameCodeCountProjection>> getImportMxikCounts(@RequestParam String subPositionName) {
        return ApiResponse.ok(PageResponse.from(mxikProductImportService.listMxikCounts(subPositionName)));
    }

    @GetMapping("/catelog/{name}")
    public ApiResponse<PageResponse<MXIKSearchResponse>> mxikSearchName(
            @PathVariable String name,
            @RequestParam(defaultValue = "0")int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(PageResponse.from(mxikProductImportService.mxikSearchName(name,page,size)));
    }
}

