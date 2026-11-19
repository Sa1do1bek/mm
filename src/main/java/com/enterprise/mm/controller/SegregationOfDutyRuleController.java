package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SegregationOfDutyDtos.SegregationOfDutyCheckRequest;
import com.enterprise.mm.payload.SegregationOfDutyDtos.SegregationOfDutyCheckResult;
import com.enterprise.mm.payload.SegregationOfDutyRuleResponse;
import com.enterprise.mm.payload.SegregationOfDutyRuleUpdateRequest;
import com.enterprise.mm.payload.SegregationOfDutyRuleCreateRequest;
import com.enterprise.mm.payload.SegregationOfDutyViolationResponse;
import com.enterprise.mm.payload.SegregationOfDutyViolationUpdateRequest;
import com.enterprise.mm.payload.SegregationOfDutyViolationCreateRequest;
import com.enterprise.mm.payload.SegregationOfDutyRuleSearchRequest;
import com.enterprise.mm.payload.SegregationOfDutyViolationSearchRequest;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import com.enterprise.mm.service.SegregationOfDutyViolationService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/segregation-of-duty-rules")
@RequiredArgsConstructor
public class SegregationOfDutyRuleController {
    private final SegregationOfDutyRuleService service;

    @GetMapping
    public ApiResponse<PageResponse<SegregationOfDutyRuleResponse>> search(
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
        SegregationOfDutyRuleSearchRequest request = new SegregationOfDutyRuleSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SegregationOfDutyRuleResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SegregationOfDutyRuleResponse> create(@Valid @RequestBody SegregationOfDutyRuleCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SegregationOfDutyRuleResponse> update(@PathVariable UUID id, @Valid @RequestBody SegregationOfDutyRuleUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SegregationOfDutyRuleResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/ensure-default")
    public ApiResponse<SegregationOfDutyRuleResponse> ensureDefault() {
        return ApiResponse.ok(service.ensureDefaultMakerCheckerRule());
    }

    @PostMapping("/ensure-defaults")
    public ApiResponse<List<SegregationOfDutyRuleResponse>> ensureDefaults() {
        return ApiResponse.ok(service.ensureDefaultMakerCheckerRules());
    }

    @PostMapping("/check")
    public ApiResponse<SegregationOfDutyCheckResult> check(@RequestBody SegregationOfDutyCheckRequest request) {
        return ApiResponse.ok(service.check(request));
    }

}

@RestController
@RequestMapping("/api/segregation-of-duty-violations")
class SegregationOfDutyViolationController {
    private final SegregationOfDutyViolationService service;

    @GetMapping
    public ApiResponse<PageResponse<SegregationOfDutyViolationResponse>> search(
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
        SegregationOfDutyViolationSearchRequest request = new SegregationOfDutyViolationSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SegregationOfDutyViolationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SegregationOfDutyViolationResponse> create(@Valid @RequestBody SegregationOfDutyViolationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SegregationOfDutyViolationResponse> update(@PathVariable UUID id, @Valid @RequestBody SegregationOfDutyViolationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SegregationOfDutyViolationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    public SegregationOfDutyViolationController(SegregationOfDutyViolationService service) {
        this.service = service;

    }

}

