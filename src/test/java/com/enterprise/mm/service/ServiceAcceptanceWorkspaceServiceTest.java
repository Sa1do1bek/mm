package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceRequestMilestone;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.mapper.ServiceAcceptanceActMapper;
import com.enterprise.mm.mapper.ServiceClaimMapper;
import com.enterprise.mm.mapper.ServiceExecutionMapper;
import com.enterprise.mm.mapper.ServiceOrderMapper;
import com.enterprise.mm.mapper.ServiceRequestMapper;
import com.enterprise.mm.mapper.ServiceReviewHistoryMapper;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.CaseFilter;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.CaseRow;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.ManagementBucket;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.OriginType;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.WorkActRow;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRemarkRepository;
import com.enterprise.mm.repository.ServiceRequestMilestoneRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.ServiceReviewHistoryRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ServiceAcceptanceWorkspaceServiceTest {
    @org.mockito.Mock com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private ServiceRequestMilestoneRepository milestoneRepository;
    @Mock private ServiceOrderRepository orderRepository;
    @Mock private ServiceExecutionRepository executionRepository;
    @Mock private ServiceAcceptanceActRepository actRepository;
    @Mock private ServiceCaseWorkspaceQueryRepository caseQueryRepository;
    @Mock private ServiceClaimRepository claimRepository;
    @Mock private ServiceRequestRemarkRepository remarkRepository;
    @Mock private ServiceReviewHistoryRepository reviewRepository;
    @Mock private SupplierPortalAccountRepository supplierPortalAccountRepository;
    @Mock private IntegrationEventRepository integrationEventRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock private ServiceRequestMapper requestMapper;
    @Mock private ServiceOrderMapper orderMapper;
    @Mock private ServiceExecutionMapper executionMapper;
    @Mock private ServiceAcceptanceActMapper actMapper;
    @Mock private ServiceClaimMapper claimMapper;
    @Mock private ServiceReviewHistoryMapper reviewMapper;
    @Mock private AttachmentService attachmentService;
    @Mock private AttachmentEntityAccessPolicy attachmentEntityAccessPolicy;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private ServiceWorkflowAccessService accessService;

    @InjectMocks private ServiceAcceptanceWorkspaceService service;

    private final UUID company = CompanyContext.DEFAULT_COMPANY_ID;
    private final UUID requestId = UUID.randomUUID();
    private final UUID actId = UUID.randomUUID();

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(company);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void canonicalCasesDelegateTheWholeFilterAndRealPageableToTheSharedQuery() {
        UUID orderId = UUID.randomUUID();
        CaseFilter filter = CaseFilter.all()
            .withQueue("PAYMENT")
            .withOriginType(OriginType.PO_SERVICE)
            .withOwnerId(UUID.randomUUID())
            .withSupplierId(UUID.randomUUID())
            .withStatuses(List.of("APPROVED"))
            .withOverdue(false)
            .withSearch("service order");
        PageRequest pageable = PageRequest.of(3, 25, Sort.by(Sort.Direction.ASC, "dueDate"));
        CaseRow row = canonicalRow(orderId, OriginType.PO_SERVICE, null, orderId, "SO-100");
        when(caseQueryRepository.findCases(eq(filter), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of(row), pageable, 76));

        var page = service.cases(filter, pageable);

        verify(caseQueryRepository).findCases(filter, pageable);
        assertThat(page.getTotalElements()).isEqualTo(76);
        assertThat(page.getContent()).singleElement().satisfies(item -> {
            assertThat(item.caseId()).isEqualTo(orderId);
            assertThat(item.originType()).isEqualTo("PO_SERVICE");
            assertThat(item.originId()).isEqualTo(orderId);
            assertThat(item.requestId()).isNull();
            assertThat(item.serviceOrderId()).isEqualTo(orderId);
            assertThat(item.acceptanceMode()).isEqualTo("AMOUNT");
            assertThat(item.orderedAmount()).isEqualByComparingTo("500");
            assertThat(item.remainingAmount()).isEqualByComparingTo("250");
        });
    }

    @Test
    void canonicalQueueCountsUseTheSameFilterObjectAsTheirDrilldowns() {
        CaseFilter baseFilter = CaseFilter.all()
            .withOriginType(OriginType.PO_WORKS)
            .withSupplierId(UUID.randomUUID())
            .withSearch("pump overhaul");
        when(caseQueryRepository.count(any(CaseFilter.class))).thenReturn(1L);
        when(caseQueryRepository.findCases(any(CaseFilter.class), any(PageRequest.class)))
            .thenAnswer(invocation -> {
                CaseFilter filter = invocation.getArgument(0);
                PageRequest page = invocation.getArgument(1);
                return new PageImpl<>(
                    List.of(canonicalRow(UUID.randomUUID(), OriginType.PO_WORKS, null, null, filter.queue())),
                    page,
                    1
                );
            });

        var queues = service.queues(baseFilter);

        assertThat(queues).extracting("key")
            .containsExactly("EXECUTION", "ACCEPTANCE", "REWORK", "PAYMENT", "INTEGRATION", "COMPLETED");
        assertThat(queues).allSatisfy(queue -> {
            CaseFilter drilldown = baseFilter.withQueue(queue.key());
            assertThat(service.cases(drilldown, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(queue.count());
            assertThat(queue.appliedFilters()).containsEntry("queue", queue.key());
            assertThat(queue.appliedFilters()).containsEntry("originType", "PO_WORKS");
        });
    }

    @Test
    void summaryMetricsCarryTheExactFilterUsedForEveryCountAndDrilldown() {
        when(caseQueryRepository.count(any(CaseFilter.class))).thenAnswer(invocation -> {
            CaseFilter filter = invocation.getArgument(0);
            if (filter.originType() == OriginType.SERVICE_REQUEST) return 11L;
            if (Boolean.TRUE.equals(filter.overdue())) return 6L;
            return switch (filter.queue()) {
                case "EXECUTION" -> 12L;
                case "ACCEPTANCE" -> 8L;
                case "REWORK" -> 4L;
                case "PAYMENT" -> 3L;
                case "INTEGRATION" -> 2L;
                default -> 0L;
            };
        });

        var summary = service.summary(CaseFilter.all());

        assertThat(summary.metrics()).hasSize(7).allSatisfy(metric -> assertThat(metric.available()).isTrue());
        assertThat(summary.metrics()).filteredOn(metric -> metric.key().equals("activeRequests")).singleElement()
            .satisfies(metric -> {
                assertThat(metric.value()).isEqualByComparingTo("11");
                assertThat(metric.appliedFilters()).containsEntry("queue", "ALL").containsEntry("originType", "SERVICE_REQUEST");
            });
        assertThat(summary.metrics()).filteredOn(metric -> metric.key().equals("overdue")).singleElement()
            .satisfies(metric -> {
                assertThat(metric.value()).isEqualByComparingTo("6");
                assertThat(metric.appliedFilters()).containsEntry("queue", "EXECUTION").containsEntry("overdue", "true");
            });
        assertThat(summary.activeExecutions()).isEqualTo(12L);
        assertThat(summary.integrationIssues()).isEqualTo(2L);
    }

    @Test
    void managementUsesDatabaseBucketsAndNeverSumsMixedCurrencies() {
        UUID supplierId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        when(caseQueryRepository.count(any(CaseFilter.class))).thenReturn(2L);
        when(caseQueryRepository.managementBuckets(any(CaseFilter.class))).thenReturn(List.of(
            new ManagementBucket("LIFECYCLE", "PAYMENT", "Payment", 2L, BigDecimal.valueOf(900), "UZS", 1L),
            new ManagementBucket("BACKLOG", "OVERDUE_1_30", "Overdue 1–30 days", 2L, null, null, 0L),
            new ManagementBucket("EXPOSURE", "OUTSTANDING", "Outstanding acceptance value", 4L,
                BigDecimal.valueOf(1500), null, 2L),
            new ManagementBucket("SUPPLIER", supplierId.toString(), "Single-currency supplier", 2L,
                BigDecimal.valueOf(700), "UZS", 1L),
            new ManagementBucket("DEPARTMENT", departmentId.toString(), "Maintenance", 2L,
                BigDecimal.valueOf(600), "UZS", 1L),
            new ManagementBucket("CONTRACT", contractId.toString(), "CTR-1", 1L,
                BigDecimal.valueOf(500), "UZS", 1L),
            new ManagementBucket("CATEGORY", "MAINTENANCE", "MAINTENANCE", 1L,
                BigDecimal.valueOf(450), "UZS", 1L),
            new ManagementBucket("PAYMENT", "DUE_0_7", "Due in 0–7 days", 1L,
                BigDecimal.valueOf(300), "UZS", 1L),
            new ManagementBucket("INTEGRATION_TREND", "2026-07-15", "2026-07-15", 3L,
                null, null, 0L),
            new ManagementBucket("QUALITY_METRIC", "ON_TIME_RATE", "On-time completion rate", 5L,
                BigDecimal.valueOf(80), null, 0L),
            new ManagementBucket("QUALITY_METRIC", "REWORK_RATE", "Rework rate", 10L,
                BigDecimal.valueOf(20), null, 0L)
        ));

        var dashboard = service.management(CaseFilter.all());

        assertThat(dashboard.metrics()).hasSize(9);
        assertThat(dashboard.lifecycleWaterfall()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualByComparingTo("900");
            assertThat(item.appliedFilters()).containsEntry("queue", "PAYMENT").containsEntry("currencyCode", "UZS");
        });
        assertThat(dashboard.exposureBreakdown()).singleElement().satisfies(item -> {
            assertThat(item.available()).isFalse();
            assertThat(item.value()).isNull();
            assertThat(item.unavailableReason()).contains("multiple currencies");
            assertThat(item.appliedFilters()).containsEntry("exposureMetric", "OUTSTANDING");
        });
        assertThat(dashboard.supplierExposure()).singleElement().satisfies(item -> {
            assertThat(item.available()).isTrue();
            assertThat(item.value()).isEqualByComparingTo("700");
            assertThat(item.currency()).isEqualTo("UZS");
            assertThat(item.appliedFilters()).containsEntry("supplierId", supplierId.toString());
        });
        assertThat(dashboard.paymentForecast()).singleElement().satisfies(item -> {
            assertThat(item.available()).isTrue();
            assertThat(item.appliedFilters()).containsEntry("queue", "PAYMENT");
            assertThat(item.appliedFilters()).containsEntry("agingBucket", "DUE_0_7");
        });
        assertThat(dashboard.integrationTrend()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualByComparingTo("3");
            assertThat(item.appliedFilters()).containsEntry("queue", "INTEGRATION")
                .containsEntry("integrationDay", "2026-07-15");
        });
        assertThat(dashboard.departmentExposure()).singleElement().satisfies(item ->
            assertThat(item.appliedFilters()).containsEntry("departmentId", departmentId.toString()));
        assertThat(dashboard.contractExposure()).singleElement().satisfies(item ->
            assertThat(item.appliedFilters()).containsEntry("contractId", contractId.toString()));
        assertThat(dashboard.categoryExposure()).singleElement().satisfies(item -> {
            assertThat(item.available()).isTrue();
            assertThat(item.value()).isEqualByComparingTo("450");
            assertThat(item.appliedFilters()).containsEntry("category", "MAINTENANCE");
        });
        assertThat(dashboard.metrics()).filteredOn(metric -> metric.key().equals("REWORK_RATE")).singleElement()
            .satisfies(metric -> assertThat(metric.appliedFilters()).containsEntry("hasRework", "true"));
        verify(caseQueryRepository).managementBuckets(any(CaseFilter.class));
    }

    @Test
    void supplierManagerAllScopeIsAlwaysRestrictedToLinkedPortalSupplier() {
        String login = "portal@supplier.test";
        UUID linkedSupplierId = UUID.randomUUID();
        authenticate(login, "SUPPLIER_MANAGER");
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setSupplierId(linkedSupplierId);
        account.setStatus("ACTIVE");
        when(supplierPortalAccountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            "default", company, login
        )).thenReturn(Optional.of(account));
        when(caseQueryRepository.findCases(any(CaseFilter.class), any(PageRequest.class)))
            .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        service.cases(
            CaseFilter.all()
                .withScope(com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.MY, null, null)
                .withSupplierId(UUID.randomUUID()),
            PageRequest.of(0, 20)
        );

        var captor = org.mockito.ArgumentCaptor.forClass(CaseFilter.class);
        verify(caseQueryRepository).findCases(captor.capture(), any(PageRequest.class));
        assertThat(captor.getValue().supplierId()).isEqualTo(linkedSupplierId);
        assertThat(captor.getValue().scope()).isEqualTo(com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.ALL);
    }

    @Test
    void inactiveSupplierPortalAccountFailsClosedForEveryRequestedScope() {
        String login = "suspended@supplier.test";
        authenticate(login, "SUPPLIER_MANAGER");
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setSupplierId(UUID.randomUUID());
        account.setStatus("SUSPENDED");
        when(supplierPortalAccountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            "default", company, login
        )).thenReturn(Optional.of(account));
        when(caseQueryRepository.findCases(any(CaseFilter.class), any(PageRequest.class)))
            .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        service.cases(CaseFilter.all(), PageRequest.of(0, 20));

        var captor = org.mockito.ArgumentCaptor.forClass(CaseFilter.class);
        verify(caseQueryRepository).findCases(captor.capture(), any(PageRequest.class));
        assertThat(captor.getValue().supplierId()).isEqualTo(new UUID(0L, 0L));
        assertThat(captor.getValue().scope()).isEqualTo(com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.ALL);
    }

    @Test
    void readOnlyViewerKeepsRequestedCompanyWideScope() {
        authenticate("executive-viewer", "VIEWER");
        when(caseQueryRepository.findCases(any(CaseFilter.class), any(PageRequest.class)))
            .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        service.cases(CaseFilter.all(), PageRequest.of(0, 20));

        var captor = org.mockito.ArgumentCaptor.forClass(CaseFilter.class);
        verify(caseQueryRepository).findCases(captor.capture(), any(PageRequest.class));
        assertThat(captor.getValue().scope()).isEqualTo(com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.ALL);
        assertThat(captor.getValue().supplierId()).isNull();
    }

    @Test
    void forcedMyScopeFailsClosedWhenNoUserExistsInCurrentCompany() {
        authenticate("same.username", "DEPARTMENT_WORKER");
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse("default", company, "same.username"))
            .thenReturn(Optional.empty());
        when(caseQueryRepository.findCases(any(CaseFilter.class), any(PageRequest.class)))
            .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        service.cases(CaseFilter.all(), PageRequest.of(0, 20));

        var captor = org.mockito.ArgumentCaptor.forClass(CaseFilter.class);
        verify(caseQueryRepository).findCases(captor.capture(), any(PageRequest.class));
        assertThat(captor.getValue().scope())
            .isEqualTo(com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.MY);
        assertThat(captor.getValue().currentUserId()).isNull();
        verify(userRepository).findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse("default", company, "same.username");
    }

    @Test
    void intakeOptionsAreHumanReadableCompanyScopedAndRestrictedForDepartmentWorker() {
        Department ownDepartment = department();
        Department otherDepartment = department();
        otherDepartment.setName("Other department");
        User worker = user("worker.options", ownDepartment);
        User otherOwner = user("other.owner", otherDepartment);
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setCode("SUP-1");
        supplier.setLegalName("Industrial Services LLC");
        supplier.setStatus("ACTIVE");
        Currency currency = new Currency();
        currency.setIsoCode("UZS");
        currency.setName("Uzbek sum");
        currency.setStatus("ACTIVE");
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setId(UUID.randomUUID());
        unit.setCode("HOUR");
        unit.setName("Hour");
        unit.setMeasurementNameShort("h");
        unit.setStatus("ACTIVE");
        authenticate("worker.options", "DEPARTMENT_WORKER");
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse("default", company, "worker.options"))
            .thenReturn(Optional.of(worker));
        when(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(eq("default"), eq(company), any(Pageable.class)))
            .thenReturn(List.of(supplier));
        when(departmentRepository.search(eq("default"), eq(company), eq(false), any(), any(), any(), anyList(), eq(false), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(ownDepartment, otherDepartment)));
        when(userRepository.searchActiveCompanyUsers(eq("default"), eq(company), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(worker, otherOwner)));
        when(currencyRepository.search(eq("default"), eq(company), eq(false), any(), any(), any(), anyList(), eq(false), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(currency)));
        when(unitOfMeasureRepository.search(
            eq("default"), eq(company), eq(false), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(unit)));

        var options = service.intakeOptions();

        assertThat(options.departments()).extracting("value").containsExactly(ownDepartment.getId().toString());
        assertThat(options.owners()).extracting("value").containsExactly(worker.getId().toString());
        assertThat(options.suppliers()).singleElement().satisfies(option -> assertThat(option.label()).contains("Industrial Services"));
        assertThat(options.currencies()).extracting("value").containsExactly("UZS");
        assertThat(options.units()).extracting("value").containsExactly("HOUR");
        assertThat(options.approvalPreview()).containsExactly("DEPARTMENT_HEAD", "PROCUREMENT_MANAGER");
    }

    @Test
    void intakeOptionsUseDepartmentFromLinkedEmployeeWhenUserDepartmentIsEmpty() {
        Department ownDepartment = department();
        Department otherDepartment = department();
        otherDepartment.setName("Other department");
        User head = user("head.options", null);
        head.setEmployee(employee(ownDepartment));
        User ownerViaEmployee = user("employee.owner", null);
        ownerViaEmployee.setEmployee(employee(ownDepartment));
        User otherOwner = user("other.owner", otherDepartment);
        authenticate("head.options", "DEPARTMENT_HEAD");
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse("default", company, "head.options"))
            .thenReturn(Optional.of(head));
        when(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(eq("default"), eq(company), any(Pageable.class)))
            .thenReturn(List.of());
        when(departmentRepository.search(eq("default"), eq(company), eq(false), any(), any(), any(), anyList(), eq(false), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(ownDepartment, otherDepartment)));
        when(userRepository.searchActiveCompanyUsers(eq("default"), eq(company), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(ownerViaEmployee, otherOwner)));
        when(currencyRepository.search(eq("default"), eq(company), eq(false), any(), any(), any(), anyList(), eq(false), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(unitOfMeasureRepository.search(
            eq("default"), eq(company), eq(false), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        var options = service.intakeOptions();

        assertThat(options.departments()).extracting("value").containsExactly(ownDepartment.getId().toString());
        assertThat(options.owners()).extracting("value").containsExactly(ownerViaEmployee.getId().toString());
        assertThat(options.owners()).singleElement().satisfies(option -> assertThat(option.description()).isEqualTo("Maintenance"));
    }

    @Test
    void supplierManagerCannotOpenUnlinkedPoOriginByGuessingItsCaseId() {
        String login = "portal@supplier.test";
        UUID linkedSupplierId = UUID.randomUUID();
        UUID guessedCaseId = UUID.randomUUID();
        authenticate(login, "SUPPLIER_MANAGER");
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setSupplierId(linkedSupplierId);
        account.setStatus("ACTIVE");
        when(supplierPortalAccountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            "default", company, login
        )).thenReturn(Optional.of(account));
        when(caseQueryRepository.findCase(eq(guessedCaseId), any(CaseFilter.class))).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.workspace(guessedCaseId))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);

        var captor = org.mockito.ArgumentCaptor.forClass(CaseFilter.class);
        verify(caseQueryRepository).findCase(eq(guessedCaseId), captor.capture());
        assertThat(captor.getValue().supplierId()).isEqualTo(linkedSupplierId);
    }

    @Test
    void departmentWorkerCanSubmitOwnDraftRequest() {
        Department department = department();
        User worker = user("worker", department);
        ServiceRequest request = draftRequest(worker.getId(), department.getId(), "worker");
        authenticate("worker", "DEPARTMENT_WORKER");
        stubDraftWorkspace(request, worker);

        var workspace = service.workspace(request.getId());

        assertThat(workspace.allowedActions()).extracting("code").containsExactly("SUBMIT_REQUEST");
        assertThat(workspace.allowedActions()).singleElement().satisfies(action -> {
            assertThat(action.idempotencyRequired()).isTrue();
            assertThat(action.targetId()).isEqualTo(request.getId());
        });
    }

    @Test
    void departmentHeadCanSubmitTeamDraftRequestWithoutSeeingProcurementOnlyActions() {
        Department department = department();
        User head = user("department.head", department);
        ServiceRequest request = draftRequest(UUID.randomUUID(), department.getId(), "team.worker");
        authenticate("department.head", "DEPARTMENT_HEAD");
        stubDraftWorkspace(request, head);
        when(attachmentEntityAccessPolicy.canUpload(
            "SERVICE_REQUEST", request.getId(), "SCOPE_OF_WORK"
        )).thenReturn(true);

        var workspace = service.workspace(request.getId());

        assertThat(workspace.allowedActions()).extracting("code").containsExactly("SUBMIT_REQUEST");
        assertThat(workspace.allowedActions()).extracting("code")
            .doesNotContain("PARTIAL_ACCEPT", "ADD_REMARKS", "CREATE_CLAIM");
        assertThat(workspace.evidenceTarget().canUpload()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "SENT_TO_PAYMENT", "PAID"})
    void closeRequestActionIsNotAdvertisedWithoutCommandReadiness(String status) {
        Department department = department();
        User manager = user("request.manager", department);
        ServiceRequest request = draftRequest(manager.getId(), department.getId(), "request.manager");
        request.setStatus(status);
        authenticate("request.manager", "PROCUREMENT_MANAGER");
        stubDraftWorkspace(request, manager);

        var workspace = service.workspace(request.getId());

        assertThat(workspace.allowedActions()).extracting("code").doesNotContain("CLOSE_REQUEST");
    }

    @Test
    void closeRequestActionIsAdvertisedWhenCommandReadinessIsComplete() {
        Department department = department();
        User manager = user("ready.manager", department);
        ServiceRequest request = draftRequest(manager.getId(), department.getId(), "ready.manager");
        request.setStatus("PAID");
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        request.setRejectedAmount(BigDecimal.ZERO);
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(company);
        order.setServiceRequestId(request.getId());
        order.setStatus("ACCEPTED");
        com.enterprise.mm.entity.ServiceExecution execution = new com.enterprise.mm.entity.ServiceExecution();
        execution.setId(UUID.randomUUID());
        execution.setServiceRequestId(request.getId());
        execution.setServiceOrderId(order.getId());
        execution.setStatus("ACCEPTED");
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(company);
        act.setServiceRequestId(request.getId());
        act.setServiceOrderId(order.getId());
        act.setStatus("PAID");
        act.setPaymentReference("TREASURY-READY");
        act.setAmount(BigDecimal.valueOf(100));
        ServiceAcceptanceAct rejectedHistory = new ServiceAcceptanceAct();
        rejectedHistory.setId(UUID.randomUUID());
        rejectedHistory.setServiceRequestId(request.getId());
        rejectedHistory.setServiceOrderId(order.getId());
        rejectedHistory.setStatus("REJECTED");
        rejectedHistory.setAmount(BigDecimal.valueOf(100));
        ServiceAcceptanceAct cancelledHistory = new ServiceAcceptanceAct();
        cancelledHistory.setId(UUID.randomUUID());
        cancelledHistory.setServiceRequestId(request.getId());
        cancelledHistory.setServiceOrderId(order.getId());
        cancelledHistory.setStatus("CANCELLED");
        cancelledHistory.setAmount(BigDecimal.valueOf(100));
        com.enterprise.mm.payload.AttachmentDto signedAct = new com.enterprise.mm.payload.AttachmentDto();
        signedAct.setAttachmentType("ACT");
        authenticate("ready.manager", "PROCUREMENT_MANAGER");
        stubDraftWorkspace(request, manager);
        when(orderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(order));
        when(executionRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(execution));
        when(actRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(rejectedHistory, cancelledHistory, act));
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId())).thenReturn(List.of(signedAct));

        var workspace = service.workspace(request.getId());

        assertThat(workspace.allowedActions()).filteredOn(action -> action.code().equals("CLOSE_REQUEST"))
            .singleElement().satisfies(action -> assertThat(action.enabled()).isTrue());
        assertThat(workspace.blockers()).extracting("code").doesNotContain("ACT_EVIDENCE");
    }

    @Test
    void serviceActMarkPaidIsBlockedByAnyOpenRequestClaim() {
        Department department = department();
        User finance = user("service.finance", department);
        ServiceRequest request = draftRequest(finance.getId(), department.getId(), "service.finance");
        request.setStatus("SENT_TO_PAYMENT");
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(company);
        act.setServiceRequestId(request.getId());
        act.setStatus("SENT_TO_PAYMENT");
        act.setVersion(4L);
        ServiceClaim requestClaim = new ServiceClaim();
        requestClaim.setId(UUID.randomUUID());
        requestClaim.setTenantId("default");
        requestClaim.setCompanyId(company);
        requestClaim.setServiceRequestId(request.getId());
        requestClaim.setServiceActId(UUID.randomUUID());
        requestClaim.setStatus("SENT");
        com.enterprise.mm.payload.AttachmentDto evidence = new com.enterprise.mm.payload.AttachmentDto();
        evidence.setAttachmentType("ACT");
        authenticate("service.finance", "FINANCE_CONTROLLER");
        stubDraftWorkspace(request, finance);
        when(actRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(act));
        when(claimRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(requestClaim));
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId())).thenReturn(List.of(evidence));

        var actItem = service.workspace(request.getId()).acts().getFirst();

        assertThat(actItem.paymentReadiness()).filteredOn(check -> check.code().equals("OPEN_CLAIMS"))
            .singleElement().satisfies(check -> assertThat(check.passed()).isFalse());
        assertThat(actItem.allowedActions()).filteredOn(action -> action.code().equals("MARK_PAID"))
            .singleElement().satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReason()).isNotBlank();
            });
    }

    @Test
    void serviceActMarkPaidFormRemainsReachableWhenTreasuryAssignsReferenceAfterSend() {
        Department department = department();
        User finance = user("treasury.service.finance", department);
        ServiceRequest request = draftRequest(finance.getId(), department.getId(), "treasury.service.finance");
        request.setStatus("SENT_TO_PAYMENT");
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(company);
        act.setServiceRequestId(request.getId());
        act.setStatus("SENT_TO_PAYMENT");
        act.setPaymentReference(null);
        act.setVersion(7L);
        com.enterprise.mm.payload.AttachmentDto evidence = new com.enterprise.mm.payload.AttachmentDto();
        evidence.setAttachmentType("ACT");
        authenticate("treasury.service.finance", "FINANCE_CONTROLLER");
        stubDraftWorkspace(request, finance);
        when(actRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(act));
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId())).thenReturn(List.of(evidence));

        var actItem = service.workspace(request.getId()).acts().getFirst();

        assertThat(actItem.paymentReadiness()).filteredOn(check -> check.code().equals("TREASURY_ACKNOWLEDGEMENT"))
            .singleElement().satisfies(check -> assertThat(check.passed()).isFalse());
        assertThat(actItem.allowedActions()).filteredOn(action -> action.code().equals("MARK_PAID"))
            .singleElement().satisfies(action -> assertThat(action.enabled()).isTrue());
    }

    @Test
    void serviceActSendToPaymentDoesNotRequireTreasuryAcknowledgement() {
        Department department = department();
        User finance = user("send.service.finance", department);
        ServiceRequest request = draftRequest(finance.getId(), department.getId(), "send.service.finance");
        request.setStatus("APPROVED");
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(company);
        act.setServiceRequestId(request.getId());
        act.setStatus("APPROVED");
        act.setVersion(4L);
        com.enterprise.mm.payload.AttachmentDto evidence = new com.enterprise.mm.payload.AttachmentDto();
        evidence.setAttachmentType("ACT");
        authenticate("send.service.finance", "FINANCE_CONTROLLER");
        stubDraftWorkspace(request, finance);
        when(actRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of(act));
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId())).thenReturn(List.of(evidence));

        var actItem = service.workspace(request.getId()).acts().getFirst();

        assertThat(actItem.paymentReadiness()).extracting("code").doesNotContain("TREASURY_ACKNOWLEDGEMENT");
        assertThat(actItem.allowedActions()).filteredOn(action -> action.code().equals("SEND_TO_PAYMENT"))
            .singleElement().satisfies(action -> assertThat(action.enabled()).isTrue());
    }

    @Test
    void procurementGetsBackendOwnedCreateClaimActionForEligibleRequestCase() {
        Department department = department();
        User owner = user("case.owner", department);
        ServiceRequest request = draftRequest(owner.getId(), department.getId(), "case.owner");
        request.setStatus("WAITING_ACCEPTANCE");
        request.setSupplierId(UUID.randomUUID());
        authenticate("buyer", "PROCUREMENT_SPECIALIST");
        stubDraftWorkspace(request, owner);

        var workspace = service.workspace(request.getId());

        assertThat(workspace.allowedActions()).filteredOn(action -> action.code().equals("CREATE_CLAIM"))
            .singleElement().satisfies(action -> {
                assertThat(action.targetType()).isEqualTo("SERVICE_CASE");
                assertThat(action.targetId()).isEqualTo(request.getId());
                assertThat(action.targetVersion()).isEqualTo(1L);
                assertThat(action.idempotencyRequired()).isTrue();
            });
    }

    @Test
    void requestlessPoServiceDetailShowsDirectClaimsAndCanonicalCreateClaimAction() {
        UUID orderId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        authenticate("buyer.po.service", "PROCUREMENT_SPECIALIST");
        CaseRow row = canonicalRow(orderId, OriginType.PO_SERVICE, null, orderId, "SO-CLAIM-100");
        ServiceOrder order = new ServiceOrder();
        order.setId(orderId);
        order.setTenantId("default");
        order.setCompanyId(company);
        order.setCode("SO-CLAIM-100");
        order.setName("Requestless service order");
        order.setStatus("ACTIVE");
        order.setVersion(3L);
        order.setSupplierId(supplierId);
        order.setPurchaseOrderId(row.purchaseOrderId());
        order.setTotalAmount(BigDecimal.valueOf(500));
        ServiceClaim claim = new ServiceClaim();
        claim.setId(UUID.randomUUID());
        claim.setTenantId("default");
        claim.setCompanyId(company);
        claim.setServiceOrderId(orderId);
        claim.setSupplierId(supplierId);
        claim.setStatus("CREATED");
        claim.setVersion(1L);
        claim.setClaimType("QUALITY");
        claim.setSeverity("HIGH");
        when(caseQueryRepository.findCase(eq(orderId), any(CaseFilter.class))).thenReturn(Optional.of(row));
        when(orderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", orderId)).thenReturn(Optional.of(order));
        when(actRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse("default", company, orderId))
            .thenReturn(List.of());
        when(claimRepository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, orderId
        )).thenReturn(List.of(claim));
        when(attachmentService.list("SERVICE_CLAIM", claim.getId())).thenReturn(List.of());
        when(attachmentEntityAccessPolicy.canUpload(
            "SERVICE_CLAIM", claim.getId(), "CLAIM_DOCUMENT"
        )).thenReturn(true);
        when(reviewRepository.findByTenantIdAndCompanyIdAndClaimOriginTypeAndClaimOriginIdAndIsDeletedFalseOrderByActedAtAsc(
            "default", company, "PO_SERVICE", orderId
        )).thenReturn(List.of());
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            eq("default"), eq(company), any(UUID.class)
        )).thenReturn(List.of());
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            eq("default"), eq(company), any(String.class), any(UUID.class)
        )).thenReturn(List.of());

        var workspace = service.workspace(orderId);

        assertThat(workspace.claims()).hasSize(1);
        assertThat(workspace.claims().get(0)).satisfies(item -> {
            assertThat(item.evidence()).isEmpty();
            assertThat(item.evidenceTarget().entityType()).isEqualTo("SERVICE_CLAIM");
            assertThat(item.evidenceTarget().entityId()).isEqualTo(claim.getId());
            assertThat(item.evidenceTarget().allowedAttachmentTypes())
                .containsExactly("CLAIM_DOCUMENT", "PHOTO", "OTHER");
            assertThat(item.evidenceTarget().canUpload()).isTrue();
            assertThat(item.allowedActions()).extracting("code").containsExactly("SEND_CLAIM");
        });
        assertThat(workspace.allowedActions()).extracting("code").contains("CREATE_ACT", "CREATE_CLAIM");
        assertThat(workspace.allowedActions()).filteredOn(action -> action.code().equals("CREATE_CLAIM"))
            .singleElement().satisfies(action -> assertThat(action.targetId()).isEqualTo(orderId));
    }

    @Test
    void requestWorkspaceReturnsPersistedMilestonesAndTargetsTheirOwnVersions() {
        Department department = department();
        User owner = user("milestone.owner", department);
        ServiceRequest request = draftRequest(owner.getId(), department.getId(), "milestone.owner");
        request.setStatus("WAITING_ACCEPTANCE");
        ServiceRequestMilestone milestone = new ServiceRequestMilestone();
        milestone.setId(UUID.randomUUID());
        milestone.setTenantId("default");
        milestone.setCompanyId(company);
        milestone.setServiceRequestId(request.getId());
        milestone.setCode("SR-M-01");
        milestone.setName("Commissioning");
        milestone.setStatus("PENDING");
        milestone.setAcceptanceMode("AMOUNT");
        milestone.setOrderedAmount(BigDecimal.valueOf(100));
        milestone.setAcceptedAmount(BigDecimal.ZERO);
        milestone.setRejectedAmount(BigDecimal.ZERO);
        milestone.setCurrencyCode("UZS");
        milestone.setVersion(7L);
        authenticate("milestone.owner", "DEPARTMENT_WORKER");
        stubDraftWorkspace(request, owner);
        when(milestoneRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc(
                "default", company, request.getId()
            )).thenReturn(List.of(milestone));

        var workspace = service.workspace(request.getId());

        assertThat(workspace.milestones()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(milestone.getId());
            assertThat(item.title()).isEqualTo("Commissioning");
            assertThat(item.remainingAmount()).isEqualByComparingTo("100");
        });
        assertThat(workspace.allowedActions()).filteredOn(action -> action.code().equals("ACCEPT_MILESTONE"))
            .singleElement().satisfies(action -> {
                assertThat(action.targetType()).isEqualTo("SERVICE_REQUEST_MILESTONE");
                assertThat(action.targetId()).isEqualTo(milestone.getId());
                assertThat(action.targetVersion()).isEqualTo(7L);
                assertThat(action.idempotencyRequired()).isTrue();
            });
        assertThat(workspace.allowedActions()).extracting("code").doesNotContain("PARTIAL_ACCEPT");
    }

    @Test
    void poWorksDetailIncludesMilestoneWorkActEvidenceReadinessAndAllowedAction() {
        UUID caseId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        authenticate("finance", "FINANCE_CONTROLLER");
        CaseRow row = new CaseRow(
            caseId, OriginType.PO_WORKS, caseId, null, null, purchaseOrderId, caseId, actId,
            "WORK-100", "Pump overhaul", "APPROVED", "PAYMENT", UUID.randomUUID(), "Supplier",
            UUID.randomUUID(), "CTR-100", UUID.randomUUID(), "Owner", UUID.randomUUID(),
            LocalDate.now().plusDays(2), "ON_TRACK", false, BigDecimal.valueOf(40), "QUANTITY",
            BigDecimal.TEN, BigDecimal.valueOf(4), BigDecimal.ZERO, BigDecimal.valueOf(6), "HOUR",
            BigDecimal.valueOf(1_000), BigDecimal.valueOf(400), BigDecimal.ZERO, BigDecimal.valueOf(600),
            "UZS", "APPROVED", 0, 0, "SEND_TO_PAYMENT", 2L, LocalDateTime.now().minusDays(5), null
        );
        WorkActRow act = new WorkActRow(
            actId, "WA-100", "Stage acceptance", "APPROVED", 3L, purchaseOrderId, caseId,
            row.contractId(), "Plant 1", "STAGE-1", LocalDate.now().minusDays(3), LocalDate.now(),
            BigDecimal.valueOf(4), BigDecimal.valueOf(400), "PASSED", LocalDateTime.now(), "commission",
            "accepted", null, LocalDateTime.now(), LocalDateTime.now(), null, null,
            null, null, LocalDateTime.now().minusDays(1), LocalDateTime.now()
        );
        com.enterprise.mm.payload.AttachmentDto evidence = new com.enterprise.mm.payload.AttachmentDto();
        evidence.setAttachmentType("ACT");
        when(caseQueryRepository.findCase(eq(caseId), any(CaseFilter.class))).thenReturn(Optional.of(row));
        when(caseQueryRepository.findWorkActs(caseId)).thenReturn(List.of(act));
        when(attachmentService.list("WORK_COMPLETION_ACT", actId)).thenReturn(List.of(evidence));
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            eq("default"), eq(company), any(UUID.class)
        )).thenReturn(List.of());
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            eq("default"), eq(company), any(String.class), any(UUID.class)
        )).thenReturn(List.of());

        var workspace = service.workspace(caseId);

        assertThat(workspace.caseId()).isEqualTo(caseId);
        assertThat(workspace.originType()).isEqualTo("PO_WORKS");
        assertThat(workspace.request()).isNull();
        assertThat(workspace.milestones()).singleElement().satisfies(milestone -> {
            assertThat(milestone.id()).isEqualTo(caseId);
            assertThat(milestone.orderedQuantity()).isEqualByComparingTo("10");
            assertThat(milestone.acceptedQuantity()).isEqualByComparingTo("4");
        });
        assertThat(workspace.workActs()).singleElement().satisfies(item -> {
            assertThat(item.act().getId()).isEqualTo(actId);
            assertThat(item.evidence()).containsExactly(evidence);
            assertThat(item.paymentReadiness()).allMatch(check -> check.passed());
            assertThat(item.paymentReadiness()).extracting("code").doesNotContain("TREASURY_ACKNOWLEDGEMENT");
            assertThat(item.allowedActions()).extracting("code").contains("SEND_TO_PAYMENT");
        });
        assertThat(workspace.integrationEvents()).isNotNull();
        assertThat(workspace.auditTimeline()).isNotNull();
    }

    @Test
    void workActMarkPaidIsBlockedByAnyOpenClaimOnThePurchaseOrderLine() {
        UUID caseId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        authenticate("work.finance", "FINANCE_CONTROLLER");
        CaseRow row = new CaseRow(
            caseId, OriginType.PO_WORKS, caseId, null, null, purchaseOrderId, caseId, actId,
            "WORK-CLAIM", "Claim-blocked work", "SENT_TO_PAYMENT", "PAYMENT", UUID.randomUUID(), "Supplier",
            UUID.randomUUID(), "CTR-WORK", UUID.randomUUID(), "Owner", UUID.randomUUID(),
            LocalDate.now().plusDays(2), "ON_TRACK", false, BigDecimal.valueOf(100), "AMOUNT",
            null, null, null, null, null, BigDecimal.valueOf(400), BigDecimal.valueOf(400),
            BigDecimal.ZERO, BigDecimal.ZERO, "UZS", "SENT_TO_PAYMENT", 1, 1, "MARK_PAID", 2L,
            LocalDateTime.now().minusDays(5), null
        );
        WorkActRow act = new WorkActRow(
            actId, "WA-CLAIM", "Claim-blocked act", "SENT_TO_PAYMENT", 5L, purchaseOrderId, caseId,
            row.contractId(), "Plant 1", "STAGE-1", LocalDate.now().minusDays(3), LocalDate.now(),
            BigDecimal.valueOf(4), BigDecimal.valueOf(400), "PASSED", LocalDateTime.now(), "commission",
            "accepted", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), null,
            null, "PAYMENT-REQUEST", LocalDateTime.now().minusDays(1), LocalDateTime.now()
        );
        ServiceClaim lineClaim = new ServiceClaim();
        lineClaim.setId(UUID.randomUUID());
        lineClaim.setTenantId("default");
        lineClaim.setCompanyId(company);
        lineClaim.setPurchaseOrderItemId(caseId);
        lineClaim.setWorkCompletionActId(UUID.randomUUID());
        lineClaim.setStatus("SENT");
        com.enterprise.mm.payload.AttachmentDto evidence = new com.enterprise.mm.payload.AttachmentDto();
        evidence.setAttachmentType("ACT");
        when(caseQueryRepository.findCase(eq(caseId), any(CaseFilter.class))).thenReturn(Optional.of(row));
        when(caseQueryRepository.findWorkActs(caseId)).thenReturn(List.of(act));
        when(claimRepository.findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, caseId
        )).thenReturn(List.of(lineClaim));
        when(attachmentService.list("WORK_COMPLETION_ACT", actId)).thenReturn(List.of(evidence));

        var workAct = service.workspace(caseId).workActs().getFirst();

        assertThat(workAct.paymentReadiness()).filteredOn(check -> check.code().equals("OPEN_CLAIMS"))
            .singleElement().satisfies(check -> assertThat(check.passed()).isFalse());
        assertThat(workAct.allowedActions()).filteredOn(action -> action.code().equals("MARK_PAID"))
            .singleElement().satisfies(action -> {
                assertThat(action.enabled()).isFalse();
                assertThat(action.disabledReason()).isNotBlank();
            });
    }

    @Test
    void workActMarkPaidRequiresMatchingTreasuryAcknowledgement() {
        UUID caseId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        authenticate("treasury.work.finance", "FINANCE_CONTROLLER");
        CaseRow row = new CaseRow(
            caseId, OriginType.PO_WORKS, caseId, null, null, purchaseOrderId, caseId, actId,
            "WORK-TREASURY", "Treasury-confirmed work", "SENT_TO_PAYMENT", "PAYMENT", UUID.randomUUID(), "Supplier",
            UUID.randomUUID(), "CTR-TREASURY", UUID.randomUUID(), "Owner", UUID.randomUUID(),
            LocalDate.now().plusDays(2), "ON_TRACK", false, BigDecimal.valueOf(100), "AMOUNT",
            null, null, null, null, null, BigDecimal.valueOf(400), BigDecimal.valueOf(400),
            BigDecimal.ZERO, BigDecimal.ZERO, "UZS", "SENT_TO_PAYMENT", 0, 0, "MARK_PAID", 2L,
            LocalDateTime.now().minusDays(5), null
        );
        WorkActRow act = new WorkActRow(
            actId, "WA-TREASURY", "Treasury-confirmed act", "SENT_TO_PAYMENT", 5L, purchaseOrderId, caseId,
            row.contractId(), "Plant 1", "STAGE-1", LocalDate.now().minusDays(3), LocalDate.now(),
            BigDecimal.valueOf(4), BigDecimal.valueOf(400), "PASSED", LocalDateTime.now(), "commission",
            "accepted", null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), null,
            null, "TREASURY-WORK-5", LocalDateTime.now().minusDays(1), LocalDateTime.now()
        );
        com.enterprise.mm.payload.AttachmentDto evidence = new com.enterprise.mm.payload.AttachmentDto();
        evidence.setAttachmentType("ACT");
        when(caseQueryRepository.findCase(eq(caseId), any(CaseFilter.class))).thenReturn(Optional.of(row));
        when(caseQueryRepository.findWorkActs(caseId)).thenReturn(List.of(act));
        when(attachmentService.list("WORK_COMPLETION_ACT", actId)).thenReturn(List.of(evidence));

        var workAct = service.workspace(caseId).workActs().getFirst();

        assertThat(workAct.paymentReadiness()).filteredOn(check -> check.code().equals("TREASURY_ACKNOWLEDGEMENT"))
            .singleElement().satisfies(check -> assertThat(check.passed()).isFalse());
        assertThat(workAct.allowedActions()).filteredOn(action -> action.code().equals("MARK_PAID"))
            .singleElement().satisfies(action -> assertThat(action.enabled()).isTrue());
        verify(integrationEventRepository).countSuccessfulTreasuryPaymentAcknowledgements(
            "default", company, actId, "TREASURY-WORK-5",
            List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED")
        );
    }

    @Test
    void linkedSupplierGetsOnlyContractorEvidenceTypesOnEditableOwnWorkAct() {
        String login = "evidence@supplier.test";
        UUID supplierId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID purchaseOrderId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        authenticate(login, "SUPPLIER_MANAGER");
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setSupplierId(supplierId);
        account.setStatus("ACTIVE");
        when(supplierPortalAccountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            "default", company, login
        )).thenReturn(Optional.of(account));
        CaseRow row = new CaseRow(
            caseId, OriginType.PO_WORKS, caseId, null, null, purchaseOrderId, caseId, actId,
            "WORK-EVIDENCE", "Supplier work evidence", "DRAFT", "ACCEPTANCE", supplierId, "Supplier",
            UUID.randomUUID(), "CTR-E", null, null, null, LocalDate.now().plusDays(2), "ON_TRACK", false,
            BigDecimal.ZERO, "QUANTITY", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, "HOUR",
            BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1000), "UZS",
            "DRAFT", 0, 0, "SUBMIT_WORK_ACT", 1L, LocalDateTime.now(), null
        );
        WorkActRow act = new WorkActRow(
            actId, "WA-E", "Draft act", "DRAFT", 1L, purchaseOrderId, caseId, row.contractId(),
            null, null, null, null, BigDecimal.ONE, BigDecimal.valueOf(100), null, null, null, null,
            null, null, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(caseQueryRepository.findCase(eq(caseId), any(CaseFilter.class))).thenReturn(Optional.of(row));
        when(caseQueryRepository.findWorkActs(caseId)).thenReturn(List.of(act));
        when(attachmentService.list("WORK_COMPLETION_ACT", actId)).thenReturn(List.of());
        when(attachmentEntityAccessPolicy.canUpload(
            "WORK_COMPLETION_ACT", actId, "CONTRACTOR_REPORT"
        )).thenReturn(true);

        var target = service.workspace(caseId).evidenceTarget();

        assertThat(target.canUpload()).isTrue();
        assertThat(target.attachmentType()).isEqualTo("CONTRACTOR_REPORT");
        assertThat(target.allowedAttachmentTypes())
            .containsExactly("CONTRACTOR_REPORT", "PHOTO", "SUPPLIER_DOCUMENT")
            .doesNotContain("ACT", "SCOPE_OF_WORK");
    }

    private CaseRow canonicalRow(
        UUID caseId,
        OriginType originType,
        UUID requestId,
        UUID serviceOrderId,
        String code
    ) {
        return new CaseRow(
            caseId,
            originType,
            caseId,
            requestId,
            serviceOrderId,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            code,
            "Canonical service case",
            "APPROVED",
            "PAYMENT",
            UUID.randomUUID(),
            "Canonical supplier",
            UUID.randomUUID(),
            "CTR-100",
            UUID.randomUUID(),
            "Case owner",
            UUID.randomUUID(),
            LocalDate.now().plusDays(2),
            "ON_TRACK",
            false,
            BigDecimal.valueOf(50),
            "AMOUNT",
            null,
            null,
            null,
            null,
            null,
            BigDecimal.valueOf(500),
            BigDecimal.valueOf(250),
            BigDecimal.ZERO,
            BigDecimal.valueOf(250),
            "UZS",
            "APPROVED",
            0,
            0,
            "SEND_TO_PAYMENT",
            3L,
            LocalDateTime.now().minusDays(5),
            null
        );
    }

    private void stubDraftWorkspace(ServiceRequest request, User currentUser) {
        CaseRow row = new CaseRow(
            request.getId(), OriginType.SERVICE_REQUEST, request.getId(), request.getId(), null, null, null, null,
            request.getCode(), request.getName(), request.getStatus(),
            "DRAFT".equals(request.getStatus()) ? "REQUEST" : "ACCEPTANCE", null, null, null, null,
            request.getResponsibleUserId(), currentUser.getFullName(), request.getDepartmentId(),
            request.getPlannedEndDate(), "ON_TRACK", false, BigDecimal.ZERO, "AMOUNT",
            null, null, null, null, null, BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.valueOf(100), "UZS", null, 0, 0, "SUBMIT_REQUEST", 1L,
            LocalDateTime.now(), null
        );
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(eq("default"), eq(company), any(String.class)))
            .thenReturn(Optional.of(currentUser));
        when(caseQueryRepository.findCase(eq(request.getId()), any(CaseFilter.class))).thenReturn(Optional.of(row));
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse("default", request.getId()))
            .thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(orderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of());
        when(executionRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of());
        when(actRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of());
        when(claimRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", company, request.getId()
        )).thenReturn(List.of());
        when(remarkRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", company, request.getId()
        )).thenReturn(List.of());
        when(reviewRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByActedAtAsc(
            "default", company, request.getId()
        )).thenReturn(List.of());
    }

    private Department department() {
        Department department = new Department();
        department.setId(UUID.randomUUID());
        department.setTenantId("default");
        department.setCompanyId(company);
        department.setName("Maintenance");
        return department;
    }

    private User user(String username, Department department) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId("default");
        user.setCompanyId(company);
        user.setUsername(username);
        user.setFullName(username);
        user.setDepartment(department);
        return user;
    }

    private Employee employee(Department department) {
        Employee employee = new Employee();
        employee.setId(UUID.randomUUID());
        employee.setTenantId("default");
        employee.setCompanyId(company);
        employee.setDepartment(department);
        return employee;
    }

    private ServiceRequest draftRequest(UUID responsibleId, UUID departmentId, String createdBy) {
        ServiceRequest request = new ServiceRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId("default");
        request.setCompanyId(company);
        request.setCode("SR-DRAFT");
        request.setName("Draft service request");
        request.setStatus("DRAFT");
        request.setVersion(1L);
        request.setResponsibleUserId(responsibleId);
        request.setDepartmentId(departmentId);
        request.setCreatedBy(createdBy);
        request.setPlannedEndDate(LocalDate.now().plusDays(5));
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setCurrencyCode("UZS");
        return request;
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }
}


