package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.payload.IntegrationEventUpdateRequest;
import com.enterprise.mm.payload.IntegrationEventCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.IntegrationEventSearchRequest;
import com.enterprise.mm.mapper.IntegrationEventMapper;
import com.enterprise.mm.service.IntegrationDispatchService;
import com.enterprise.mm.service.IntegrationEventService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/integrations/events")
@RequiredArgsConstructor
public class IntegrationEventController {
    private final IntegrationEventService service;

    @GetMapping
    public ApiResponse<PageResponse<IntegrationEventResponse>> search(
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
        IntegrationEventSearchRequest request = new IntegrationEventSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<IntegrationEventResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IntegrationEventResponse> create(@Valid @RequestBody IntegrationEventCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<IntegrationEventResponse> update(@PathVariable UUID id, @Valid @RequestBody IntegrationEventUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<IntegrationEventResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    private final IntegrationDispatchService dispatchService;
    private final IntegrationEventMapper mapper;

    @PostMapping("/idempotent")
    public ApiResponse<IntegrationEventResponse> createIdempotent(@RequestBody IntegrationEventCreateRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.createIdempotent(request, key));
    }

    @PostMapping("/{id}/failed")
    public ApiResponse<IntegrationEventResponse> markFailed(@PathVariable UUID id, @RequestBody(required = false) String error) {
        return ApiResponse.ok(service.markFailed(id, error == null ? "external integration failed" : error));
    }

    @PostMapping("/{id}/dispatch")
    public ApiResponse<IntegrationEventResponse> dispatch(@PathVariable UUID id) {
        return ApiResponse.ok(mapper.toResponse(dispatchService.dispatch(id)));
    }

    @PostMapping("/dispatch-due")
    public ApiResponse<Integer> dispatchDue() {
        return ApiResponse.ok(dispatchService.dispatchDue());
    }

    @GetMapping("/pending")
    public ApiResponse<PageResponse<IntegrationEventResponse>> pending() {
        return ApiResponse.ok(PageResponse.from(dispatchService.pending().stream().map(mapper::toResponse).toList()));
    }

}

