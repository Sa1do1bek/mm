package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.UnitOfMeasureCreateRequest;
import com.enterprise.mm.payload.UnitOfMeasureResponse;
import com.enterprise.mm.payload.UnitOfMeasureUpdateRequest;
import com.enterprise.mm.payload.UnitOfMeasureSearchRequest;
import com.enterprise.mm.service.UnitOfMeasureService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/uom")
@RequiredArgsConstructor
public class UnitOfMeasureController {
    private final UnitOfMeasureService service;

    @GetMapping
    public ApiResponse<PageResponse<UnitOfMeasureResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        Locale locale
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        UnitOfMeasureSearchRequest request = new UnitOfMeasureSearchRequest(search, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable,locale)));
    }

    @GetMapping("/{id}")
    public ApiResponse<UnitOfMeasureResponse> get(@PathVariable UUID id, Locale locale) {
        return ApiResponse.ok(service.get(id,locale));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UnitOfMeasureResponse> create(@Valid @RequestBody UnitOfMeasureCreateRequest request, Locale locale) {
        return ApiResponse.created(service.create(request,locale));
    }

    @PutMapping("/{id}")
    public ApiResponse<UnitOfMeasureResponse> update(@PathVariable UUID id, @Valid @RequestBody UnitOfMeasureUpdateRequest request, Locale locale) {
        return ApiResponse.ok(service.update(id, request,locale));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<UnitOfMeasureResponse> restore(@PathVariable UUID id,Locale locale) {
        return ApiResponse.ok(service.restore(id,locale));
    }
}

