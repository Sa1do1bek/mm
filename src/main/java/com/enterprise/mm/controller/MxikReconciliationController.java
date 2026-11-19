package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.MxikReconciliationService;
import com.enterprise.mm.service.MaterialMatchingQuery;
import com.enterprise.mm.service.MxikReconciliationService.AssignmentRequest;
import com.enterprise.mm.service.MxikReconciliationService.MaterialRow;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mxik-reconciliation")
@RequiredArgsConstructor
public class MxikReconciliationController {
    private final MxikReconciliationService service;
    private final MaterialMatchingQuery matching;

    @GetMapping("/matching-materials")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER')")
    public ApiResponse<PageResponse<MaterialMatchingQuery.Row>> matchingMaterials(
        @RequestParam(defaultValue = "ALL") String filter,
        @RequestParam(required = false) String mxikStatus,
        @RequestParam(required = false) String accountStatus,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(matching.list(filter, mxikStatus, accountStatus, search, page, size));
    }

    @GetMapping("/materials")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER')")
    public ApiResponse<PageResponse<MaterialRow>> materials(
        @RequestParam(defaultValue = "true") boolean missingOnly,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.list(missingOnly, search,
            PaginationUtils.pageRequest(page, size, Sort.by("name").ascending()))));
    }

    @GetMapping("/materials/{id}/similar")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER')")
    public ApiResponse<List<MaterialRow>> similar(@PathVariable UUID id) {
        return ApiResponse.ok(service.sameGroupWithMxik(id));
    }

    @GetMapping("/materials/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER')")
    public ApiResponse<MaterialRow> material(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/assignments")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<List<MaterialRow>> assign(@RequestBody AssignmentRequest request) {
        return ApiResponse.ok(service.assign(request));
    }
}
