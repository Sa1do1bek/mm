package com.enterprise.mm.controller.inventoryfinance;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryFinanceDtos.InventoryPeriodClosePrepareRequest;
import com.enterprise.mm.payload.InventoryFinanceDtos.InventoryPeriodClosePrepareResult;
import com.enterprise.mm.payload.InventoryPeriodCloseResponse;
import com.enterprise.mm.payload.ApprovalOperationResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseUpdateRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseCreateRequest;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseValidationResult;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.InventoryPeriodCloseSearchRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseCommandDtos.ReopenRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseCommandDtos.VersionedCommand;
import com.enterprise.mm.payload.InventoryPeriodCloseWorkspaceDtos.Summary;
import com.enterprise.mm.payload.InventoryPeriodCloseWorkspaceDtos.Workspace;
import com.enterprise.mm.service.InventoryPeriodCloseService;
import com.enterprise.mm.service.InventoryPeriodCloseWorkspaceService;
import com.enterprise.mm.service.approval.ApprovalOperationSubmissionService;
import com.enterprise.mm.service.approval.InventoryPeriodCloseOperationHandler;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/inventory-finance/period-closes")
@RequiredArgsConstructor
public class InventoryPeriodCloseController {
    private final InventoryPeriodCloseService service;
    private final ApprovalOperationSubmissionService operationApprovals;
    private final InventoryPeriodCloseWorkspaceService workspaceService;

    @GetMapping("/workspace/summary")
    public ApiResponse<Summary> workspaceSummary() {
        return ApiResponse.ok(workspaceService.summary());
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<Workspace> workspace(@PathVariable UUID id) {
        return ApiResponse.ok(workspaceService.workspace(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<InventoryPeriodCloseResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
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
        InventoryPeriodCloseSearchRequest request = new InventoryPeriodCloseSearchRequest(search, status, warehouseId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryPeriodCloseResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryPeriodCloseResponse> create(@Valid @RequestBody InventoryPeriodCloseCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryPeriodCloseResponse> update(@PathVariable UUID id, @Valid @RequestBody InventoryPeriodCloseUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<InventoryPeriodCloseResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/prepare")
    public ApiResponse<InventoryPeriodClosePrepareResult> prepare(@RequestBody(required = false) InventoryPeriodClosePrepareRequest request) {
        return ApiResponse.ok(service.prepare(request));
    }

    @PostMapping("/{id}/prepare")
    public ApiResponse<InventoryPeriodClosePrepareResult> prepare(@PathVariable UUID id, @RequestBody(required = false) InventoryPeriodClosePrepareRequest request) {
        return ApiResponse.ok(service.prepare(id, request));
    }

    @PostMapping("/{id}/calculate")
    public ApiResponse<InventoryPeriodCloseResponse> calculate(
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) VersionedCommand request
    ) {
        return ApiResponse.ok(service.calculate(id, request == null ? null : request.expectedVersion()));
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ApprovalOperationResponse> close(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody(required = false) VersionedCommand request
    ) {
        return ApiResponse.ok(operationApprovals.submit(
            InventoryPeriodCloseOperationHandler.MODULE_KEY, id,
            request == null ? null : request.expectedVersion(), "{}", idempotencyKey
        ));
    }

    @PostMapping("/{id}/validate")
    public ApiResponse<InventoryPeriodCloseValidationResult> validate(@PathVariable UUID id) {
        return ApiResponse.ok(service.validate(id));
    }

    @GetMapping("/{id}/issues")
    public ApiResponse<List<InventoryValuationReconciliationIssueResponse>> issues(@PathVariable UUID id) {
        return ApiResponse.ok(service.issues(id));
    }

    @PostMapping("/{id}/reopen")
    public ApiResponse<InventoryPeriodCloseResponse> reopen(
        @PathVariable UUID id,
        @Valid @RequestBody ReopenRequest request
    ) {
        return ApiResponse.ok(service.reopen(id, request.expectedVersion(), request.reason()));
    }
}
