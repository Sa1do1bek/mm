package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.NonMovingStockCaseCreateRequest;
import com.enterprise.mm.payload.NonMovingStockCaseResponse;
import com.enterprise.mm.payload.NonMovingStockDecisionCreateRequest;
import com.enterprise.mm.payload.NonMovingStockDecisionResponse;
import com.enterprise.mm.payload.NonMovingStockExecutionResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.NonMovingStockCaseSearchRequest;
import com.enterprise.mm.service.NonMovingStockCaseService;
import com.enterprise.mm.service.NonMovingStockSavedViewService;
import com.enterprise.mm.service.NonMovingStockWorkspaceService;
import com.enterprise.mm.payload.NonMovingStockWorkspaceFilter;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.CaseWorkspace;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewRequest;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewResponse;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SelectorOption;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceMode;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceSummary;
import com.enterprise.mm.payload.NonMovingStockResponse;
import com.enterprise.mm.payload.NonMovingStockCaseCommandDtos;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/non-moving-stock-cases")
@RequiredArgsConstructor
public class NonMovingStockCaseController {
    private final NonMovingStockCaseService service;
    private final NonMovingStockWorkspaceService workspaceService;
    private final NonMovingStockSavedViewService savedViewService;

    @GetMapping("/workspace/saved-views")
    public ApiResponse<List<SavedViewResponse>> savedViews(@RequestParam(required = false) WorkspaceMode mode) {
        return ApiResponse.ok(savedViewService.list(mode));
    }

    @PostMapping("/workspace/saved-views")
    public ApiResponse<SavedViewResponse> saveView(@Valid @RequestBody SavedViewRequest request) {
        return ApiResponse.ok(savedViewService.save(request));
    }

    @DeleteMapping("/workspace/saved-views/{viewId}")
    public ApiResponse<Void> deleteView(@PathVariable UUID viewId) {
        savedViewService.delete(viewId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/workspace/selectors/users")
    public ApiResponse<PageResponse<SelectorOption>> workspaceUsers(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(PageResponse.from(workspaceService.userSelector(
            search, PaginationUtils.pageRequest(page, size, Sort.by("fullName").ascending())
        )));
    }

    @GetMapping("/workspace/selectors/commissions")
    public ApiResponse<PageResponse<SelectorOption>> workspaceCommissions(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(PageResponse.from(workspaceService.commissionSelector(
            search, PaginationUtils.pageRequest(page, size, Sort.by("name").ascending())
        )));
    }

    @GetMapping("/workspace/summary")
    public ApiResponse<WorkspaceSummary> workspaceSummary(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) List<UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(required = false) UUID responsibleUserId,
        @RequestParam(required = false) Boolean overdueOnly,
        @RequestParam(required = false) List<String> decisionType,
        @RequestParam(required = false) List<String> executionStatus,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false) BigDecimal minBookValue,
        @RequestParam(required = false) BigDecimal maxBookValue,
        @RequestParam(required = false) List<String> blockerType,
        @RequestParam(required = false) List<String> integrationStatus,
        @RequestParam(required = false) List<String> exclusionResult,
        @RequestParam(required = false) Boolean activeCaseOnly
    ) {
        return ApiResponse.ok(workspaceService.summary(workspaceFilter(
            search, status, warehouseId, materialIds, dateFrom, dateTo, responsibleUserId, overdueOnly,
            decisionType, executionStatus, materialGroupId, minBookValue, maxBookValue, blockerType,
            integrationStatus, exclusionResult, activeCaseOnly
        )));
    }

