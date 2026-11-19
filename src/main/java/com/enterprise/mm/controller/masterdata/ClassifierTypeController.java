package com.enterprise.mm.controller.masterdata;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ClassifierTypeResponse;
import com.enterprise.mm.payload.ClassifierTypeUpdateRequest;
import com.enterprise.mm.payload.ClassifierTypeCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ClassifierTypeSearchRequest;
import com.enterprise.mm.service.ClassifierTypeService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/classifier-types")
@RequiredArgsConstructor
public class ClassifierTypeController {
    private final ClassifierTypeService service;

    @GetMapping
    public ApiResponse<PageResponse<ClassifierTypeResponse>> search(
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
        ClassifierTypeSearchRequest request = new ClassifierTypeSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ClassifierTypeResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ClassifierTypeResponse> create(@Valid @RequestBody ClassifierTypeCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ClassifierTypeResponse> update(@PathVariable UUID id, @Valid @RequestBody ClassifierTypeUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ClassifierTypeResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}

