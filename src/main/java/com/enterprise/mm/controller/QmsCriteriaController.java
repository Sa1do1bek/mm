package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaCreateRequest;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaResponse;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaUpdateRequest;
import com.enterprise.mm.payload.QmsCriteriaSearchRequest;
import com.enterprise.mm.service.QmsCriteriaService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/qms/criteria")
@RequiredArgsConstructor
public class QmsCriteriaController {
    private static final String MUTATION_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','SUPPLIER_MANAGER','PROCUREMENT_MANAGER')";

    private final QmsCriteriaService service;

    @GetMapping
    public ApiResponse<PageResponse<CriteriaResponse>> search(
        @RequestParam(required = false) UUID companyId,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("code").ascending());
        QmsCriteriaSearchRequest request = new QmsCriteriaSearchRequest(
            companyId,
            search,
            includeInactive,
            includeDeleted,
            dateFrom,
            dateTo
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CriteriaResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize(MUTATION_ROLES)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CriteriaResponse> create(@Valid @RequestBody CriteriaCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<CriteriaResponse> update(@PathVariable UUID id, @Valid @RequestBody CriteriaUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }
}
