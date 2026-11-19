package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.LaboratoryTestResponse;
import com.enterprise.mm.payload.LaboratoryTestUpdateRequest;
import com.enterprise.mm.payload.LaboratoryTestCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QualityControlDtos.LaboratoryResultRequest;
import com.enterprise.mm.payload.QualityControlDtos.LimsLaboratoryResultCallback;
import com.enterprise.mm.payload.LaboratoryTestSearchRequest;
import com.enterprise.mm.service.LaboratoryTestService;
import jakarta.validation.Valid;
import java.util.UUID;
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
@RequestMapping("/api/quality/laboratory-tests")
@RequiredArgsConstructor
public class LaboratoryTestController {
    private final LaboratoryTestService service;

    @GetMapping
    public ApiResponse<PageResponse<LaboratoryTestResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
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
        LaboratoryTestSearchRequest request = new LaboratoryTestSearchRequest(search, status, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LaboratoryTestResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LaboratoryTestResponse> create(@Valid @RequestBody LaboratoryTestCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LaboratoryTestResponse> update(@PathVariable UUID id, @Valid @RequestBody LaboratoryTestUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<LaboratoryTestResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<LaboratoryTestResponse> start(@PathVariable UUID id) {
        return ApiResponse.ok(service.start(id));
    }

    @PostMapping("/{id}/send-lims")
    public ApiResponse<LaboratoryTestResponse> sendToLims(@PathVariable UUID id) {
        return ApiResponse.ok(service.sendToLims(id));
    }

    @PostMapping("/{id}/result")
    public ApiResponse<LaboratoryTestResponse> result(@PathVariable UUID id, @RequestBody(required = false) LaboratoryResultRequest request) {
        return ApiResponse.ok(service.recordResult(id, request));
    }

    @PostMapping("/lims-callback")
    public ApiResponse<LaboratoryTestResponse> limsCallback(@RequestBody LimsLaboratoryResultCallback request) {
        return ApiResponse.ok(service.applyLimsResult(request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<LaboratoryTestResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

}

