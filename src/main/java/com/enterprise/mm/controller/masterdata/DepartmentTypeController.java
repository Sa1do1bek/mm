package com.enterprise.mm.controller.masterdata;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DepartmentTypeResponse;
import com.enterprise.mm.payload.DepartmentTypeUpdateRequest;
import com.enterprise.mm.payload.DepartmentTypeCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.DepartmentTypeSearchRequest;
import com.enterprise.mm.service.DepartmentTypeService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/department-types")
@RequiredArgsConstructor
public class DepartmentTypeController {
    private final DepartmentTypeService service;

    @GetMapping
    public ApiResponse<PageResponse<DepartmentTypeResponse>> search(
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
        DepartmentTypeSearchRequest request = new DepartmentTypeSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DepartmentTypeResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DepartmentTypeResponse> create(@Valid @RequestBody DepartmentTypeCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DepartmentTypeResponse> update(@PathVariable UUID id, @Valid @RequestBody DepartmentTypeUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<DepartmentTypeResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}

