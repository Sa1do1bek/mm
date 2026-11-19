package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.enterprise.mm.error.GlobalExceptionHandler;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.supply.workspace.controller.SupplyWorkspaceController;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.error.SupplyAssignmentConflictException;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentOwner;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.AssignmentResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.ClaimRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyAssignmentDtos.EscalationRequest;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.SupplyCalendarResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyCommitmentPageResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyOverviewResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceSelectorDtos.CompanyOption;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceSelectorDtos.OwnerOption;
import com.enterprise.mm.supply.workspace.service.SupplyCalendarService;
import com.enterprise.mm.supply.workspace.service.SupplyCommitmentContextService;
import com.enterprise.mm.supply.workspace.service.SupplyPerformanceService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkAssignmentService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceQueryService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceSelectorService;
import com.enterprise.mm.supply.workspace.web.SupplyWorkspaceRequestBinder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class SupplyWorkspaceControllerTest {
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-15T06:00:00Z");

    private SupplyWorkspaceQueryService workspaceService;
    private SupplyCalendarService calendarService;
    private SupplyCommitmentContextService contextService;
    private SupplyPerformanceService performanceService;
    private SupplyWorkAssignmentService assignmentService;
    private SupplyWorkspaceSelectorService selectorService;
    private SupplyWorkspaceAccessPolicy accessPolicy;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        workspaceService = mock(SupplyWorkspaceQueryService.class);
        calendarService = mock(SupplyCalendarService.class);
        contextService = mock(SupplyCommitmentContextService.class);
        performanceService = mock(SupplyPerformanceService.class);
        assignmentService = mock(SupplyWorkAssignmentService.class);
        selectorService = mock(SupplyWorkspaceSelectorService.class);
        accessPolicy = mock(SupplyWorkspaceAccessPolicy.class);
        RoleDataScopeService dataScopeService = mock(RoleDataScopeService.class);
        when(dataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());

        SupplyWorkspaceRequestBinder binder = new SupplyWorkspaceRequestBinder(
            new SupplyWorkspaceFilterFactory(Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Tashkent"),
            dataScopeService,
            new com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec()
        );
        SupplyWorkspaceController controller = new SupplyWorkspaceController(
            workspaceService,
            calendarService,
            contextService,
            performanceService,
            assignmentService,
            selectorService,
            accessPolicy,
            binder
        );
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();

        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "manager", "n/a", "ROLE_MM_MANAGER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void overviewReturnsApiEnvelopeAndNormalizedAppliedFilters() throws Exception {
        when(workspaceService.overview(any())).thenAnswer(invocation -> overview(invocation.getArgument(0)));

        mvc.perform(get("/api/workspaces/supply/overview")
                .param("from", "2026-07-01")
                .param("to", "2026-08-01")
                .param("lens", "TEAM")
                .param("currencyCode", " usd ")
                .param("search", " bolts "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data.appliedScope.tenantId").value("tenant-a"))
            .andExpect(jsonPath("$.data.appliedScope.companyIds[0]").value(COMPANY_ID.toString()))
            .andExpect(jsonPath("$.data.appliedFilters.currencyCode").value("USD"))
            .andExpect(jsonPath("$.data.appliedFilters.search").value("bolts"))
            .andExpect(jsonPath("$.data.definitionVersion").value("supply-workspace.v1"))
            .andExpect(jsonPath("$.timestamp").isString());

        ArgumentCaptor<SupplyWorkspaceQuery> query = ArgumentCaptor.forClass(SupplyWorkspaceQuery.class);
        verify(workspaceService).overview(query.capture());
        assertThat(query.getValue().from()).isEqualTo("2026-07-01");
        assertThat(query.getValue().to()).isEqualTo("2026-08-01");
        verify(accessPolicy).requireReadable(query.getValue().lens());
    }

    @Test
    void commitmentsBindStablePageMetadataAndAmountSortGuard() throws Exception {
        when(workspaceService.commitments(any(), any())).thenAnswer(invocation ->
            commitments(invocation.getArgument(0), invocation.getArgument(1)));

        mvc.perform(get("/api/workspaces/supply/commitments")
                .param("from", "2026-07-01")
                .param("to", "2026-08-01")
                .param("sort", "OPEN_AMOUNT")
                .param("currencyCode", "uzs")
                .param("page", "2")
                .param("size", "25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.size").value(25))
            .andExpect(jsonPath("$.data.totalElements").value(52))
            .andExpect(jsonPath("$.data.totalPages").value(3));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(workspaceService).commitments(any(), page.capture());
        assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void calendarBindsAxiosBracketedSourcesAndExclusiveRange() throws Exception {
        when(calendarService.calendar(any(), anySet())).thenAnswer(invocation ->
            calendar(invocation.getArgument(0)));

        mvc.perform(get("/api/workspaces/supply/calendar")
                .param("from", "2026-07-01")
                .param("to", "2026-07-08")
                .param("sources[]", "DELIVERY", "PAYMENT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.appliedFilters.from").value("2026-07-01"))
            .andExpect(jsonPath("$.data.appliedFilters.to").value("2026-07-08"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<SupplyCalendarLane>> lanes = ArgumentCaptor.forClass(Set.class);
        verify(calendarService).calendar(any(), lanes.capture());
        assertThat(lanes.getValue()).containsExactlyInAnyOrder(
            SupplyCalendarLane.DELIVERY, SupplyCalendarLane.PAYMENT);
    }

    @Test
    void performanceUsesTheSameAuthenticatedNormalizedWorkspaceQuery() throws Exception {
        mvc.perform(get("/api/workspaces/supply/performance")
                .param("from", "2026-05-04")
                .param("to", "2026-07-13")
                .param("lens", "TEAM"))
            .andExpect(status().isOk());

        ArgumentCaptor<SupplyWorkspaceQuery> query = ArgumentCaptor.forClass(SupplyWorkspaceQuery.class);
        verify(performanceService).performance(query.capture());
        assertThat(query.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(query.getValue().authorizedCompanyIds()).containsExactly(COMPANY_ID);
        assertThat(query.getValue().from()).isEqualTo("2026-05-04");
        assertThat(query.getValue().to()).isEqualTo("2026-07-13");
    }

    @Test
    void commitmentContextUsesTheOpaqueKeyAndAuthenticatedScope() throws Exception {
        mvc.perform(get("/api/workspaces/supply/commitments/{key}/context", encodedKey()))
            .andExpect(status().isOk());

        verify(contextService).context(
            org.mockito.ArgumentMatchers.eq(encodedKey()),
            org.mockito.ArgumentMatchers.argThat(scope ->
                scope.tenantId().equals("tenant-a") && scope.companyIds().contains(COMPANY_ID)));
    }

    @Test
    void invalidCurrencyUsesStableSupplyErrorCode() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .param("currencyCode", "US"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_CURRENCY_INVALID"));

        verifyNoInteractions(workspaceService);
    }

    @Test
    void malformedDateUsesStableSupplyErrorCode() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .param("from", "2026-13-40")
                .param("to", "2026-08-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_DATE_INVALID"));
    }

    @Test
    void explicitCompanyCanOnlyNarrowAuthenticatedScope() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .param("companyId", UUID.randomUUID().toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_COMPANY_FORBIDDEN"));

        verifyNoInteractions(workspaceService);
    }

    @Test
    void invalidPageUsesStableSupplyErrorCode() throws Exception {
        mvc.perform(get("/api/workspaces/supply/commitments")
                .param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_PAGE_INVALID"));
    }

    @Test
    void malformedCommitmentKeyIsRejectedBeforeAssignmentService() throws Exception {
        mvc.perform(post("/api/workspaces/supply/commitments/not%20canonical/assign")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerUserId\":\"20000000-0000-0000-0000-000000000001\","
                    + "\"reason\":\"ownership transfer\",\"expectedVersion\":4}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_COMMITMENT_KEY_INVALID"));

        verifyNoInteractions(assignmentService);
    }

    @Test
    void assignBindsServerOwnedOwnerReasonAndOptimisticVersion() throws Exception {
        AssignmentResponse response = assignmentResponse();
        when(assignmentService.assign(any(), any(), any())).thenReturn(response);

        mvc.perform(post("/api/workspaces/supply/commitments/{key}/assign", encodedKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerUserId\":\"20000000-0000-0000-0000-000000000001\","
                    + "\"ownerUsername\":\"client-spoof\",\"ownerRole\":\"ADMIN\","
                    + "\"dueDate\":\"2026-07-20\",\"severity\":\"BLOCKING\","
                    + "\"reason\":\"supplier escalation\",\"expectedVersion\":4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commitmentKey").value("PO:30000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.data.owner.role").value("PROCUREMENT_SPECIALIST"))
            .andExpect(jsonPath("$.data.version").value(5));

        ArgumentCaptor<AssignmentRequest> request = ArgumentCaptor.forClass(AssignmentRequest.class);
        verify(assignmentService).assign(any(), any(), request.capture());
        assertThat(request.getValue().ownerUserId()).isEqualTo(
            UUID.fromString("20000000-0000-0000-0000-000000000001"));
        assertThat(request.getValue().reason()).isEqualTo("supplier escalation");
        assertThat(request.getValue().expectedVersion()).isEqualTo(4L);
        assertThat(request.getValue().ownerUsername()).isEqualTo("client-spoof");
        assertThat(request.getValue().ownerRole()).isEqualTo("ADMIN");
    }

    @Test
    void claimAndEscalateExposeTheirExactRequestContracts() throws Exception {
        when(assignmentService.claim(any(), any(), any())).thenReturn(assignmentResponse());
        when(assignmentService.escalate(any(), any(), any())).thenReturn(assignmentResponse());

        mvc.perform(post("/api/workspaces/supply/commitments/{key}/claim", encodedKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":\"2026-07-20\",\"reason\":\"taking ownership\","
                    + "\"expectedVersion\":4}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/escalate", encodedKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"severity\":\"BLOCKING\",\"reason\":\"customs blocked\","
                    + "\"expectedVersion\":4}"))
            .andExpect(status().isOk());

        ArgumentCaptor<ClaimRequest> claim = ArgumentCaptor.forClass(ClaimRequest.class);
        ArgumentCaptor<EscalationRequest> escalation = ArgumentCaptor.forClass(EscalationRequest.class);
        verify(assignmentService).claim(any(), any(), claim.capture());
        verify(assignmentService).escalate(any(), any(), escalation.capture());
        assertThat(claim.getValue().reason()).isEqualTo("taking ownership");
        assertThat(claim.getValue().expectedVersion()).isEqualTo(4L);
        assertThat(escalation.getValue().severity()).isEqualTo(SupplySeverity.BLOCKING);
        assertThat(escalation.getValue().expectedVersion()).isEqualTo(4L);
    }

    @Test
    void assignmentConflictAndCrossScopeAbsenceKeepStableStatusAndCodes() throws Exception {
        when(assignmentService.assign(any(), any(), any()))
            .thenThrow(new SupplyAssignmentConflictException(
                "SUPPLY_ASSIGNMENT_CONFLICT", "assignment changed concurrently"));
        when(assignmentService.claim(any(), any(), any()))
            .thenThrow(new NotFoundException(
                "Supply commitment was not found in authenticated scope",
                "supply.workspace.commitment.notFound",
                "PO:30000000-0000-0000-0000-000000000001"));

        mvc.perform(post("/api/workspaces/supply/commitments/{key}/assign", encodedKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerUserId\":\"20000000-0000-0000-0000-000000000001\","
                    + "\"reason\":\"reassign\",\"expectedVersion\":4}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_ASSIGNMENT_CONFLICT"));

        mvc.perform(post("/api/workspaces/supply/commitments/{key}/claim", encodedKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"claim\",\"expectedVersion\":4}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_COMMITMENT_NOT_FOUND"));
    }

    @Test
    void malformedAssignmentJsonUsesStableSupplyRequestCode() throws Exception {
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/assign", encodedKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_REQUEST_INVALID"));
    }

    @Test
    void scopedCompanyAndOwnerSelectorsUseDedicatedReadSafeRoutes() throws Exception {
        when(selectorService.companies(any(), any())).thenReturn(List.of(
            new CompanyOption(COMPANY_ID, "A", "Alpha Plant", "ACTIVE")));
        UUID ownerId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        when(selectorService.owners(any(), any(), any())).thenReturn(List.of(
            new OwnerOption(
                ownerId, COMPANY_ID, "specialist", "Supply Specialist",
                SupplyAssignmentRole.PROCUREMENT_SPECIALIST)));

        mvc.perform(get("/api/workspaces/supply/selectors/companies")
                .param("search", "alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(COMPANY_ID.toString()))
            .andExpect(jsonPath("$.data[0].displayName").value("Alpha Plant"));
        mvc.perform(get("/api/workspaces/supply/selectors/owners")
                .param("companyId", COMPANY_ID.toString())
                .param("search", "supply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(ownerId.toString()))
            .andExpect(jsonPath("$.data[0].role").value("PROCUREMENT_SPECIALIST"));

        verify(selectorService).companies(any(), org.mockito.ArgumentMatchers.eq("alpha"));
        verify(selectorService).owners(
            any(), org.mockito.ArgumentMatchers.eq(COMPANY_ID), org.mockito.ArgumentMatchers.eq("supply"));
    }

    private SupplyOverviewResponse overview(SupplyWorkspaceQuery query) {
        return new SupplyOverviewResponse(
            new com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope(
                query.tenantId(), query.authorizedCompanyIds(), query.authorizedWarehouseIds(), query.warehouseScoped()),
            SupplyWorkspaceQueryService.appliedFilters(query),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            NOW, query.timeZone().getId(), SupplyWorkspaceQueryService.DEFINITION_VERSION);
    }

    private SupplyCommitmentPageResponse commitments(SupplyWorkspaceQuery query, Pageable page) {
        return new SupplyCommitmentPageResponse(
            new com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope(
                query.tenantId(), query.authorizedCompanyIds(), query.authorizedWarehouseIds(), query.warehouseScoped()),
            SupplyWorkspaceQueryService.appliedFilters(query),
            List.of(), 52, page.getPageNumber(), page.getPageSize(), 3,
            List.of(), List.of(), NOW, query.timeZone().getId(), SupplyWorkspaceQueryService.DEFINITION_VERSION);
    }

    private SupplyCalendarResponse calendar(SupplyWorkspaceQuery query) {
        return new SupplyCalendarResponse(
            new com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope(
                query.tenantId(), query.authorizedCompanyIds(), query.authorizedWarehouseIds(), query.warehouseScoped()),
            SupplyWorkspaceQueryService.appliedFilters(query),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            NOW, query.timeZone().getId(), SupplyWorkspaceQueryService.DEFINITION_VERSION);
    }

    private String encodedKey() {
        return new com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec().encode(
            new com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey(
                SupplyCommitmentKind.PO,
                UUID.fromString("30000000-0000-0000-0000-000000000001")));
    }

    private AssignmentResponse assignmentResponse() {
        return new AssignmentResponse(
            UUID.fromString("40000000-0000-0000-0000-000000000001"),
            "PO:30000000-0000-0000-0000-000000000001",
            encodedKey(),
            SupplyCommitmentKind.PO,
            new AssignmentOwner(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "specialist", "Supply Specialist", SupplyAssignmentRole.PROCUREMENT_SPECIALIST),
            LocalDate.parse("2026-07-20"),
            SupplyDueDateSource.EXPLICIT,
            SupplySeverity.BLOCKING,
            SupplyEscalationState.ESCALATED,
            1,
            "supplier escalation",
            5L);
    }
}
