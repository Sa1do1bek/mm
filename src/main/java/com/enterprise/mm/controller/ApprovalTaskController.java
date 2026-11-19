package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDetailResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskSummaryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalWorklistFilter;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval-tasks")
@RequiredArgsConstructor
public class ApprovalTaskController {
    private final ApprovalEngineService service;

    @GetMapping("/my")
    public ApiResponse<PageResponse<ApprovalTaskResponse>> myTasks(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "PENDING") String status,
        @RequestParam(required = false) UUID taskId,
        @RequestParam(required = false) UUID entityId,
        @RequestParam(required = false) String search,
        @RequestParam(name = "module", required = false) String moduleKey,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
        @RequestParam(required = false) String dueState,
        @RequestParam(required = false) String ageBucket
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        ApprovalWorklistFilter filter = new ApprovalWorklistFilter(
            taskId, entityId, search, moduleKey, status, dateFrom, dateTo, dueState, ageBucket
        );
        return ApiResponse.ok(PageResponse.from(service.myTasks(pageable, filter)));
    }

    @GetMapping("/my/summary")
    public ApiResponse<ApprovalTaskSummaryResponse> mySummary(
        @RequestParam(required = false) String search,
        @RequestParam(name = "module", required = false) String moduleKey,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo
    ) {
        ApprovalWorklistFilter filter = new ApprovalWorklistFilter(
            null, null, search, moduleKey, ApprovalTaskStatus.PENDING.name(), dateFrom, dateTo, null, null
        );
        return ApiResponse.ok(service.myTaskSummary(filter));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<ApprovalTaskDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.myTaskDetail(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalInstanceResponse> approve(@PathVariable UUID id, @RequestBody(required = false) ApprovalTaskDecisionRequest request) {
        return ApiResponse.ok(service.approveTask(id, request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalInstanceResponse> reject(@PathVariable UUID id, @RequestBody(required = false) ApprovalTaskDecisionRequest request) {
        return ApiResponse.ok(service.rejectTask(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<ApprovalInstanceResponse> returnTask(@PathVariable UUID id, @RequestBody ApprovalTaskDecisionRequest request) {
        return ApiResponse.ok(service.returnTask(id, request.returnToStepOrder(), request.comment()));
    }
}
