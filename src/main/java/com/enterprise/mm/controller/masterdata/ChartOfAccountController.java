package com.enterprise.mm.controller.masterdata;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ChartOfAccountResponse;
import com.enterprise.mm.payload.ChartOfAccountUpdateRequest;
import com.enterprise.mm.payload.ChartOfAccountCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ChartOfAccountSearchRequest;
import com.enterprise.mm.service.ChartOfAccountService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/chart-of-accounts")
@RequiredArgsConstructor
public class ChartOfAccountController {
    private static final String MUTATION_ROLES = "hasAnyRole('ADMIN','FINANCE_CONTROLLER')";

    private final ChartOfAccountService service;

    @GetMapping
    public ApiResponse<PageResponse<ChartOfAccountResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
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
        ChartOfAccountSearchRequest request = new ChartOfAccountSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChartOfAccountResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize(MUTATION_ROLES)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChartOfAccountResponse> create(@Valid @RequestBody ChartOfAccountCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ChartOfAccountResponse> update(@PathVariable UUID id, @Valid @RequestBody ChartOfAccountUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ChartOfAccountResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}
