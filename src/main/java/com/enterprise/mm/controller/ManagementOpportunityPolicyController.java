package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ManagementOpportunityPolicyCreateRequest;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResolveRequest;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResolveResponse;
import com.enterprise.mm.payload.ManagementOpportunityPolicyResponse;
import com.enterprise.mm.payload.ManagementOpportunityPolicyUpdateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.ManagementOpportunityPolicyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management-opportunity-policies")
@RequiredArgsConstructor
public class ManagementOpportunityPolicyController {
    private static final String READ_ROLES = "hasAnyRole('ADMIN','EXECUTIVE','MM_MANAGER','FINANCE_CONTROLLER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER','ACCOUNTANT','AUDITOR','VIEWER')";
    private static final String CONTROL_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT')";

    private final ManagementOpportunityPolicyService service;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    public ApiResponse<PageResponse<ManagementOpportunityPolicyResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) ManagementOpportunityType type,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(search, status, active, type, includeDeleted, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ManagementOpportunityPolicyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CONTROL_ROLES)
    public ApiResponse<ManagementOpportunityPolicyResponse> create(
        @Valid @RequestBody ManagementOpportunityPolicyCreateRequest request
    ) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(CONTROL_ROLES)
    public ApiResponse<ManagementOpportunityPolicyResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody ManagementOpportunityPolicyUpdateRequest request
    ) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(CONTROL_ROLES)
    public ApiResponse<ManagementOpportunityPolicyResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(CONTROL_ROLES)
    public ApiResponse<ManagementOpportunityPolicyResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok(service.deactivate(id));
    }

    @PostMapping("/resolve-preview")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ManagementOpportunityPolicyResolveResponse> resolvePreview(
        @Valid @RequestBody ManagementOpportunityPolicyResolveRequest request
    ) {
        return ApiResponse.ok(service.resolvePreview(request));
    }
}
