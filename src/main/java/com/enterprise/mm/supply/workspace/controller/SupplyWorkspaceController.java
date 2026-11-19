package com.enterprise.mm.supply.workspace.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.SupplyCalendarResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.ClaimRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.EscalationRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyCommitmentPageResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyOverviewResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyCommitmentContextDtos.SupplyCommitmentContextResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyPerformanceDtos.SupplyPerformanceResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceSelectorDtos.CompanyOption;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceSelectorDtos.OwnerOption;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.service.SupplyCalendarService;
import com.enterprise.mm.supply.workspace.service.SupplyCommitmentContextService;
import com.enterprise.mm.supply.workspace.service.SupplyPerformanceService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkAssignmentService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceQueryService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceSelectorService;
import com.enterprise.mm.supply.workspace.web.SupplyWorkspaceRequestBinder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/supply")
@PreAuthorize(SupplyWorkspaceController.READ_AUTHORIZATION)
public class SupplyWorkspaceController {
    public static final String READ_AUTHORIZATION = "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER',"
        + "'PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD',"
        + "'WAREHOUSE_KEEPER','SUPPLIER_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT','BUDGET_CONTROLLER',"
        + "'EXECUTIVE','AUDITOR','VIEWER')";
    public static final String MANAGER_AUTHORIZATION =
        "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')";
    public static final String SPECIALIST_AUTHORIZATION =
        "hasRole('PROCUREMENT_SPECIALIST')";

    private final SupplyWorkspaceQueryService workspaceService;
    private final SupplyCalendarService calendarService;
    private final SupplyCommitmentContextService contextService;
    private final SupplyPerformanceService performanceService;
    private final SupplyWorkAssignmentService assignmentService;
    private final SupplyWorkspaceSelectorService selectorService;
    private final SupplyWorkspaceAccessPolicy accessPolicy;
    private final SupplyWorkspaceRequestBinder binder;

    public SupplyWorkspaceController(
        SupplyWorkspaceQueryService workspaceService,
        SupplyCalendarService calendarService,
        SupplyCommitmentContextService contextService,
        SupplyPerformanceService performanceService,
        SupplyWorkAssignmentService assignmentService,
        SupplyWorkspaceSelectorService selectorService,
        SupplyWorkspaceAccessPolicy accessPolicy,
        SupplyWorkspaceRequestBinder binder
    ) {
        this.workspaceService = workspaceService;
        this.calendarService = calendarService;
        this.contextService = contextService;
        this.performanceService = performanceService;
        this.assignmentService = assignmentService;
        this.selectorService = selectorService;
        this.accessPolicy = accessPolicy;
        this.binder = binder;
    }

    @GetMapping("/overview")
    public ApiResponse<SupplyOverviewResponse> overview(
        @ModelAttribute SupplyWorkspaceRequest request
    ) {
        SupplyWorkspaceQuery query = readableQuery(request);
        return ApiResponse.ok(workspaceService.overview(query));
    }

    @GetMapping("/commitments")
    public ApiResponse<SupplyCommitmentPageResponse> commitments(
        @ModelAttribute SupplyWorkspaceRequest request,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        SupplyWorkspaceQuery query = readableQuery(request);
        Pageable pageable = binder.pageable(page, size);
        return ApiResponse.ok(workspaceService.commitments(query, pageable));
    }

    @GetMapping("/calendar")
    public ApiResponse<SupplyCalendarResponse> calendar(
        @ModelAttribute SupplyWorkspaceRequest request,
        @RequestParam(required = false) Set<SupplyCalendarLane> sources,
        @RequestParam(name = "sources[]", required = false) Set<SupplyCalendarLane> bracketedSources
    ) {
        SupplyWorkspaceQuery query = readableQuery(request);
        return ApiResponse.ok(calendarService.calendar(
            query, binder.calendarSources(sources, bracketedSources)));
    }

    @GetMapping("/performance")
    public ApiResponse<SupplyPerformanceResponse> performance(
        @ModelAttribute SupplyWorkspaceRequest request
    ) {
        return ApiResponse.ok(performanceService.performance(readableQuery(request)));
    }

    @GetMapping("/commitments/{encodedCommitmentKey}/context")
    public ApiResponse<SupplyCommitmentContextResponse> commitmentContext(
        @PathVariable String encodedCommitmentKey
    ) {
        String key = binder.commitmentKey(encodedCommitmentKey);
        return ApiResponse.ok(contextService.context(key, binder.scope()));
    }

    @GetMapping("/selectors/companies")
    public ApiResponse<List<CompanyOption>> companyOptions(
        @RequestParam(required = false) String search
    ) {
        return ApiResponse.ok(selectorService.companies(binder.scope(), search));
    }

    @GetMapping("/selectors/owners")
    public ApiResponse<List<OwnerOption>> ownerOptions(
        @RequestParam(required = false) UUID companyId,
        @RequestParam(required = false) String search
    ) {
        return ApiResponse.ok(selectorService.owners(binder.scope(), companyId, search));
    }

    @PostMapping("/commitments/{encodedCommitmentKey}/assign")
    @PreAuthorize(MANAGER_AUTHORIZATION)
    public ApiResponse<AssignmentResponse> assign(
        @PathVariable String encodedCommitmentKey,
        @RequestBody(required = false) AssignmentRequest request
    ) {
        String key = binder.commitmentKey(encodedCommitmentKey);
        return ApiResponse.ok(assignmentService.assign(key, binder.scope(), request));
    }

    @PostMapping("/commitments/{encodedCommitmentKey}/claim")
    @PreAuthorize(SPECIALIST_AUTHORIZATION)
    public ApiResponse<AssignmentResponse> claim(
        @PathVariable String encodedCommitmentKey,
        @RequestBody(required = false) ClaimRequest request
    ) {
        String key = binder.commitmentKey(encodedCommitmentKey);
        return ApiResponse.ok(assignmentService.claim(key, binder.scope(), request));
    }

    @PostMapping("/commitments/{encodedCommitmentKey}/escalate")
    @PreAuthorize(MANAGER_AUTHORIZATION)
    public ApiResponse<AssignmentResponse> escalate(
        @PathVariable String encodedCommitmentKey,
        @RequestBody(required = false) EscalationRequest request
    ) {
        String key = binder.commitmentKey(encodedCommitmentKey);
        return ApiResponse.ok(assignmentService.escalate(key, binder.scope(), request));
    }

    private SupplyWorkspaceQuery readableQuery(SupplyWorkspaceRequest request) {
        SupplyWorkspaceQuery query = binder.query(request);
        accessPolicy.requireReadable(query.lens());
        return query;
    }
}
