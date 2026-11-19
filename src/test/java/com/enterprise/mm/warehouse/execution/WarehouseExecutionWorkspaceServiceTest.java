package com.enterprise.mm.warehouse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.IssueRequestLineResponse;
import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.IssueRequestSearchRequest;
import com.enterprise.mm.payload.MaterialReservationSearchRequest;
import com.enterprise.mm.payload.MaterialReturnSearchRequest;
import com.enterprise.mm.payload.TransferSearchRequest;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.AssetManagementSettingsRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.IssueRequestService;
import com.enterprise.mm.service.MaterialReservationService;
import com.enterprise.mm.service.MaterialReturnService;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.DocumentType;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueFilter;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueResponse;
import com.enterprise.mm.warehouse.execution.service.WarehouseExecutionWorkspaceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class WarehouseExecutionWorkspaceServiceTest {
    @Mock private IssueRequestService issueRequestService;
    @Mock private MaterialReservationService reservationService;
    @Mock private TransferDocumentService transferService;
    @Mock private MaterialReturnService returnService;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private IntegrationEventRepository integrationEventRepository;
    @Mock private UserRepository userRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DemandPlanItemRepository demandPlanItemRepository;
    @Mock private AssetManagementSettingsRepository assetManagementSettingsRepository;
    @Mock private com.enterprise.mm.service.RoleDataScopeService roleDataScopeService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("warehouse-execution-workspace-test");
        CompanyContext.setCompany(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void queueUsesTheSamePageTotalAsItsDrilldownItems() {
        UUID id = UUID.randomUUID();
        IssueRequestResponse response = issueRequest(id);
        PageRequest pageRequest = PageRequest.of(0, 1, Sort.by("createdAt").descending());
        when(issueRequestService.search(any(), any())).thenReturn(
            new PageImpl<>(List.of(response), pageRequest, 3)
        );

        var queue = service().queue(
            DocumentType.ISSUE_REQUEST,
            new QueueFilter(" bearing ", List.of("reserved"), response.getWarehouseId()),
            pageRequest
        );

        assertThat(queue.totalElements()).isEqualTo(3);
        assertThat(queue.items()).singleElement().satisfies(item -> {
            assertThat(item.documentType()).isEqualTo(DocumentType.ISSUE_REQUEST);
            assertThat(item.document().id()).isEqualTo(id);
            assertThat(item.ownerRole()).isEqualTo("WAREHOUSE_OPERATOR");
            assertThat(item.nextAction()).isEqualTo("ISSUE");
            assertThat(item.primaryQuantity()).isEqualByComparingTo("6");
        });
        assertThat(queue.appliedFilters().search()).isEqualTo("bearing");
        assertThat(queue.appliedFilters().status()).containsExactly("RESERVED");
    }

    @ParameterizedTest
    @MethodSource("activeStatusCohorts")
    void queueReturnsAndDelegatesTheExactDefaultStatusCohort(
        DocumentType type,
        List<String> expectedStatuses
    ) {
        UUID warehouseId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 25);
        stubEmptyQueue(type, pageRequest);
        if (type == DocumentType.TRANSFER) {
            when(roleDataScopeService.effectiveWarehouseId(warehouseId)).thenReturn(warehouseId);
        }

        var queue = service().queue(
            type,
            new QueueFilter("  bearings  ", List.of(), warehouseId),
            pageRequest
        );

        QueueFilter expected = new QueueFilter("bearings", expectedStatuses, warehouseId);
        assertThat(delegatedFilter(type, pageRequest)).isEqualTo(expected);
        assertThat(queue.appliedFilters()).isEqualTo(expected);
    }

    @Test
    void queueKeepsExplicitFiltersNormalizedExactlyInResponseAndDelegatedRequest() {
        UUID warehouseId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 25);
        when(issueRequestService.search(any(), eq(pageRequest))).thenReturn(
            new PageImpl<>(List.of(), pageRequest, 0)
        );

        var queue = service().queue(
            DocumentType.ISSUE_REQUEST,
            new QueueFilter(
                "  bearings  ",
                List.of(" submitted ", "draft", "SUBMITTED", "  "),
                warehouseId
            ),
            pageRequest
        );

        QueueFilter expected = new QueueFilter(
            "bearings",
            List.of("DRAFT", "SUBMITTED"),
            warehouseId
        );
        assertThat(delegatedFilter(DocumentType.ISSUE_REQUEST, pageRequest)).isEqualTo(expected);
        assertThat(queue.appliedFilters()).isEqualTo(expected);
    }

    @Test
    void overviewCopiesCountAndAppliedFiltersFromEachSingleQueueResponse() {
        PageRequest pageRequest = PageRequest.of(0, 1, Sort.by("createdAt").descending());
        QueueFilter emptyFilter = new QueueFilter(null, List.of(), null);
        QueueFilter transferFilter = new QueueFilter(null, List.of(), null, "tasks");
        Map<DocumentType, QueueResponse> responses = Map.of(
            DocumentType.ISSUE_REQUEST,
            queueResponse(DocumentType.ISSUE_REQUEST, 11, "ISSUE_ONLY"),
            DocumentType.RESERVATION,
            queueResponse(DocumentType.RESERVATION, 22, "RESERVATION_ONLY"),
            DocumentType.TRANSFER,
            queueResponse(DocumentType.TRANSFER, 33, "TRANSFER_ONLY"),
            DocumentType.RETURN,
            queueResponse(DocumentType.RETURN, 44, "RETURN_ONLY")
        );
        WarehouseExecutionWorkspaceService service = spy(service());
        responses.forEach((type, response) ->
            doReturn(response).when(service).queue(type, type == DocumentType.TRANSFER ? transferFilter : emptyFilter, pageRequest)
        );

        var overview = service.overview();

        assertThat(overview.warehouseId()).isNull();
        assertThat(overview.canReceiveFromSupplier()).isFalse();
        assertThat(overview.canReceiveFromWarehouse()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(assetManagementSettingsRepository);
        responses.forEach((type, response) -> {
            var summary = overview.queues().stream()
                .filter(candidate -> candidate.documentType() == type)
                .findFirst()
                .orElseThrow();
            assertThat(summary.count()).isEqualTo(response.totalElements());
            assertThat(summary.appliedFilters()).isSameAs(response.appliedFilters());
            verify(service, times(1)).queue(type, type == DocumentType.TRANSFER ? transferFilter : emptyFilter, pageRequest);
        });
    }

    @ParameterizedTest
    @MethodSource("overviewReceiptCapabilities")
    void overviewReportsReceiptCapabilitiesForTheEffectiveWarehouse(
        boolean settingsExist, boolean centralConfigured, boolean centralSelected, boolean supplierAllowed
    ) {
        UUID warehouseId = UUID.randomUUID();
        when(roleDataScopeService.effectiveWarehouseId(warehouseId)).thenReturn(warehouseId);
        AssetManagementSettings settings = new AssetManagementSettings();
        settings.setCentralWarehouseId(centralConfigured
            ? (centralSelected ? warehouseId : UUID.randomUUID()) : null);
        when(assetManagementSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany()
        )).thenReturn(settingsExist ? Optional.of(settings) : Optional.empty());

        var overview = overviewService(warehouseId).overview(warehouseId);

        assertThat(overview.warehouseId()).isEqualTo(warehouseId);
        assertThat(overview.canReceiveFromSupplier()).isEqualTo(supplierAllowed);
        assertThat(overview.canReceiveFromWarehouse()).isTrue();
        verify(roleDataScopeService).assertWarehouseAccess(warehouseId);
        verify(assetManagementSettingsRepository).findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    private static Stream<Arguments> overviewReceiptCapabilities() {
        return Stream.of(
            Arguments.of(true, true, true, true),
            Arguments.of(true, true, false, false),
            Arguments.of(false, false, false, false),
            Arguments.of(true, false, false, false)
        );
    }

    @Test
    void overviewUsesAssignedWarehouseWhenRequestDoesNotSpecifyOne() {
        UUID warehouseId = UUID.randomUUID();
        when(roleDataScopeService.effectiveWarehouseId(null)).thenReturn(warehouseId);
        AssetManagementSettings settings = new AssetManagementSettings();
        settings.setCentralWarehouseId(warehouseId);
        when(assetManagementSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany()
        )).thenReturn(Optional.of(settings));

        var overview = overviewService(warehouseId).overview();

        assertThat(overview.warehouseId()).isEqualTo(warehouseId);
        assertThat(overview.canReceiveFromSupplier()).isTrue();
        assertThat(overview.canReceiveFromWarehouse()).isTrue();
        verify(roleDataScopeService).assertWarehouseAccess(warehouseId);
    }

    @Test
    void overviewRejectsInaccessibleWarehouseBeforeReadingSettingsOrQueues() {
        UUID requestedWarehouseId = UUID.randomUUID();
        UUID deniedWarehouseId = UUID.randomUUID();
        when(roleDataScopeService.effectiveWarehouseId(requestedWarehouseId)).thenReturn(deniedWarehouseId);
        org.mockito.Mockito.doThrow(new com.enterprise.mm.error.NotFoundException("Record is outside your assigned warehouse"))
            .when(roleDataScopeService).assertWarehouseAccess(deniedWarehouseId);

        assertThatThrownBy(() -> service().overview(requestedWarehouseId))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(
            assetManagementSettingsRepository, issueRequestService, reservationService, transferService, returnService);
    }

    private WarehouseExecutionWorkspaceService overviewService(UUID warehouseId) {
        WarehouseExecutionWorkspaceService service = spy(service());
        PageRequest pageRequest = PageRequest.of(0, 1, Sort.by("createdAt").descending());
        QueueFilter filter = new QueueFilter(null, List.of(), warehouseId);
        for (DocumentType type : DocumentType.values()) {
            QueueFilter appliedFilter = type == DocumentType.TRANSFER
                ? new QueueFilter(null, List.of(), warehouseId, "tasks") : filter;
            doReturn(new QueueResponse(type, appliedFilter, 0, List.of(), 0, 1, 0, LocalDateTime.now(), "TEST"))
                .when(service).queue(type, appliedFilter, pageRequest);
        }
        return service;
    }

    @Test
    void contextCarriesBackendActionsAuditAndIntegrationImpact() {
        UUID id = UUID.randomUUID();
        IssueRequestResponse response = issueRequest(id);
        response.setStockMovementDocumentId(UUID.randomUUID());
        when(issueRequestService.get(id)).thenReturn(response);

        AuditLog audit = new AuditLog();
        audit.setId(UUID.randomUUID());
        audit.setAction("RESERVE");
        audit.setUsername("keeper");
        audit.setDetails("Reservation created");
        audit.setOccurredAt(LocalDateTime.of(2026, 7, 15, 9, 30));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            any(), any(), any(), any()
        )).thenReturn(List.of(audit));

        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setEventType("MM_MATERIAL_RESERVED");
        event.setStatus("NEW");
        event.setCreatedAt(LocalDateTime.of(2026, 7, 15, 9, 31));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            any(), any(), any()
        )).thenReturn(List.of(event));

        var context = service().issueRequestContext(id);

        assertThat(context.document()).isSameAs(response);
        assertThat(context.allowedActions()).extracting(action -> action.code())
            .containsExactly("ISSUE", "CANCEL");
        assertThat(context.auditTimeline()).singleElement().satisfies(item ->
            assertThat(item.action()).isEqualTo("RESERVE")
        );
        assertThat(context.integrationImpact()).singleElement().satisfies(item ->
            assertThat(item.eventType()).isEqualTo("MM_MATERIAL_RESERVED")
        );
        assertThat(context.relatedDocuments()).extracting(item -> item.documentType())
            .contains("STOCK_MOVEMENT");
    }

    @Test
    void userSelectorUsesStrictTenantAndCompanyScopeAndSafeDisplayFields() {
        UUID companyId = CompanyContext.currentCompany();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setCode("USR-17");
        user.setName("Warehouse keeper account");
        user.setFullName("Aziza Karimova");
        user.setEnabled(true);
        PageRequest pageRequest = PageRequest.of(0, 25);
        when(userRepository.searchActiveCompanyUsers(
            eq(TenantContext.currentTenant()), eq(companyId), eq("Aziza"), eq(pageRequest)
        )).thenReturn(new PageImpl<>(List.of(user), pageRequest, 1));

        var result = service().userSelector("  Aziza  ", pageRequest);

        assertThat(result).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo(user.getId());
            assertThat(option.code()).isEqualTo("USR-17");
            assertThat(option.displayName()).isEqualTo("Aziza Karimova");
            assertThat(option.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void costObjectSelectorRejectsUnsupportedTypes() {
        assertThatThrownBy(() -> service().costObjectSelector("vendor", null, PageRequest.of(0, 25)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Unsupported cost object type");
    }

    private WarehouseExecutionWorkspaceService service() {
        return new WarehouseExecutionWorkspaceService(
            issueRequestService,
            reservationService,
            transferService,
            returnService,
            auditLogRepository,
            integrationEventRepository,
            userRepository,
            departmentRepository,
            roleDataScopeService,
            demandPlanItemRepository,
            assetManagementSettingsRepository
        );
    }

    private void stubEmptyQueue(DocumentType type, PageRequest pageRequest) {
        switch (type) {
            case ISSUE_REQUEST -> when(issueRequestService.search(any(), eq(pageRequest))).thenReturn(
                new PageImpl<>(List.of(), pageRequest, 0)
            );
            case RESERVATION -> when(reservationService.search(any(), eq(pageRequest))).thenReturn(
                new PageImpl<>(List.of(), pageRequest, 0)
            );
            case TRANSFER -> when(transferService.search(any(), eq(pageRequest))).thenReturn(
                new PageImpl<>(List.of(), pageRequest, 0)
            );
            case RETURN -> when(returnService.search(any(), eq(pageRequest))).thenReturn(
                new PageImpl<>(List.of(), pageRequest, 0)
            );
        }
    }

    private QueueFilter delegatedFilter(DocumentType type, PageRequest pageRequest) {
        return switch (type) {
            case ISSUE_REQUEST -> {
                ArgumentCaptor<IssueRequestSearchRequest> request =
                    ArgumentCaptor.forClass(IssueRequestSearchRequest.class);
                org.mockito.Mockito.verify(issueRequestService).search(request.capture(), eq(pageRequest));
                yield new QueueFilter(
                    request.getValue().search(),
                    request.getValue().status(),
                    request.getValue().warehouseId()
                );
            }
            case RESERVATION -> {
                ArgumentCaptor<MaterialReservationSearchRequest> request =
                    ArgumentCaptor.forClass(MaterialReservationSearchRequest.class);
                org.mockito.Mockito.verify(reservationService).search(request.capture(), eq(pageRequest));
                yield new QueueFilter(
                    request.getValue().search(),
                    request.getValue().status(),
                    request.getValue().warehouseId()
                );
            }
            case TRANSFER -> {
                ArgumentCaptor<TransferSearchRequest> request =
                    ArgumentCaptor.forClass(TransferSearchRequest.class);
                org.mockito.Mockito.verify(transferService).search(request.capture(), eq(pageRequest));
                yield new QueueFilter(
                    request.getValue().search(),
                    request.getValue().status(),
                    request.getValue().warehouseId()
                );
            }
            case RETURN -> {
                ArgumentCaptor<MaterialReturnSearchRequest> request =
                    ArgumentCaptor.forClass(MaterialReturnSearchRequest.class);
                org.mockito.Mockito.verify(returnService).search(request.capture(), eq(pageRequest));
                yield new QueueFilter(
                    request.getValue().search(),
                    request.getValue().status(),
                    request.getValue().warehouseId()
                );
            }
        };
    }

    private QueueResponse queueResponse(DocumentType type, long totalElements, String status) {
        return new QueueResponse(
            type,
            new QueueFilter(type.path(), List.of(status), UUID.randomUUID()),
            totalElements,
            List.of(),
            0,
            1,
            1,
            LocalDateTime.now(),
            "COMPANY:" + CompanyContext.currentCompany()
        );
    }

    private static Stream<Arguments> activeStatusCohorts() {
        return Stream.of(
            Arguments.of(
                DocumentType.ISSUE_REQUEST,
                List.of("DRAFT", "SUBMITTED", "APPROVED", "RESERVED", "PARTIALLY_ISSUED", "ISSUED")
            ),
            Arguments.of(DocumentType.RESERVATION, List.of("ACTIVE", "PARTIALLY_RELEASED")),
            Arguments.of(
                DocumentType.TRANSFER,
                List.of("DRAFT", "SUBMITTED", "APPROVED", "IN_TRANSIT", "DISCREPANCY_REVIEW", "RECEIVED")
            ),
            Arguments.of(DocumentType.RETURN, List.of("DRAFT", "SUBMITTED", "INSPECTION"))
        );
    }

    private IssueRequestResponse issueRequest(UUID id) {
        IssueRequestResponse response = new IssueRequestResponse();
        response.setId(id);
        response.setCompanyId(UUID.randomUUID());
        response.setCode("IR-1007");
        response.setName("Bearing issue");
        response.setStatus("RESERVED");
        response.setWarehouseId(UUID.randomUUID());
        response.setWarehouseName("Central warehouse");
        response.setRequiredDate(LocalDate.of(2026, 7, 16));
        response.setVersion(4L);
        response.setAllowedActions(List.of("issue", "cancel"));
        IssueRequestLineResponse line = new IssueRequestLineResponse();
        line.setApprovedQuantity(BigDecimal.TEN);
        line.setIssuedQuantity(BigDecimal.valueOf(4));
        line.setRemainingQuantity(BigDecimal.valueOf(6));
        response.setLines(List.of(line));
        return response;
    }
}
