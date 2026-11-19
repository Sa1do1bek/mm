package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.MaterialCostObservationResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.MaterialCostObservationService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-cost-observations")
@RequiredArgsConstructor
public class MaterialCostObservationController {
    private static final Set<String> SORT_FIELDS = Set.of("observedAt", "unitCost", "sourceType", "createdAt", "updatedAt");
    private final MaterialCostObservationService service;

    @GetMapping
    public ApiResponse<PageResponse<MaterialCostObservationResponse>> candidates(
        @RequestParam(required = false) String mxikCode,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir
    ) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir, SORT_FIELDS, Sort.by("observedAt").descending());
        Pageable pageable = PaginationUtils.pageRequest(page, size, sort);
        return ApiResponse.ok(PageResponse.from(service.candidates(mxikCode, materialId, currencyCode, pageable)));
    }
}
