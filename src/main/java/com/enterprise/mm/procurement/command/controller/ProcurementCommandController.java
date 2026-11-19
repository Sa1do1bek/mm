package com.enterprise.mm.procurement.command.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AvailableActions;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AssignmentRequest;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AssignmentResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ClaimRequest;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.EscalationRequest;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandSummary;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandTimelineEntry;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ProcurementCommandCaseDetail;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.ProcurementCommandCaseRow;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.QueueDefinition;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.QuotationComparisonRow;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.service.ProcurementCommandDetailService;
import com.enterprise.mm.procurement.command.service.ProcurementCommandQueryService;
import com.enterprise.mm.procurement.command.service.ProcurementQueueService;
import com.enterprise.mm.procurement.command.service.ProcurementTimelineService;
import com.enterprise.mm.procurement.command.service.ProcurementWorkAssignmentService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/procurement-command")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','AUDITOR','VIEWER')")
public class ProcurementCommandController {

    private static final Set<String> CASE_SORT_FIELDS = Set.of(
        "createdAt",
        "requiredDate",
        "totalAmount",
        "code"
    );

    private final ProcurementCommandQueryService queryService;
    private final ProcurementCommandDetailService detailService;
    private final ProcurementQueueService queueService;
    private final ProcurementTimelineService timelineService;
    private ProcurementWorkAssignmentService assignmentService;

    public ProcurementCommandController(
        ProcurementCommandQueryService queryService,
        ProcurementCommandDetailService detailService,
        ProcurementQueueService queueService,
        ProcurementTimelineService timelineService
    ) {
        this.queryService = queryService;
        this.detailService = detailService;
        this.queueService = queueService;
        this.timelineService = timelineService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAssignmentService(ProcurementWorkAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @GetMapping("/summary")
    public ApiResponse<CommandSummary> summary() {
        long open = queueService.count(ProcurementQueueKey.BUYER_PREPARATION, null)
            + queueService.count(ProcurementQueueKey.AWAITING_APPROVAL, null)
            + queueService.count(ProcurementQueueKey.APPROVED_UNSOURCED, null);
        return ApiResponse.ok(new CommandSummary(
            open,
            queueService.count(ProcurementQueueKey.OVERDUE_WORK, null),
            queueService.count(ProcurementQueueKey.PO_DISPATCH_BLOCKED, null),
            queueService.count(ProcurementQueueKey.PO_READY_TO_DISPATCH, null),
            LocalDateTime.now()));
    }

    @GetMapping("/queues")
    public ApiResponse<List<QueueDefinition>> queues(ProcurementCommandSearchRequest request) {
        return ApiResponse.ok(queueService.definitions(request));
    }

    @GetMapping("/cases")
    public ApiResponse<PageResponse<ProcurementCommandCaseRow>> search(
        ProcurementCommandSearchRequest request,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) String sortBy,
        @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = PaginationUtils.sort(
            sortBy,
            sortDir,
            CASE_SORT_FIELDS,
            Sort.by("requiredDate").ascending()
                .and(Sort.by("createdAt").descending())
        );
        Pageable pageable = PaginationUtils.pageRequest(page, size, sort);
        return ApiResponse.ok(PageResponse.from(queryService.search(request, pageable)));
    }

    @GetMapping("/cases/{id}")
    public ApiResponse<ProcurementCommandCaseDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(detailService.get(id));
    }

    @GetMapping("/cases/{id}/quotation-comparison")
    public ApiResponse<PageResponse<QuotationComparisonRow>> comparison(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("totalAmount").ascending());
        return ApiResponse.ok(PageResponse.from(detailService.comparison(id, pageable)));
    }

    @GetMapping("/cases/{id}/timeline")
    public ApiResponse<PageResponse<CommandTimelineEntry>> timeline(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("occurredAt").descending());
        return ApiResponse.ok(PageResponse.from(timelineService.timeline(id, pageable)));
    }

    @GetMapping("/cases/{id}/available-actions")
    public ApiResponse<AvailableActions> actions(@PathVariable UUID id) {
        return ApiResponse.ok(detailService.actions(id));
    }

    @PutMapping("/cases/{id}/assignment")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<AssignmentResponse> assign(
        @PathVariable UUID id,
        @RequestBody AssignmentRequest request
    ) {
        return ApiResponse.ok(assignmentService.assign(id, request));
    }

    @PostMapping("/cases/{id}/claim")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<AssignmentResponse> claim(
        @PathVariable UUID id,
        @RequestBody(required = false) ClaimRequest request
    ) {
        return ApiResponse.ok(assignmentService.claim(id, request));
    }

    @PostMapping("/cases/{id}/escalate")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<AssignmentResponse> escalate(
        @PathVariable UUID id,
        @RequestBody EscalationRequest request
    ) {
        return ApiResponse.ok(assignmentService.escalate(id, request));
    }
}
