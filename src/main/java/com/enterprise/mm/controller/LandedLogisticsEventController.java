package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.LandedLogisticsEventResponse;
import com.enterprise.mm.payload.LandedLogisticsEventUpdateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.LandedLogisticsEventSearchRequest;
import com.enterprise.mm.service.LandedLogisticsEventService;
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
@RequestMapping("/api/landed-logistics-events")
@RequiredArgsConstructor
public class LandedLogisticsEventController {
    private final LandedLogisticsEventService service;

    @GetMapping
    public ApiResponse<PageResponse<LandedLogisticsEventResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) String externalSystem,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        LandedLogisticsEventSearchRequest request = new LandedLogisticsEventSearchRequest(search, status, dateFrom, dateTo, includeDeleted, externalSystem);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LandedLogisticsEventResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LandedLogisticsEventResponse> create(@Valid @RequestBody LandedLogisticsEventCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LandedLogisticsEventResponse> update(@PathVariable UUID id, @Valid @RequestBody LandedLogisticsEventUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<LandedLogisticsEventResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<LandedLogisticsEventResponse> resolve(@PathVariable UUID id) {
        return ApiResponse.ok(service.resolve(id));
    }

}

