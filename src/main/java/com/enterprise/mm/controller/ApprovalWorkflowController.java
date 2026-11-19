package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ApprovalWorkflowResponse;
import com.enterprise.mm.payload.ApprovalWorkflowUpdateRequest;
import com.enterprise.mm.payload.ApprovalWorkflowCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ApprovalWorkflowSearchRequest;
import com.enterprise.mm.service.ApprovalWorkflowService;
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
@RequestMapping("/api/approval-workflows")
@RequiredArgsConstructor
public class ApprovalWorkflowController {
    private final ApprovalWorkflowService service;

    @GetMapping
    public ApiResponse<PageResponse<ApprovalWorkflowResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) String moduleKey,
        @RequestParam(name = "module", required = false) String module,
        @RequestParam(required = false) String requestType,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) java.util.UUID referenceId,
        @RequestParam(required = false) String referenceEntityType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ApprovalWorkflowSearchRequest request = new ApprovalWorkflowSearchRequest(
            search,
            status,
            firstNonBlank(moduleKey, module),
            requestType,
            dateFrom,
            dateTo,
            includeDeleted,
            referenceId,
            referenceEntityType
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalWorkflowResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalWorkflowResponse> create(@Valid @RequestBody ApprovalWorkflowCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ApprovalWorkflowResponse> update(@PathVariable UUID id, @Valid @RequestBody ApprovalWorkflowUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ApprovalWorkflowResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

}

