package com.enterprise.mm.controller;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowHierarchyTemplateResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepResponse;
import com.enterprise.mm.payload.ApprovalFlowSearchRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.approval.ApprovalFlowAdminService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/approval-flows")
@RequiredArgsConstructor
public class ApprovalFlowController {
    private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();
    private static final Set<String> SORTABLE_FIELDS = Set.of(
        "name",
        "code",
        "companyId",
        "moduleKey",
        "requestType",
        "active",
        "updatedAt",
        "createdAt"
    );

    private final ApprovalFlowAdminService service;

    @GetMapping
    public ApiResponse<PageResponse<ApprovalFlowResponse>> search(
        @RequestParam(required = false) String moduleKey,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) List<String> requestType,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, parseSort(sort), "e");
        ApprovalFlowSearchRequest request = new ApprovalFlowSearchRequest(search, dateFrom, dateTo, includeDeleted, moduleKey, departmentId, requestType, resolveActive(active, status));
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalFlowResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/steps/hierarchy-template")
    public ApiResponse<ApprovalFlowHierarchyTemplateResponse> hierarchyTemplate(
        @RequestParam UUID departmentId,
        @RequestParam(required = false) String moduleKey,
        @RequestParam(required = false) ApprovalRequestType requestType
    ) {
        return ApiResponse.ok(service.buildHierarchyTemplate(departmentId, moduleKey, requestType));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalFlowResponse> create(@Valid @RequestBody ApprovalFlowRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ApprovalFlowResponse> update(@PathVariable UUID id, @Valid @RequestBody ApprovalFlowRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.message("soft-deleted");
    }

    @GetMapping("/{flowId}/steps")
    public ApiResponse<List<ApprovalFlowStepResponse>> steps(@PathVariable UUID flowId) {
        return ApiResponse.ok(service.steps(flowId));
    }

    @PostMapping("/{flowId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalFlowStepResponse> createStep(@PathVariable UUID flowId, @Valid @RequestBody ApprovalFlowStepRequest request) {
        return ApiResponse.created(service.createStep(flowId, request));
    }

    @PutMapping("/{flowId}/steps/order")
    public ApiResponse<List<ApprovalFlowStepResponse>> reorderSteps(
        @PathVariable UUID flowId, @RequestBody List<UUID> stepIds
    ) {
        return ApiResponse.ok(service.reorderSteps(flowId, stepIds));
    }

    private Boolean resolveActive(Boolean active, List<String> status) {
        if (active != null || status == null || status.isEmpty()) {
            return active;
        }
        String normalized = status.get(0) == null ? "" : status.get(0).trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        return !"INACTIVE".equals(normalized) && !"DISABLED".equals(normalized) && !"FALSE".equals(normalized);
    }

    private Sort parseSort(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SORT;
        }
        String[] parts = value.split(",", -1);
        if (parts.length > 2) {
            return DEFAULT_SORT;
        }
        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            return DEFAULT_SORT;
        }
        String directionValue = parts.length == 2 ? parts[1].trim() : "desc";
        try {
            Sort.Direction direction = Sort.Direction.valueOf(directionValue.toUpperCase(Locale.ROOT));
            return Sort.by(direction, field);
        } catch (IllegalArgumentException error) {
            return DEFAULT_SORT;
        }
    }
}