    @GetMapping("/workspace/positions")
    public ApiResponse<PageResponse<NonMovingStockResponse>> workspacePositions(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) List<UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(required = false) UUID responsibleUserId,
        @RequestParam(required = false) Boolean overdueOnly,
        @RequestParam(required = false) List<String> decisionType,
        @RequestParam(required = false) List<String> executionStatus,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false) BigDecimal minBookValue,
        @RequestParam(required = false) BigDecimal maxBookValue,
        @RequestParam(required = false) List<String> blockerType,
        @RequestParam(required = false) List<String> integrationStatus,
        @RequestParam(required = false) List<String> exclusionResult,
        @RequestParam(required = false) Boolean activeCaseOnly,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(workspaceService.positions(
            workspaceFilter(
                search, status, warehouseId, materialIds, dateFrom, dateTo, responsibleUserId, overdueOnly,
                decisionType, executionStatus, materialGroupId, minBookValue, maxBookValue, blockerType,
                integrationStatus, exclusionResult, activeCaseOnly
            ), pageable
        )));
    }

    @GetMapping("/workspace/positions/{id}")
    public ApiResponse<NonMovingStockResponse> workspacePosition(@PathVariable UUID id) {
        return ApiResponse.ok(workspaceService.position(id));
    }

    @GetMapping("/workspace/cases")
    public ApiResponse<PageResponse<NonMovingStockCaseResponse>> workspaceCases(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) List<UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(required = false) UUID responsibleUserId,
        @RequestParam(required = false) Boolean overdueOnly,
        @RequestParam(required = false) List<String> decisionType,
        @RequestParam(required = false) List<String> executionStatus,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false) BigDecimal minBookValue,
        @RequestParam(required = false) BigDecimal maxBookValue,
        @RequestParam(required = false) List<String> blockerType,
        @RequestParam(required = false) List<String> integrationStatus,
        @RequestParam(required = false) List<String> exclusionResult,
        @RequestParam(required = false) Boolean activeCaseOnly,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(workspaceService.cases(
            workspaceFilter(
                search, status, warehouseId, materialIds, dateFrom, dateTo, responsibleUserId, overdueOnly,
                decisionType, executionStatus, materialGroupId, minBookValue, maxBookValue, blockerType,
                integrationStatus, exclusionResult, activeCaseOnly
            ), pageable
        )));
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<CaseWorkspace> workspace(@PathVariable UUID id) {
        return ApiResponse.ok(workspaceService.workspace(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<NonMovingStockCaseResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        NonMovingStockCaseSearchRequest request = new NonMovingStockCaseSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<NonMovingStockCaseResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/from-detected")
    public ApiResponse<NonMovingStockCaseResponse> createCaseFromDetected(@Valid @RequestBody NonMovingStockCaseCreateRequest request) {
        return ApiResponse.created(service.createCaseFromDetected(request));
    }

    @PostMapping("/{id}/decisions")
    public ApiResponse<NonMovingStockDecisionResponse> approveDecision(
        @PathVariable UUID id,
        @Valid @RequestBody NonMovingStockDecisionCreateRequest request
    ) {
        return ApiResponse.created(service.approveDecision(id, request));
    }

    @PostMapping("/{id}/decisions/{decisionId}/execute")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','FINANCE_CONTROLLER','ACCOUNTANT')")
    public ApiResponse<NonMovingStockExecutionResponse> executeDecision(
        @PathVariable UUID id,
        @PathVariable UUID decisionId,
        @Valid @RequestBody(required = false) NonMovingStockCaseCommandDtos.ExecutionRequest request
    ) {
        return ApiResponse.ok(request == null
            ? service.executeDecision(id, decisionId)
            : service.executeDecision(id, decisionId, request.idempotencyKey(), request.expectedVersion()));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<NonMovingStockCaseResponse> assign(
        @PathVariable UUID id, @Valid @RequestBody NonMovingStockCaseCommandDtos.AssignmentRequest request
    ) {
        return ApiResponse.ok(service.assign(id, request));
    }

    @PostMapping("/{id}/review-due-date")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<NonMovingStockCaseResponse> changeReviewDueDate(
        @PathVariable UUID id, @Valid @RequestBody NonMovingStockCaseCommandDtos.DueDateRequest request
    ) {
        return ApiResponse.ok(service.changeReviewDueDate(id, request));
    }

    @PostMapping("/{id}/start-review")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<NonMovingStockCaseResponse> startReview(
        @PathVariable UUID id, @RequestBody NonMovingStockCaseCommandDtos.TransitionRequest request
    ) {
        return ApiResponse.ok(service.startReview(id, request.expectedVersion(), request.comment()));
    }

    @PostMapping("/{id}/send-to-commission")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<NonMovingStockCaseResponse> sendToCommission(
        @PathVariable UUID id, @RequestBody NonMovingStockCaseCommandDtos.TransitionRequest request
    ) {
        return ApiResponse.ok(service.sendToCommission(id, request.expectedVersion(), request.comment()));
    }

    @PostMapping("/{id}/defer-review")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<NonMovingStockCaseResponse> deferReview(
        @PathVariable UUID id, @Valid @RequestBody NonMovingStockCaseCommandDtos.DeferredReviewRequest request
    ) {
        return ApiResponse.ok(service.deferReview(id, request));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT')")
    public ApiResponse<NonMovingStockCaseResponse> close(
        @PathVariable UUID id, @Valid @RequestBody NonMovingStockCaseCommandDtos.CloseRequest request
    ) {
        return ApiResponse.ok(service.close(id, request.expectedVersion(), request.comment()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<NonMovingStockCaseResponse> cancel(
        @PathVariable UUID id, @Valid @RequestBody NonMovingStockCaseCommandDtos.CancelRequest request
    ) {
        return ApiResponse.ok(service.cancel(id, request.expectedVersion(), request.reason()));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<NonMovingStockCaseResponse> reopen(
        @PathVariable UUID id, @Valid @RequestBody NonMovingStockCaseCommandDtos.ReopenRequest request
    ) {
        return ApiResponse.ok(service.reopen(id, request.expectedVersion(), request.reason()));
    }

    private NonMovingStockWorkspaceFilter workspaceFilter(
        String search,
        List<String> status,
        UUID warehouseId,
        List<UUID> materialIds,
        java.time.LocalDate dateFrom,
        java.time.LocalDate dateTo,
        UUID responsibleUserId,
        Boolean overdueOnly,
        List<String> decisionTypes,
        List<String> executionStatuses,
        UUID materialGroupId,
        BigDecimal minBookValue,
        BigDecimal maxBookValue,
        List<String> blockerTypes,
        List<String> integrationStatuses,
        List<String> exclusionResults,
        Boolean activeCaseOnly
    ) {
        return new NonMovingStockWorkspaceFilter(
            search, status, warehouseId, materialIds, dateFrom, dateTo, responsibleUserId,
            overdueOnly, decisionTypes, executionStatuses, materialGroupId, minBookValue, maxBookValue,
            blockerTypes, integrationStatuses, exclusionResults, activeCaseOnly
        );
    }
}
