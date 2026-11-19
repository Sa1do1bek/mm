package com.enterprise.mm.service;

import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.AcceptanceActItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ApprovalContext;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.AuditTimelineItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.BusinessContext;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.CaseListItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ClaimItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ExecutionItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.EvidenceTarget;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.IntegrationImpactItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.IntakeOption;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.MilestoneItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.MetricValue;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ManagementSeriesItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.PaymentReadinessCheck;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ProcessStep;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.QueueDescriptor;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.RemarkItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ServiceOrderItem;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ServiceRequestIntakeOptions;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ServicesManagementDashboard;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.SectionAvailability;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Summary;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.WorkspaceAction;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.WorkspaceBlocker;
import static com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.WorkCompletionActItem;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceRequestMilestone;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.ServiceClaimStatus;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ServiceAcceptanceActMapper;
import com.enterprise.mm.mapper.ServiceClaimMapper;
import com.enterprise.mm.mapper.ServiceExecutionMapper;
import com.enterprise.mm.mapper.ServiceOrderMapper;
import com.enterprise.mm.mapper.ServiceRequestMapper;
import com.enterprise.mm.mapper.ServiceReviewHistoryMapper;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.WorkCompletionActResponse;
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
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceRequestApprovalAdapter;
import com.enterprise.mm.service.approval.WorkCompletionActApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServiceAcceptanceWorkspaceService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private static final UUID NO_ACCESS_ID = new UUID(0L, 0L);
    private static final Set<String> SELF_SCOPED_ROLES = Set.of("PROCUREMENT_SPECIALIST", "DEPARTMENT_WORKER");
    public static final String QUEUE_EXECUTION = "EXECUTION";
    public static final String QUEUE_ACCEPTANCE = "ACCEPTANCE";
    public static final String QUEUE_REWORK = "REWORK";
    public static final String QUEUE_PAYMENT = "PAYMENT";
    public static final String QUEUE_INTEGRATION = "INTEGRATION";

    private static final Set<String> ACTIVE_EXECUTION_STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "PAUSED", "COMPLETED_BY_CONTRACTOR", "UNDER_REVIEW");
    private static final Set<String> FAILED_INTEGRATION_STATUSES = Set.of("FAILED", "RETRY", "DEAD", "CANCELLED");
    private static final Set<String> WORK_EVIDENCE_TYPES = Set.of("WORK", "MAINTENANCE", "REPAIR");
    private static final List<String> SUCCESSFUL_TREASURY_ACKNOWLEDGEMENT_STATUSES =
        List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED");

    private final ServiceRequestRepository requestRepository;
    private final ServiceRequestMilestoneRepository milestoneRepository;
    private final ServiceOrderRepository orderRepository;
    private final ServiceExecutionRepository executionRepository;
    private final ServiceAcceptanceActRepository actRepository;
    private final ServiceCaseWorkspaceQueryRepository caseQueryRepository;
    private final ServiceClaimRepository claimRepository;
    private final ServiceRequestRemarkRepository remarkRepository;
    private final ServiceReviewHistoryRepository reviewRepository;
    private final SupplierPortalAccountRepository supplierPortalAccountRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final DepartmentRepository departmentRepository;
    private final CurrencyRepository currencyRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final ServiceRequestMapper requestMapper;
    private final ServiceOrderMapper orderMapper;
    private final ServiceExecutionMapper executionMapper;
    private final ServiceAcceptanceActMapper actMapper;
    private final ServiceClaimMapper claimMapper;
    private final ServiceReviewHistoryMapper reviewMapper;
    private final AttachmentService attachmentService;
    private final AttachmentEntityAccessPolicy attachmentEntityAccessPolicy;
    private final ApprovalEngineService approvalEngineService;
    private final ServiceWorkflowAccessService accessService;

    @Transactional(readOnly = true)
    public Summary summary() {
        return summary(CaseFilter.all());
    }

    @Transactional(readOnly = true)
    public Summary summary(CaseFilter baseFilter) {
        CaseFilter scoped = resolveScope(baseFilter == null ? CaseFilter.all() : baseFilter);
        CaseFilter activeRequestsFilter = scoped.withOriginType(OriginType.SERVICE_REQUEST).withQueue("ALL");
        CaseFilter activeExecutionsFilter = scoped.withQueue(QUEUE_EXECUTION);
        CaseFilter acceptanceFilter = scoped.withQueue(QUEUE_ACCEPTANCE);
        CaseFilter reworkFilter = scoped.withQueue(QUEUE_REWORK);
        CaseFilter paymentFilter = scoped.withQueue(QUEUE_PAYMENT);
        CaseFilter overdueFilter = scoped.withQueue(QUEUE_EXECUTION).withOverdue(true);
        CaseFilter integrationFilter = scoped.withQueue(QUEUE_INTEGRATION);
        long activeRequests = caseQueryRepository.count(activeRequestsFilter);
        long activeExecutions = caseQueryRepository.count(activeExecutionsFilter);
        long awaitingAcceptance = caseQueryRepository.count(acceptanceFilter);
        long partialOrRework = caseQueryRepository.count(reworkFilter);
        long paymentReady = caseQueryRepository.count(paymentFilter);
        long overdueExecutions = caseQueryRepository.count(overdueFilter);
        long integrationIssues = caseQueryRepository.count(integrationFilter);
        return new Summary(
            activeRequests, activeExecutions, awaitingAcceptance, partialOrRework, paymentReady,
            overdueExecutions, integrationIssues,
            List.of(
                metric("activeRequests", activeRequests, activeRequestsFilter),
                metric("activeExecutions", activeExecutions, activeExecutionsFilter),
                metric("awaitingAcceptance", awaitingAcceptance, acceptanceFilter),
                metric("partialOrRework", partialOrRework, reworkFilter),
                metric("paymentReady", paymentReady, paymentFilter),
                metric("overdue", overdueExecutions, overdueFilter),
                metric("integrationIssues", integrationIssues, integrationFilter)
            )
        );
    }

    private MetricValue metric(String key, long value, CaseFilter filter) {
        return new MetricValue(key, true, BigDecimal.valueOf(value), null, null, null, appliedFilters(filter));
    }

    @Transactional(readOnly = true)
    public List<QueueDescriptor> queues() {
        return queues(CaseFilter.all());
    }

    @Transactional(readOnly = true)
    public List<QueueDescriptor> queues(CaseFilter baseFilter) {
        CaseFilter scoped = resolveScope(baseFilter == null ? CaseFilter.all() : baseFilter);
        return List.of(QUEUE_EXECUTION, QUEUE_ACCEPTANCE, QUEUE_REWORK, QUEUE_PAYMENT, QUEUE_INTEGRATION, "COMPLETED").stream()
            .map(key -> {
                CaseFilter queueFilter = scoped.withQueue(key);
                return new QueueDescriptor(key, key, caseQueryRepository.count(queueFilter), appliedFilters(queueFilter));
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public ServiceRequestIntakeOptions intakeOptions() {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        PageRequest byName = PageRequest.of(
            0,
            200,
            // Derived repository queries need entity properties, not SQL localization expressions.
            org.springframework.data.domain.Sort.by("name").ascending()
        );
        Set<String> roles = currentRoles();
        User currentUser = currentCompanyUser();
        UUID currentDepartmentId = departmentIdOf(currentUser);
        boolean departmentUser = roles.contains("DEPARTMENT_HEAD") || roles.contains("DEPARTMENT_WORKER");

        List<IntakeOption> suppliers = supplierRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(tenant, company, byName).stream()
            .filter(item -> "ACTIVE".equals(normalize(item.getStatus())))
            .map(item -> new IntakeOption(
                item.getId().toString(),
                display(item.getShortName(), item.getLegalName(), item.getName(), item.getCode()),
                display(item.getCode(), item.getLegalName())
            ))
            .toList();

        List<Department> availableDepartments = departmentRepository.search(
            tenant, company, false, "", null, null, List.of("ACTIVE"), false, byName
        ).getContent();
        if (departmentUser) {
            availableDepartments = currentDepartmentId == null ? List.of() : availableDepartments.stream()
                .filter(item -> Objects.equals(item.getId(), currentDepartmentId))
                .toList();
        }
        List<IntakeOption> departments = availableDepartments.stream()
            .map(item -> new IntakeOption(item.getId().toString(), display(item.getName(), item.getCode()), item.getCode()))
            .toList();

        List<User> availableOwners = userRepository.searchActiveCompanyUsers(tenant, company, null, byName).getContent();
        if (roles.contains("DEPARTMENT_WORKER")) {
            availableOwners = currentUser == null ? List.of() : availableOwners.stream()
                .filter(item -> Objects.equals(item.getId(), currentUser.getId()))
                .toList();
        } else if (roles.contains("DEPARTMENT_HEAD")) {
            availableOwners = currentDepartmentId == null ? List.of() : availableOwners.stream()
                .filter(item -> Objects.equals(departmentIdOf(item), currentDepartmentId))
                .toList();
        }
        List<IntakeOption> owners = availableOwners.stream()
            .map(item -> new IntakeOption(
                item.getId().toString(), display(item.getFullName(), item.getName(), item.getUsername()),
                departmentOf(item) == null ? null : display(departmentOf(item).getName(), departmentOf(item).getCode())
            ))
            .toList();

        List<IntakeOption> currencies = currencyRepository.search(
                tenant, company, false, "", null, null, List.of("ACTIVE"), false, byName
            ).getContent().stream()
            .map(this::currencyOption)
            .filter(Objects::nonNull)
            .toList();
        List<IntakeOption> units = unitOfMeasureRepository.search(
                tenant, company, false, "", null, null, byName
            ).getContent().stream()
            .filter(item -> item.getStatus() == null || item.getStatus().isBlank() || "ACTIVE".equals(normalize(item.getStatus())))
            .map(this::unitOption)
            .filter(Objects::nonNull)
            .toList();
        List<String> approvalPreview = roles.contains("DEPARTMENT_WORKER")
            ? List.of("DEPARTMENT_HEAD", "PROCUREMENT_MANAGER")
            : List.of("PROCUREMENT_MANAGER");
        return new ServiceRequestIntakeOptions(suppliers, departments, owners, currencies, units, approvalPreview);
    }

    private IntakeOption currencyOption(Currency currency) {
        String value = display(currency.getIsoCode(), currency.getCode(), "");
        if (value.isBlank() || "—".equals(value)) return null;
        return new IntakeOption(value.toUpperCase(Locale.ROOT), display(currency.getName(), value), currency.getSymbol());
    }

    private IntakeOption unitOption(UnitOfMeasure unit) {
        String value = display(unit.getCode(), unit.getMeasurementNameShort(), "");
        if (value.isBlank() || "—".equals(value)) return null;
        return new IntakeOption(value, display(unit.getMeasurementNameShort(), unit.getName(), value), unit.getMeasurementNameLong());
    }

    @Transactional(readOnly = true)
    public ServicesManagementDashboard management(CaseFilter baseFilter) {
        CaseFilter scoped = resolveScope(baseFilter == null ? CaseFilter.all() : baseFilter);
        Summary summary = summary(scoped);
        List<ManagementBucket> buckets = caseQueryRepository.managementBuckets(scoped);
        Map<String, List<ManagementBucket>> byDimension = buckets.stream()
            .collect(Collectors.groupingBy(ManagementBucket::dimension, LinkedHashMap::new, Collectors.toList()));

        List<ManagementSeriesItem> lifecycle = byDimension.getOrDefault("LIFECYCLE", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket,
                scoped.withQueue(normalizeLifecycleQueue(bucket.key())).withCurrencyCode(bucket.currencyCode()),
                "Lifecycle value spans multiple currencies"
            ))
            .toList();
        List<ManagementSeriesItem> backlog = byDimension.getOrDefault("BACKLOG", List.of()).stream()
            .map(bucket -> new ManagementSeriesItem(
                bucket.key(), bucket.label(), BigDecimal.valueOf(bucket.caseCount()), true, null, null,
                appliedFilters(scoped.withQueue("ALL").withAgingBucket(bucket.key()))
            ))
            .toList();
        List<ManagementSeriesItem> exposure = byDimension.getOrDefault("EXPOSURE", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket, scoped.withQueue("ALL").withCurrencyCode(bucket.currencyCode()).withExposureMetric(bucket.key()),
                bucket.label() + " spans multiple currencies; configure a base-currency FX snapshot"
            ))
            .toList();
        List<ManagementSeriesItem> payment = byDimension.getOrDefault("PAYMENT", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket,
                scoped.withQueue(QUEUE_PAYMENT).withAgingBucket(bucket.key()).withCurrencyCode(bucket.currencyCode()),
                "Payment forecast bucket spans multiple currencies; configure a base-currency FX snapshot"
            ))
            .toList();
        List<ManagementSeriesItem> supplier = byDimension.getOrDefault("SUPPLIER", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket,
                scoped.withQueue("ALL").withSupplierId(parseUuid(bucket.key())).withCurrencyCode(bucket.currencyCode()),
                "Supplier exposure spans multiple currencies"
            ))
            .toList();
        List<ManagementSeriesItem> department = byDimension.getOrDefault("DEPARTMENT", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket,
                scoped.withQueue("ALL").withDepartmentId(parseUuid(bucket.key())).withCurrencyCode(bucket.currencyCode()),
                "Department exposure spans multiple currencies"
            ))
            .toList();
        List<ManagementSeriesItem> contract = byDimension.getOrDefault("CONTRACT", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket,
                scoped.withQueue("ALL").withContractId(parseUuid(bucket.key())).withCurrencyCode(bucket.currencyCode()),
                "Contract exposure spans multiple currencies"
            ))
            .toList();
        List<ManagementSeriesItem> category = byDimension.getOrDefault("CATEGORY", List.of()).stream()
            .map(bucket -> monetaryBucket(
                bucket,
                scoped.withQueue("ALL").withCategory(bucket.key()).withCurrencyCode(bucket.currencyCode()),
                "Category exposure spans multiple currencies"
            ))
            .toList();
        List<ManagementSeriesItem> integration = byDimension.getOrDefault("INTEGRATION_TREND", List.of()).stream()
            .map(bucket -> new ManagementSeriesItem(
                bucket.key(), bucket.label(), BigDecimal.valueOf(bucket.caseCount()), true, null, null,
                appliedFilters(scoped.withQueue(QUEUE_INTEGRATION).withIntegrationDay(LocalDate.parse(bucket.key())))
            ))
            .toList();
        List<MetricValue> metrics = new ArrayList<>(summary.metrics());
        byDimension.getOrDefault("QUALITY_METRIC", List.of()).stream()
            .map(bucket -> qualityMetric(bucket, scoped))
            .forEach(metrics::add);
        String baseCurrency = exposure.stream()
            .filter(ManagementSeriesItem::available)
            .map(ManagementSeriesItem::currency)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        return new ServicesManagementDashboard(
            List.copyOf(metrics), lifecycle, backlog, exposure, payment, integration, supplier, category,
            department, contract, baseCurrency, null
        );
    }

    private ManagementSeriesItem monetaryBucket(ManagementBucket bucket, CaseFilter filter, String mixedCurrencyReason) {
        boolean available = bucket.caseCount() > 0 && bucket.amount() != null && bucket.currencyCount() == 1
            && bucket.currencyCode() != null && !bucket.currencyCode().isBlank();
        String reason = available ? null : bucket.caseCount() == 0
            ? "No matching monetary exposure"
            : mixedCurrencyReason;
        return new ManagementSeriesItem(
            bucket.key(), bucket.label(), available ? bucket.amount() : null, available, reason,
            available ? bucket.currencyCode() : null, appliedFilters(filter)
        );
    }

    private ManagementSeriesItem unavailableItem(
        String key,
        String label,
        String reason,
        Map<String, String> filters
    ) {
        return new ManagementSeriesItem(key, label, null, false, reason, null, filters);
    }

    private UUID parseUuid(String value) {
        try {
            return value == null ? NO_ACCESS_ID : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return NO_ACCESS_ID;
        }
    }

    private String normalizeLifecycleQueue(String phase) {
        String normalized = normalize(phase);
        return Set.of("EXECUTION", "ACCEPTANCE", "REWORK", "PAYMENT", "INTEGRATION", "COMPLETED").contains(normalized)
            ? normalized : "ALL";
    }

    private MetricValue qualityMetric(ManagementBucket bucket, CaseFilter scoped) {
        CaseFilter filter = switch (bucket.key()) {
            case "CYCLE_TIME_DAYS", "ON_TIME_RATE" -> scoped.withQueue("COMPLETED");
            case "FIRST_PASS_RATE" -> scoped.withQueue("COMPLETED").withHasRework(false);
            case "REWORK_RATE" -> scoped.withQueue("ALL").withHasRework(true);
            case "OPEN_CLAIM_CASES" -> scoped.withQueue("ALL").withHasOpenClaims(true);
            default -> scoped.withQueue("ALL");
        };
        BigDecimal value = "OPEN_CLAIM_CASES".equals(bucket.key())
            ? BigDecimal.valueOf(bucket.caseCount())
            : bucket.amount();
        boolean available = value != null && bucket.caseCount() > 0;
        return new MetricValue(
            bucket.key(), available, available ? value : null, null, null,
            available ? null : "No completed cases are available for this metric", appliedFilters(filter)
        );
    }

    @Transactional(readOnly = true)
    public Page<CaseListItem> cases(CaseFilter filter, Pageable pageable) {
        filter = resolveScope(filter == null ? CaseFilter.all() : filter);
        return caseQueryRepository.findCases(filter, pageable).map(this::toCanonicalListItem);
    }

    @Transactional(readOnly = true)
    public Page<CaseListItem> cases(String queue, String search, Pageable pageable) {
        return cases(CaseFilter.all().withQueue(queue).withSearch(search), pageable);
    }

    private CaseListItem toCanonicalListItem(CaseRow row) {
        boolean workOrigin = row.originType() == OriginType.PO_WORKS;
        return new CaseListItem(
            row.caseId(),
            row.originType().name(),
            row.originId(),
            row.requestId(),
            row.serviceOrderId(),
            workOrigin ? null : row.latestActId(),
            workOrigin ? row.latestActId() : null,
            row.code(),
            row.title(),
            row.status(),
            row.phase(),
            null,
            row.supplierId(),
            row.supplierName(),
            row.contractId(),
            row.contractCode(),
            row.responsibleId(),
            row.responsibleName(),
            row.dueDate(),
            row.dueDate(),
            row.slaState(),
            row.overdue(),
            row.progressPercent(),
            row.acceptanceMode(),
            row.orderedQuantity(),
            row.acceptedQuantity(),
            row.rejectedQuantity(),
            row.remainingQuantity(),
            row.uomCode(),
            row.orderedAmount(),
            row.acceptedAmount(),
            row.rejectedAmount(),
            row.remainingAmount(),
            row.currencyCode(),
            row.latestActStatus(),
            row.openClaimCount(),
            row.blockerCount(),
            row.nextAction(),
            row.version()
        ).withUnitShortName(unitDisplay.shortName(TenantContext.currentTenant(), CompanyContext.currentCompany(), null, row.uomCode()));
    }

    private CaseFilter resolveScope(CaseFilter filter) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return filter.scope() == com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.ALL
                ? filter
                : filter.withScope(filter.scope(), null, null);
        }
        String username = authentication.getName();
        Set<String> roles = currentRoles();
        CaseFilter secured = filter;
        if (roles.contains("SUPPLIER_MANAGER")) {
            UUID linkedSupplierId = supplierPortalAccountRepository
                .findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), username
                )
                .filter(account -> "ACTIVE".equals(account.getStatus()))
                .map(com.enterprise.mm.entity.SupplierPortalAccount::getSupplierId)
                .orElse(NO_ACCESS_ID);
            secured = secured.withSupplierId(linkedSupplierId);
        }
        User currentUser = currentCompanyUser();
        UUID departmentId = departmentIdOf(currentUser);
        var effectiveScope = secured.scope();
        if (roles.contains("SUPPLIER_MANAGER")) {
            effectiveScope = com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.ALL;
        } else if (roles.stream().anyMatch(SELF_SCOPED_ROLES::contains)) {
            effectiveScope = com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.MY;
        } else if (roles.contains("DEPARTMENT_HEAD")
            && effectiveScope == com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.ALL) {
            effectiveScope = com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope.TEAM;
        }
        return secured.withScope(effectiveScope, currentUser == null ? null : currentUser.getId(), departmentId);
    }

    private Map<String, String> appliedFilters(CaseFilter filter) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("queue", filter.queue());
        result.put("scope", filter.scope().name());
        if (filter.originType() != null) result.put("originType", filter.originType().name());
        if (filter.ownerId() != null) result.put("ownerId", filter.ownerId().toString());
        if (filter.supplierId() != null) result.put("supplierId", filter.supplierId().toString());
        if (filter.departmentId() != null) result.put("departmentId", filter.departmentId().toString());
        if (filter.contractId() != null) result.put("contractId", filter.contractId().toString());
        if (filter.currencyCode() != null) result.put("currencyCode", filter.currencyCode());
        if (filter.category() != null) result.put("category", filter.category());
        if (filter.exposureMetric() != null) result.put("exposureMetric", filter.exposureMetric());
        if (filter.agingBucket() != null) result.put("agingBucket", filter.agingBucket());
        if (filter.integrationDay() != null) result.put("integrationDay", filter.integrationDay().toString());
        if (filter.hasOpenClaims() != null) result.put("hasOpenClaims", filter.hasOpenClaims().toString());
        if (filter.hasRework() != null) result.put("hasRework", filter.hasRework().toString());
        if (!filter.statuses().isEmpty()) result.put("status", String.join(",", filter.statuses()));
        if (filter.overdue() != null) result.put("overdue", filter.overdue().toString());
        if (filter.search() != null) result.put("search", filter.search());
        return Map.copyOf(result);
    }

    @Transactional(readOnly = true)
    public Workspace workspace(UUID requestId) {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        CaseRow canonicalCase = caseQueryRepository.findCase(requestId, resolveScope(CaseFilter.all()))
            .orElseThrow(() -> new NotFoundException("Service case not found: " + requestId));
        if (canonicalCase.originType() != OriginType.SERVICE_REQUEST) {
            return originWorkspace(canonicalCase);
        }
        ServiceRequest request = requestRepository.findByTenantIdAndIdAndIsDeletedFalse(tenant, requestId)
            .filter(item -> company.equals(item.getCompanyId()))
            .filter(accessService::canAccessServiceRequest)
            .orElseThrow(() -> new NotFoundException("ServiceRequest not found: " + requestId));

        List<String> loadErrors = new ArrayList<>();
        List<ServiceOrder> orders = orderRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(tenant, company, requestId);
        List<ServiceExecution> executions = executionRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(tenant, company, requestId);
        List<ServiceAcceptanceAct> acts = actRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(tenant, company, requestId);
        List<ServiceClaim> claims = claimRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(tenant, company, requestId);
        List<ServiceRequestMilestone> milestones = milestoneRepository
            .findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc(
                tenant, company, requestId
            );

        Map<UUID, List<AttachmentDto>> actEvidence = new HashMap<>();
        for (ServiceAcceptanceAct act : acts) {
            actEvidence.put(act.getId(), safe(loadErrors, "act evidence", () -> attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId())));
        }
        List<AttachmentDto> requestEvidence = safe(loadErrors, "request evidence", () -> attachmentService.list("SERVICE_REQUEST", requestId));
        List<IntegrationEvent> integrationEvents = safe(loadErrors, "integration events", () -> integrations(request, orders, executions, acts, claims));
        List<WorkspaceBlocker> blockers = blockers(request, executions, acts, claims, actEvidence, integrationEvents);
        String nextAction = nextAction(request, executions, acts, claims, blockers);
        List<ApprovalContext> requestApprovals = new ArrayList<>();
        approval(ServiceRequestApprovalAdapter.MODULE_KEY, requestId, loadErrors).ifPresent(requestApprovals::add);

        return new Workspace(
            requestMapper.toResponse(request),
            businessContext(request),
            nextAction,
            blockers,
            rootActions(request, orders, executions, acts, claims, milestones, actEvidence, nextAction),
            List.copyOf(requestApprovals),
            orders.stream().map(item -> orderItem(item, executions)).toList(),
            executions.stream().map(item -> executionItem(item, acts)).toList(),
            acts.stream().map(item -> actItem(item, orders, claims, actEvidence.getOrDefault(item.getId(), List.of()), loadErrors)).toList(),
            claims.stream().map(item -> claimItem(item, loadErrors)).toList(),
            remarkRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(tenant, company, requestId)
                .stream().map(this::remarkItem).toList(),
            reviewRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByActedAtAsc(tenant, company, requestId)
                .stream().map(reviewMapper::toResponse).toList(),
            requestEvidence,
            integrationEvents.stream().map(this::integrationItem).toList(),
            safe(loadErrors, "audit timeline", () -> auditTimeline(request, orders, executions, acts, claims)),
            List.copyOf(loadErrors),
            canonicalCase.caseId(),
            canonicalCase.originType().name(),
            canonicalCase.originId(),
            canonicalCase.code(),
            canonicalCase.title(),
            canonicalCase.status(),
            canonicalCase.phase(),
            canonicalCase.version(),
            completed(canonicalCase),
            processSteps(canonicalCase),
            milestones.stream().map(this::milestoneItem).toList(),
            List.of(),
            sectionAvailability(true, true, true, true),
            evidenceTarget(
                "SERVICE_REQUEST", request.getId(), "SCOPE_OF_WORK",
                List.of("SCOPE_OF_WORK", "SPECIFICATION", "ACT", "OTHER"), request.getStatus(), !completed(canonicalCase)
            )
        );
    }

    @Transactional(readOnly = true)
    public void requireAccessibleIntegrationEvent(UUID eventId) {
        IntegrationEvent event = integrationEventRepository.findWorkspaceEvent(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), eventId
            )
            .orElseThrow(() -> new NotFoundException("Integration event not found: " + eventId));
        if (event.getAggregateId() == null
            || caseQueryRepository.findCaseByAggregateId(event.getAggregateId(), resolveScope(CaseFilter.all())).isEmpty()) {
            throw new NotFoundException("Integration event not found: " + eventId);
        }
    }

    private Workspace originWorkspace(CaseRow canonicalCase) {
        return canonicalCase.originType() == OriginType.PO_WORKS
            ? worksOriginWorkspace(canonicalCase)
            : serviceOrderOriginWorkspace(canonicalCase);
    }

    private Workspace serviceOrderOriginWorkspace(CaseRow canonicalCase) {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        ServiceOrder order = orderRepository.findByTenantIdAndIdAndIsDeletedFalse(tenant, canonicalCase.serviceOrderId())
            .filter(item -> company.equals(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ServiceOrder not found: " + canonicalCase.serviceOrderId()));
        List<String> loadErrors = new ArrayList<>();
        List<ServiceAcceptanceAct> acts = actRepository
            .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(tenant, company, order.getId());
        List<ServiceClaim> claims = claimRepository
            .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, company, order.getId());
        Map<UUID, List<AttachmentDto>> evidence = new HashMap<>();
        acts.forEach(act -> evidence.put(
            act.getId(), safe(loadErrors, "act evidence", () -> attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId()))
        ));
        List<UUID> aggregateIds = new ArrayList<>();
        addId(aggregateIds, canonicalCase.purchaseOrderId());
        addId(aggregateIds, order.getId());
        acts.forEach(item -> addId(aggregateIds, item.getId()));
        claims.forEach(item -> addId(aggregateIds, item.getId()));
        List<IntegrationEvent> integrations = safe(loadErrors, "integration events", () -> integrations(aggregateIds));
        List<WorkspaceBlocker> blockers = canonicalBlockers(canonicalCase, integrations);
        boolean canCreateAct = !completed(canonicalCase) && hasRemainingAcceptance(canonicalCase);
        List<WorkspaceAction> rootActions = new ArrayList<>();
        if (canProcure()) {
            rootActions.add(action(
                "CREATE_ACT", "SERVICE_ORDER", order.getId(), order.getVersion(), true, canCreateAct, false, true,
                canCreateAct ? null : "No remaining acceptance basis is available"
            ));
            if (!completed(canonicalCase)) {
                rootActions.add(action(
                    "CREATE_CLAIM", "SERVICE_CASE", canonicalCase.caseId(), canonicalCase.version(), false,
                    true, false, true, null
                ));
            }
        }
        return new Workspace(
            null,
            businessContext(canonicalCase),
            canonicalCase.nextAction(),
            blockers,
            List.copyOf(rootActions),
            List.of(),
            List.of(orderItem(order, List.of())),
            List.of(),
            acts.stream().map(item -> actItem(
                item, List.of(order), claims, evidence.getOrDefault(item.getId(), List.of()), loadErrors
            )).toList(),
            claims.stream().map(item -> claimItem(item, loadErrors)).toList(),
            List.of(),
            reviewRepository.findByTenantIdAndCompanyIdAndClaimOriginTypeAndClaimOriginIdAndIsDeletedFalseOrderByActedAtAsc(
                tenant, company, OriginType.PO_SERVICE.name(), order.getId()
            ).stream().map(reviewMapper::toResponse).toList(),
            List.of(),
            integrations.stream().map(this::integrationItem).toList(),
            safe(loadErrors, "audit timeline", () -> auditTimeline(aggregateAuditTargets(
                canonicalCase.purchaseOrderId(), order.getId(), acts.stream().map(ServiceAcceptanceAct::getId).toList(),
                claims.stream().map(ServiceClaim::getId).toList()
            ))),
            List.copyOf(loadErrors),
            canonicalCase.caseId(), canonicalCase.originType().name(), canonicalCase.originId(), canonicalCase.code(),
            canonicalCase.title(), canonicalCase.status(), canonicalCase.phase(), canonicalCase.version(), completed(canonicalCase),
            processSteps(canonicalCase), List.of(milestone(canonicalCase)), List.of(), sectionAvailability(false, true, true, true),
            acts.isEmpty()
                ? evidenceTarget(
                    "SERVICE_ORDER", order.getId(), "SCOPE_OF_WORK",
                    List.of("SCOPE_OF_WORK", "SPECIFICATION", "OTHER"), order.getStatus(), !completed(canonicalCase)
                )
                : evidenceTarget(
                    "SERVICE_ACCEPTANCE_ACT", acts.get(0).getId(), "ACT",
                    List.of("ACT", "CONTRACTOR_REPORT", "PHOTO", "OTHER"),
                    acts.get(0).getStatus(),
                    !Set.of("SENT_TO_PAYMENT", "PAID").contains(acts.get(0).getStatus())
                )
        );
    }

    private Workspace worksOriginWorkspace(CaseRow canonicalCase) {
        List<String> loadErrors = new ArrayList<>();
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        List<WorkActRow> actRows = caseQueryRepository.findWorkActs(canonicalCase.purchaseOrderItemId());
        List<ServiceClaim> claims = claimRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, company, canonicalCase.purchaseOrderItemId());
        List<WorkCompletionActItem> workActs = actRows.stream()
            .map(row -> workActItem(row, claims, loadErrors))
            .toList();
        List<UUID> aggregateIds = new ArrayList<>();
        addId(aggregateIds, canonicalCase.purchaseOrderId());
        addId(aggregateIds, canonicalCase.purchaseOrderItemId());
        actRows.forEach(item -> addId(aggregateIds, item.id()));
        claims.forEach(item -> addId(aggregateIds, item.getId()));
        List<IntegrationEvent> integrations = safe(loadErrors, "integration events", () -> integrations(aggregateIds));
        List<WorkspaceBlocker> blockers = new ArrayList<>(canonicalBlockers(canonicalCase, integrations));
        if (actRows.stream().anyMatch(item -> "REJECTED".equals(item.status()))) {
            blockers.add(blocker("WORK_ACT_REJECTED", "BLOCKER", "A work-completion act requires correction", "CASE_OWNER", "Correct and resubmit the rejected act"));
        }
        List<WorkspaceAction> rootActions = new ArrayList<>();
        if (canProcure()) {
            if (nvl(canonicalCase.remainingQuantity()).signum() > 0) {
                rootActions.add(action("CREATE_WORK_ACT", "PURCHASE_ORDER_ITEM", canonicalCase.purchaseOrderItemId(), canonicalCase.version(), true, true, false, true, null));
            }
            if (!completed(canonicalCase)) {
                rootActions.add(action("CREATE_CLAIM", "SERVICE_CASE", canonicalCase.caseId(), canonicalCase.version(), rootActions.isEmpty(), true, false, true, null));
            }
        }
        List<AuditTarget> auditTargets = new ArrayList<>();
        auditTargets.add(new AuditTarget("PurchaseOrder", canonicalCase.purchaseOrderId()));
        auditTargets.add(new AuditTarget("PurchaseOrderItem", canonicalCase.purchaseOrderItemId()));
        actRows.forEach(item -> auditTargets.add(new AuditTarget("WorkCompletionAct", item.id())));
        claims.forEach(item -> auditTargets.add(new AuditTarget("ServiceClaim", item.getId())));
        return new Workspace(
            null,
            businessContext(canonicalCase),
            canonicalCase.nextAction(),
            List.copyOf(blockers),
            List.copyOf(rootActions),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            claims.stream().map(item -> claimItem(item, loadErrors)).toList(),
            List.of(),
            reviewRepository.findByTenantIdAndCompanyIdAndClaimOriginTypeAndClaimOriginIdAndIsDeletedFalseOrderByActedAtAsc(
                tenant, company, OriginType.PO_WORKS.name(), canonicalCase.purchaseOrderItemId()
            ).stream().map(reviewMapper::toResponse).toList(),
            List.of(),
            integrations.stream().map(this::integrationItem).toList(),
            safe(loadErrors, "audit timeline", () -> auditTimeline(auditTargets)),
            List.copyOf(loadErrors),
            canonicalCase.caseId(), canonicalCase.originType().name(), canonicalCase.originId(), canonicalCase.code(),
            canonicalCase.title(), canonicalCase.status(), canonicalCase.phase(), canonicalCase.version(), completed(canonicalCase),
            processSteps(canonicalCase), List.of(milestone(canonicalCase)), workActs, sectionAvailability(false, true, true, true),
            actRows.isEmpty()
                ? null
                : evidenceTarget(
                    "WORK_COMPLETION_ACT", actRows.get(0).id(), "ACT",
                    List.of("ACT", "CONTRACTOR_REPORT", "PHOTO", "OTHER"),
                    actRows.get(0).status(),
                    !Set.of("SENT_TO_PAYMENT", "PAID").contains(actRows.get(0).status())
                )
        );
    }


    private BusinessContext businessContext(ServiceRequest request) {
        BigDecimal ordered = request.getOrderedAmount() == null
            ? (nvl(request.getApprovedAmount()).signum() > 0 ? nvl(request.getApprovedAmount()) : nvl(request.getEstimatedAmount()))
            : request.getOrderedAmount();
        BigDecimal accepted = nvl(request.getAcceptedAmount());
        BigDecimal rejected = nvl(request.getRejectedAmount());
        String currency = display(
            request.getCurrencyCode(),
            request.getContract() == null ? null : request.getContract().getCurrencyCode(),
            "UZS"
        );
        return new BusinessContext(
            request.getSupplier() == null ? null : request.getSupplier().getCode(),
            request.getSupplier() == null ? "—" : display(request.getSupplier().getShortName(), request.getSupplier().getLegalName(), request.getSupplier().getName()),
            request.getContract() == null ? null : display(request.getContract().getContractNumber(), request.getContract().getCode()),
            request.getContract() == null ? "—" : display(request.getContract().getName(), request.getContract().getContractNumber()),
            request.getPurchaseOrder() == null ? null : request.getPurchaseOrder().getCode(),
            request.getPurchaseOrder() == null ? "—" : display(request.getPurchaseOrder().getName(), request.getPurchaseOrder().getCode()),
            request.getDepartment() == null ? "—" : display(request.getDepartment().getName(), request.getDepartment().getCode()),
            userName(request.getResponsibleUser()), request.getAcceptanceMode(), request.getOrderedQuantity(),
            request.getAcceptedQuantity(), request.getRejectedQuantity(), request.getRemainingQuantity(), request.getUomCode(),
            ordered, accepted, rejected,
            ordered.subtract(accepted).subtract(rejected).max(BigDecimal.ZERO), currency
        ).withUnitShortName(unitDisplay.shortName(request, null, request.getUomCode()));
    }

    private BusinessContext businessContext(CaseRow row) {
        return new BusinessContext(
            null,
            row.supplierName(),
            row.contractCode(),
            row.contractCode(),
            row.purchaseOrderId() == null ? null : row.purchaseOrderId().toString(),
            row.code(),
            null,
            row.responsibleName(),
            row.acceptanceMode(),
            row.orderedQuantity(),
            row.acceptedQuantity(),
            row.rejectedQuantity(),
            row.remainingQuantity(),
            row.uomCode(),
            row.orderedAmount(),
            row.acceptedAmount(),
            row.rejectedAmount(),
            row.remainingAmount(),
            row.currencyCode()
        ).withUnitShortName(unitDisplay.shortName(TenantContext.currentTenant(), CompanyContext.currentCompany(), null, row.uomCode()));
    }

    private MilestoneItem milestone(CaseRow row) {
        return new MilestoneItem(
            row.originId(), row.code(), row.title(), row.status(), row.dueDate(), row.acceptanceMode(),
            row.orderedQuantity(), row.acceptedQuantity(), row.rejectedQuantity(), row.remainingQuantity(), row.uomCode(),
            row.orderedAmount(), row.acceptedAmount(), row.rejectedAmount(), row.remainingAmount(), row.currencyCode()
        ).withUnitShortName(unitDisplay.shortName(TenantContext.currentTenant(), CompanyContext.currentCompany(), null, row.uomCode()));
    }

    private MilestoneItem milestoneItem(ServiceRequestMilestone milestone) {
        return new MilestoneItem(
            milestone.getId(), milestone.getCode(), milestone.getName(), milestone.getStatus(), milestone.getDueDate(),
            milestone.getAcceptanceMode(), milestone.getOrderedQuantity(), milestone.getAcceptedQuantity(),
            milestone.getRejectedQuantity(), milestone.getRemainingQuantity(), milestone.getUomCode(),
            milestone.getOrderedAmount(), milestone.getAcceptedAmount(), milestone.getRejectedAmount(),
            milestone.getRemainingAmount(), milestone.getCurrencyCode()
        ).withUnitShortName(unitDisplay.shortName(milestone, null, milestone.getUomCode()));
    }

    private List<ProcessStep> processSteps(CaseRow row) {
        List<String> steps = List.of("REQUEST", "EXECUTION", "ACCEPTANCE", "PAYMENT", "COMPLETED");
        int current = switch (display(row.phase(), "EXECUTION")) {
            case "REQUEST" -> 0;
            case "EXECUTION", "REWORK" -> 1;
            case "ACCEPTANCE" -> 2;
            case "PAYMENT" -> 3;
            case "COMPLETED" -> 4;
            default -> 1;
        };
        List<ProcessStep> result = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            boolean done = index < current || completed(row);
            result.add(new ProcessStep(
                steps.get(index), done ? "COMPLETED" : index == current ? "CURRENT" : "PENDING",
                done, index == current && !done, done && index == steps.size() - 1 ? row.completedAt() : null
            ));
        }
        return List.copyOf(result);
    }

    private boolean completed(CaseRow row) {
        return "COMPLETED".equals(row.phase()) || row.completedAt() != null;
    }

    private boolean hasRemainingAcceptance(CaseRow row) {
        return "QUANTITY".equals(row.acceptanceMode())
            ? nvl(row.remainingQuantity()).signum() > 0
            : nvl(row.remainingAmount()).signum() > 0;
    }

    private Map<String, SectionAvailability> sectionAvailability(
        boolean request,
        boolean evidence,
        boolean integrations,
        boolean audit
    ) {
        return Map.of(
            "request", availability(request, "No service-request aggregate exists for this origin"),
            "evidence", availability(evidence, "No evidence target is available"),
            "approvals", availability(true, null),
            "integrations", availability(integrations, "Integration impact is unavailable"),
            "audit", availability(audit, "Audit timeline is unavailable")
        );
    }

    private SectionAvailability availability(boolean available, String reason) {
        return new SectionAvailability(available, available ? null : reason);
    }

    private EvidenceTarget evidenceTarget(
        String entityType,
        UUID entityId,
        String attachmentType,
        List<String> allowedAttachmentTypes,
        String status,
        boolean editable
    ) {
        boolean supplier = hasAnyRole("SUPPLIER_MANAGER");
        List<String> effectiveTypes = supplier
            ? ("SERVICE_CLAIM".equals(entityType)
                ? List.of("CLAIM_DOCUMENT", "PHOTO", "SUPPLIER_RESPONSE")
                : List.of("CONTRACTOR_REPORT", "PHOTO", "SUPPLIER_DOCUMENT"))
            : List.copyOf(allowedAttachmentTypes);
        String effectiveDefault = effectiveTypes.contains(attachmentType)
            ? attachmentType
            : effectiveTypes.stream().findFirst().orElse(null);
        boolean canUpload = editable
            && effectiveDefault != null
            && attachmentEntityAccessPolicy.canUpload(entityType, entityId, effectiveDefault);
        return new EvidenceTarget(
            entityType, entityId, effectiveDefault, effectiveTypes, canUpload,
            canUpload ? null : editable ? "Current role cannot upload evidence" : "The evidence target is read-only"
        );
    }

    private WorkCompletionActItem workActItem(WorkActRow row, List<ServiceClaim> claims, List<String> loadErrors) {
        List<AttachmentDto> evidence = safe(
            loadErrors, "work-act evidence", () -> attachmentService.list("WORK_COMPLETION_ACT", row.id())
        );
        List<ApprovalContext> approvals = new ArrayList<>();
        approval(WorkCompletionActApprovalAdapter.MODULE_KEY, row.id(), loadErrors).ifPresent(approvals::add);
        List<PaymentReadinessCheck> readiness = new ArrayList<>(List.of(
            check("FINANCE_APPROVAL", Set.of("APPROVED", "SENT_TO_PAYMENT", "PAID").contains(row.status()),
                "BLOCKER", "Approval is required", "Complete work-act approval"),
            check("ACT_EVIDENCE", evidence.stream().anyMatch(item -> "ACT".equals(item.getAttachmentType())),
                "BLOCKER", "Signed work-completion act is required", "Attach the signed ACT document"),
            check("OPEN_CLAIMS", claims.stream().noneMatch(claim ->
                    !ServiceClaimStatus.CLOSED.name().equals(claim.getStatus())),
                "BLOCKER", "Open claims block payment", "Resolve linked claims")
        ));
        if ("SENT_TO_PAYMENT".equals(row.status())) {
            readiness.add(treasuryAcknowledgementCheck(row.id(), row.paymentReference()));
        }
        return new WorkCompletionActItem(
            workActResponse(row), evidence, evidenceTarget(
                "WORK_COMPLETION_ACT", row.id(), "ACT",
                List.of("ACT", "CONTRACTOR_REPORT", "PHOTO", "OTHER"), row.status(),
                !Set.of("SENT_TO_PAYMENT", "PAID").contains(row.status())
            ), List.copyOf(readiness), List.copyOf(approvals), workActActions(row, readiness)
        );
    }

    private WorkCompletionActResponse workActResponse(WorkActRow row) {
        WorkCompletionActResponse response = new WorkCompletionActResponse();
        response.setId(row.id());
        response.setCode(row.code());
        response.setName(row.name());
        response.setStatus(row.status());
        response.setVersion(row.version());
        response.setCreatedAt(row.createdAt());
        response.setUpdatedAt(row.updatedAt());
        response.setPurchaseOrderId(row.purchaseOrderId());
        response.setPurchaseOrderItemId(row.purchaseOrderItemId());
        response.setContractId(row.contractId());
        response.setWorkLocation(row.workLocation());
        response.setMilestone(row.milestone());
        response.setPeriodStart(row.periodStart());
        response.setPeriodEnd(row.periodEnd());
        response.setCompletedQuantity(row.completedQuantity());
        response.setAmount(row.amount());
        response.setInspectionResult(row.inspectionResult());
        response.setAcceptedAt(row.acceptedAt());
        response.setAcceptedBy(row.acceptedBy());
        response.setCommissionNotes(row.commissionNotes());
        response.setRemarks(row.remarks());
        response.setDocumentReviewedAt(row.documentReviewedAt());
        response.setFinanceReviewedAt(row.financeReviewedAt());
        response.setSentToPaymentAt(row.sentToPaymentAt());
        response.setPaidAt(row.paidAt());
        response.setContractConsumedAt(row.contractConsumedAt());
        response.setPaymentReference(row.paymentReference());
        return response;
    }

    private List<WorkspaceAction> workActActions(WorkActRow row, List<PaymentReadinessCheck> checks) {
        List<WorkspaceAction> actions = new ArrayList<>();
        if (canProcure() && Set.of("DRAFT", "REJECTED").contains(row.status())) {
            actions.add(action("SUBMIT_WORK_ACT", "WORK_COMPLETION_ACT", row.id(), row.version(), false, true, false, true, null));
        }
        if (hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER") && "APPROVED".equals(row.status())) {
            boolean ready = checks.stream().allMatch(PaymentReadinessCheck::passed);
            actions.add(action("SEND_TO_PAYMENT", "WORK_COMPLETION_ACT", row.id(), row.version(), false, ready, false, true,
                ready ? null : "Resolve payment-readiness blockers"));
        }
        if (hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER") && "SENT_TO_PAYMENT".equals(row.status())) {
            boolean ready = paymentConfirmationInputReady(checks);
            actions.add(action("MARK_PAID", "WORK_COMPLETION_ACT", row.id(), row.version(), false, ready, false, true,
                ready ? null : "Resolve payment-readiness blockers"));
        }
        return List.copyOf(actions);
    }

    private List<WorkspaceBlocker> canonicalBlockers(CaseRow row, List<IntegrationEvent> integrations) {
        List<WorkspaceBlocker> result = new ArrayList<>();
        if (row.overdue()) {
            result.add(blocker("OVERDUE", "WARNING", "The service case is overdue", "CASE_OWNER", "Update the due date or complete the current step"));
        }
        if (row.openClaimCount() > 0) {
            result.add(blocker("OPEN_CLAIMS", "BLOCKER", row.openClaimCount() + " open claim(s)", "SUPPLIER_MANAGER", "Resolve linked claims"));
        }
        if (row.originType() == OriginType.PO_WORKS
            && nvl(row.remainingQuantity()).signum() <= 0
            && nvl(row.remainingAmount()).signum() > 0) {
            result.add(blocker(
                "WORK_VALUE_MISMATCH",
                "BLOCKER",
                "Completed work quantity does not cover the contracted line value",
                "PROCUREMENT_MANAGER",
                "Reconcile legacy act quantity and amount before payment or close"
            ));
        }
        if (integrations.stream().anyMatch(item -> FAILED_INTEGRATION_STATUSES.contains(item.getStatus()))) {
            result.add(blocker("INTEGRATION_FAILURE", "WARNING", "Integration handoff needs attention", "INTEGRATION_OWNER", "Retry or resolve the failed event"));
        }
        return List.copyOf(result);
    }

    private List<IntegrationEvent> integrations(List<UUID> aggregateIds) {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        Map<UUID, IntegrationEvent> events = new LinkedHashMap<>();
        aggregateIds.stream().filter(Objects::nonNull).distinct().forEach(id ->
            integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, company, id
            ).forEach(event -> events.put(event.getId(), event))
        );
        return events.values().stream()
            .sorted(Comparator.comparing(IntegrationEvent::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private void addId(List<UUID> values, UUID id) {
        if (id != null) values.add(id);
    }

    private List<AuditTarget> aggregateAuditTargets(
        UUID purchaseOrderId,
        UUID serviceOrderId,
        List<UUID> actIds,
        List<UUID> claimIds
    ) {
        List<AuditTarget> targets = new ArrayList<>();
        targets.add(new AuditTarget("PurchaseOrder", purchaseOrderId));
        targets.add(new AuditTarget("ServiceOrder", serviceOrderId));
        actIds.forEach(id -> targets.add(new AuditTarget("ServiceAcceptanceAct", id)));
        claimIds.forEach(id -> targets.add(new AuditTarget("ServiceClaim", id)));
        return targets;
    }

    private List<AuditTimelineItem> auditTimeline(List<AuditTarget> targets) {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        List<AuditLog> logs = new ArrayList<>();
        targets.stream().filter(target -> target.id() != null).forEach(target ->
            addAudit(logs, tenant, company, target.type(), target.id())
        );
        return logs.stream()
            .sorted(Comparator.comparing(AuditLog::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(item -> new AuditTimelineItem(
                item.getId(), item.getEntityType(), item.getEntityId(), item.getAction(), item.getFullName(),
                item.getDetails(), item.getOccurredAt()
            ))
            .toList();
    }

    private ServiceOrderItem orderItem(ServiceOrder order, List<ServiceExecution> executions) {
        boolean acceptedExecution = executions.stream().filter(item -> Objects.equals(order.getId(), item.getServiceOrderId()))
            .anyMatch(item -> Set.of("ACCEPTED", "ACCEPTED_WITH_REMARKS").contains(item.getStatus()));
        List<WorkspaceAction> actions = List.of();
        if (acceptedExecution && !Set.of("ACCEPTED", "CLOSED").contains(order.getStatus()) && canProcure()) {
            actions = List.of(action("CREATE_ACT", "SERVICE_ORDER", order.getId(), order.getVersion(), false, true, false, true, null));
        }
        return new ServiceOrderItem(orderMapper.toResponse(order),
            order.getSupplier() == null ? "—" : display(order.getSupplier().getShortName(), order.getSupplier().getLegalName(), order.getSupplier().getName()),
            order.getContract() == null ? "—" : display(order.getContract().getContractNumber(), order.getContract().getCode()),
            order.getPurchaseOrder() == null ? "—" : display(order.getPurchaseOrder().getCode(), order.getPurchaseOrder().getName()), actions);
    }

    private ExecutionItem executionItem(ServiceExecution execution, List<ServiceAcceptanceAct> acts) {
        List<WorkspaceAction> actions = new ArrayList<>();
        if (canProcure() && !hasAcceptedActForExecution(execution, acts)) {
            switch (display(execution.getStatus(), "")) {
                case "NOT_STARTED" -> actions.add(action("START_EXECUTION", "SERVICE_EXECUTION", execution.getId(), execution.getVersion(), false, true, false, true, null));
                case "IN_PROGRESS", "PAUSED" -> actions.add(action("SUBMIT_EXECUTION", "SERVICE_EXECUTION", execution.getId(), execution.getVersion(), false, true, false, true, null));
                case "COMPLETED_BY_CONTRACTOR", "UNDER_REVIEW" -> {
                    actions.add(action("ACCEPT_EXECUTION", "SERVICE_EXECUTION", execution.getId(), execution.getVersion(), false, true, false, true, null));
                    actions.add(action("REJECT_EXECUTION", "SERVICE_EXECUTION", execution.getId(), execution.getVersion(), false, true, true, true, null));
                }
                default -> { }
            }
        }
        return new ExecutionItem(executionMapper.toResponse(execution), userName(execution.getResponsibleUser()), overdue(execution), List.copyOf(actions));
    }

    private boolean hasAcceptedActForExecution(ServiceExecution execution, List<ServiceAcceptanceAct> acts) {
        if (execution == null || acts == null || acts.isEmpty()) {
            return false;
        }
        return acts.stream()
            .filter(act -> Objects.equals(execution.getServiceOrderId(), act.getServiceOrderId())
                || Objects.equals(execution.getServiceRequestId(), act.getServiceRequestId()))
            .anyMatch(act -> Set.of(
                ServiceAcceptanceActStatus.APPROVED.name(),
                ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(),
                ServiceAcceptanceActStatus.PAID.name()
            ).contains(act.getStatus()));
    }

    private AcceptanceActItem actItem(
        ServiceAcceptanceAct act,
        List<ServiceOrder> orders,
        List<ServiceClaim> claims,
        List<AttachmentDto> evidence,
        List<String> loadErrors
    ) {
        ServiceOrder order = orders.stream().filter(item -> Objects.equals(item.getId(), act.getServiceOrderId())).findFirst().orElse(null);
        List<PaymentReadinessCheck> checks = paymentChecks(act, order, claims, evidence);
        List<WorkspaceAction> actions = actActions(act, checks);
        List<ApprovalContext> approvals = new ArrayList<>();
        approval(ServiceAcceptanceActApprovalAdapter.MODULE_KEY, act.getId(), loadErrors).ifPresent(approvals::add);
        approval(ServiceFinanceApprovalAdapter.MODULE_KEY, act.getId(), loadErrors).ifPresent(approvals::add);
        return new AcceptanceActItem(actMapper.toResponse(act), evidence, evidenceTarget(
            "SERVICE_ACCEPTANCE_ACT", act.getId(), "ACT",
            List.of("ACT", "CONTRACTOR_REPORT", "PHOTO", "OTHER"), act.getStatus(),
            !Set.of("SENT_TO_PAYMENT", "PAID").contains(act.getStatus())
        ), checks, List.copyOf(approvals), actions);
    }

    private ClaimItem claimItem(ServiceClaim claim, List<String> loadErrors) {
        List<WorkspaceAction> actions = new ArrayList<>();
        if (hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST")) {
            switch (display(claim.getStatus(), "")) {
                case "CREATED" -> actions.add(action("SEND_CLAIM", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, false, true, null));
                case "SENT" -> actions.add(action("START_CLAIM_RESOLUTION", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, true, true, null));
                case "UNDER_RESOLUTION" -> actions.add(action("RESOLVE_CLAIM", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, true, true, null));
                case "RESOLVED", "REJECTED" -> actions.add(action("CLOSE_CLAIM", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, true, true, null));
                default -> { }
            }
        } else if (hasAnyRole("SUPPLIER_MANAGER")) {
            switch (display(claim.getStatus(), "")) {
                case "SENT" -> actions.add(action("START_CLAIM_RESOLUTION", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, true, true, null));
                case "UNDER_RESOLUTION" -> {
                    actions.add(action("RESOLVE_CLAIM", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, true, true, null));
                    actions.add(action("REJECT_CLAIM", "SERVICE_CLAIM", claim.getId(), claim.getVersion(), false, true, true, true, null));
                }
                default -> { }
            }
        }
        List<AttachmentDto> evidence = safe(
            loadErrors, "claim evidence", () -> attachmentService.list("SERVICE_CLAIM", claim.getId())
        );
        return new ClaimItem(claimMapper.toResponse(claim),
            claim.getSupplier() == null ? "—" : display(claim.getSupplier().getShortName(), claim.getSupplier().getLegalName(), claim.getSupplier().getName()),
            evidence,
            evidenceTarget(
                "SERVICE_CLAIM", claim.getId(), "CLAIM_DOCUMENT",
                List.of("CLAIM_DOCUMENT", "PHOTO", "OTHER"), claim.getStatus(),
                !ServiceClaimStatus.CLOSED.name().equals(claim.getStatus())
            ),
            List.copyOf(actions));
    }

    private RemarkItem remarkItem(com.enterprise.mm.entity.ServiceRequestRemark remark) {
        User assigned = remark.getAssignedTo() == null ? null : userRepository.findByIdAndIsDeletedFalse(remark.getAssignedTo()).orElse(null);
        return new RemarkItem(remark.getId(), remark.getSeverity(), remark.getRemarkDescription(), remark.getDueDate(),
            remark.getAssignedTo(), userName(assigned), remark.getStatus(), remark.getCreatedAt());
    }

    private List<PaymentReadinessCheck> paymentChecks(ServiceAcceptanceAct act, ServiceOrder order, List<ServiceClaim> claims, List<AttachmentDto> evidence) {
        Set<String> types = evidence.stream().map(AttachmentDto::getAttachmentType).filter(Objects::nonNull).collect(Collectors.toSet());
        boolean workEvidenceRequired = order != null && WORK_EVIDENCE_TYPES.contains(normalize(order.getServiceType()));
        boolean noOpenClaims = claims.stream()
            .noneMatch(item -> !ServiceClaimStatus.CLOSED.name().equals(item.getStatus()));
        List<PaymentReadinessCheck> checks = new ArrayList<>(List.of(
            check("FINANCE_APPROVAL", Set.of("APPROVED", "SENT_TO_PAYMENT", "PAID").contains(act.getStatus()), "BLOCKER", "Finance approval is required", "Complete finance approval"),
            check("ACT_EVIDENCE", types.contains("ACT"), "BLOCKER", "Signed acceptance act is required", "Attach the signed ACT document"),
            check("WORK_EVIDENCE", !workEvidenceRequired || types.contains("CONTRACTOR_REPORT") || types.contains("PHOTO"), "BLOCKER", "Work evidence is required", "Attach contractor report or photo evidence"),
            check("OPEN_CLAIMS", noOpenClaims, "BLOCKER", "Open claims block payment", "Resolve and close every linked claim")
        ));
        if ("SENT_TO_PAYMENT".equals(act.getStatus())) {
            checks.add(treasuryAcknowledgementCheck(act.getId(), act.getPaymentReference()));
        }
        return List.copyOf(checks);
    }

    private PaymentReadinessCheck treasuryAcknowledgementCheck(UUID aggregateId, String paymentReference) {
        String normalizedReference = paymentReference == null ? null : paymentReference.trim();
        boolean acknowledged = normalizedReference != null
            && !normalizedReference.isBlank()
            && integrationEventRepository.countSuccessfulTreasuryPaymentAcknowledgements(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), aggregateId, normalizedReference,
                SUCCESSFUL_TREASURY_ACKNOWLEDGEMENT_STATUSES
            ) > 0;
        return check(
            "TREASURY_ACKNOWLEDGEMENT",
            acknowledged,
            "BLOCKER",
            "Successful Treasury payment acknowledgement is required before marking paid",
            "Wait for or reconcile the matching Treasury payment confirmation"
        );
    }

    private PaymentReadinessCheck check(String code, boolean passed, String severity, String message, String resolution) {
        return new PaymentReadinessCheck(code, passed, severity, message, resolution);
    }

    private List<WorkspaceAction> actActions(ServiceAcceptanceAct act, List<PaymentReadinessCheck> checks) {
        List<WorkspaceAction> actions = new ArrayList<>();
        String status = display(act.getStatus(), "");
        if (canProcure() && "DRAFT".equals(status)) actions.add(action("SUBMIT_ACT", "SERVICE_ACT", act.getId(), act.getVersion(), false, true, false, true, null));
        if (canProcure() && "DOCUMENT_REVIEW".equals(status)) {
            actions.add(action("DOCUMENT_APPROVE", "SERVICE_ACT", act.getId(), act.getVersion(), false, true, false, true, null));
            actions.add(action("DOCUMENT_REJECT", "SERVICE_ACT", act.getId(), act.getVersion(), false, true, true, true, null));
        }
        if (canProcure() && Set.of("TECHNICAL_REVIEW", "DOCUMENT_REVIEW", "FINANCE_REVIEW", "REJECTED").contains(status)) {
            actions.add(action("ROLLBACK_ACT", "SERVICE_ACT", act.getId(), act.getVersion(), false, true, true, true, null));
        }
        if (hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER") && "APPROVED".equals(status)) {
            boolean ready = checks.stream().allMatch(PaymentReadinessCheck::passed);
            actions.add(action("SEND_TO_PAYMENT", "SERVICE_ACT", act.getId(), act.getVersion(), false, ready, false, true,
                ready ? null : "Resolve payment-readiness blockers"));
        }
        if (hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER") && "SENT_TO_PAYMENT".equals(status)) {
            boolean ready = paymentConfirmationInputReady(checks);
            actions.add(action("MARK_PAID", "SERVICE_ACT", act.getId(), act.getVersion(), false, ready, false, true,
                ready ? null : "Resolve payment-readiness blockers"));
        }
        return List.copyOf(actions);
    }

    private boolean paymentConfirmationInputReady(List<PaymentReadinessCheck> checks) {
        // The real Treasury reference is commonly assigned by the callback, not
        // known when the act is sent. Keep the confirmation form reachable so
        // finance can enter that signed externalId; the command policy still
        // rejects any value without an exact correlated acknowledgement.
        return checks.stream()
            .filter(check -> !"TREASURY_ACKNOWLEDGEMENT".equals(check.code()))
            .allMatch(PaymentReadinessCheck::passed);
    }

    private List<WorkspaceAction> rootActions(
        ServiceRequest request,
        List<ServiceOrder> orders,
        List<ServiceExecution> executions,
        List<ServiceAcceptanceAct> acts,
        List<ServiceClaim> claims,
        List<ServiceRequestMilestone> milestones,
        Map<UUID, List<AttachmentDto>> evidence,
        String nextAction
    ) {
        List<WorkspaceAction> actions = new ArrayList<>();
        if ((canProcure() || canSubmitOwnRequest(request)) && Set.of("DRAFT", "RETURNED").contains(request.getStatus())) {
            actions.add(action("SUBMIT_REQUEST", "SERVICE_REQUEST", request.getId(), request.getVersion(), true, true, false, true, null));
        }
        if ((canProcure() || canSubmitOwnRequest(request))
            && Set.of("WAITING_ACCEPTANCE", "PARTIALLY_ACCEPTED").contains(request.getStatus())) {
            if (milestones.isEmpty()) {
                actions.add(action(
                    "PARTIAL_ACCEPT", "SERVICE_REQUEST", request.getId(), request.getVersion(), actions.isEmpty(),
                    true, true, true, null
                ));
            } else {
                for (ServiceRequestMilestone milestone : milestones) {
                    if (milestoneActionable(milestone)) {
                        actions.add(action(
                            "ACCEPT_MILESTONE", "SERVICE_REQUEST_MILESTONE", milestone.getId(), milestone.getVersion(),
                            actions.isEmpty(), true, false, true, null
                        ));
                    }
                }
            }
        }
        if (canProcure()) {
            if (Set.of("APPROVED", "WAITING_EXECUTION", "IN_PROGRESS", "WAITING_ACCEPTANCE", "PARTIALLY_ACCEPTED").contains(request.getStatus())) {
                actions.add(action("ADD_REMARKS", "SERVICE_REQUEST", request.getId(), request.getVersion(), false, true, true, true, null));
            }
            if (request.getSupplierId() != null
                && !Set.of("DRAFT", "CLOSED", "CANCELLED").contains(display(request.getStatus(), ""))) {
                actions.add(action(
                    "CREATE_CLAIM", "SERVICE_CASE", request.getId(), request.getVersion(), false, true, false, true, null
                ));
            }
        }
        if (hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER")
            && closeRequestReady(request, orders, executions, acts, claims, evidence)) {
            actions.add(action("CLOSE_REQUEST", "SERVICE_REQUEST", request.getId(), request.getVersion(), actions.isEmpty(), true, true, true, null));
        }
        if (actions.stream().noneMatch(WorkspaceAction::primary)) {
            actions = actions.stream().map((item) -> new WorkspaceAction(item.code(), item.targetType(), item.targetId(), item.targetVersion(),
                item.code().equals(actionCodeForNext(nextAction)), item.enabled(), item.reasonRequired(), item.idempotencyRequired(), item.disabledReason())).toList();
        }
        return List.copyOf(actions);
    }

    private boolean closeRequestReady(
        ServiceRequest request,
        List<ServiceOrder> orders,
        List<ServiceExecution> executions,
        List<ServiceAcceptanceAct> acts,
        List<ServiceClaim> claims,
        Map<UUID, List<AttachmentDto>> evidence
    ) {
        if (!ServiceRequestStatus.PAID.name().equals(request.getStatus())
            || claims.stream().anyMatch(item -> !ServiceClaimStatus.CLOSED.name().equals(item.getStatus()))
            || executions.isEmpty()
            || executions.stream().anyMatch(item -> !Set.of(
                ServiceExecutionStatus.ACCEPTED.name(),
                ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name(),
                ServiceExecutionStatus.CANCELLED.name()
            ).contains(item.getStatus()))) {
            return false;
        }

        List<ServiceAcceptanceAct> effectiveActs = acts.stream()
            .filter(act -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(act.getStatus()))
            .toList();
        if (effectiveActs.isEmpty() || effectiveActs.stream().anyMatch(act ->
            !ServiceAcceptanceActStatus.PAID.name().equals(act.getStatus())
                || act.getPaymentReference() == null
                || act.getPaymentReference().isBlank())) {
            return false;
        }

        List<ServiceAcceptanceAct> financialActs = effectiveActs.stream()
            .filter(act -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(act.getStatus()))
            .toList();
        Set<UUID> coveredOrderIds = financialActs.stream()
            .map(ServiceAcceptanceAct::getServiceOrderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (orders.stream().anyMatch(order -> !coveredOrderIds.contains(order.getId()))
            || !acceptedCoverageComplete(request, financialActs)) {
            return false;
        }
        return financialActs.stream().allMatch(act -> evidence.getOrDefault(act.getId(), List.of()).stream()
            .anyMatch(item -> "ACT".equals(item.getAttachmentType())));
    }

    private boolean acceptedCoverageComplete(ServiceRequest request, List<ServiceAcceptanceAct> acts) {
        String acceptanceMode = request.getAcceptanceMode() == null || request.getAcceptanceMode().isBlank()
            ? request.getOrderedQuantity() == null ? "AMOUNT" : "QUANTITY"
            : normalize(request.getAcceptanceMode());
        if ("QUANTITY".equals(acceptanceMode)) {
            return request.getOrderedQuantity() != null
                && request.getOrderedQuantity().signum() > 0
                && nvl(request.getAcceptedQuantity()).compareTo(request.getOrderedQuantity()) >= 0
                && nvl(request.getRejectedQuantity()).signum() == 0;
        }
        if (!"AMOUNT".equals(acceptanceMode)) {
            return false;
        }
        BigDecimal ordered = request.getOrderedAmount();
        if (ordered == null || ordered.signum() <= 0) {
            ordered = nvl(request.getApprovedAmount()).signum() > 0
                ? request.getApprovedAmount() : request.getEstimatedAmount();
        }
        BigDecimal rejected = request.getRejectedAmount() == null
            ? nvl(request.getRejectedVolume()) : request.getRejectedAmount();
        if (ordered == null || ordered.signum() <= 0 || nvl(rejected).signum() > 0) {
            return false;
        }
        BigDecimal covered = acts.stream()
            .filter(act -> ServiceOrderAcceptanceLifecycleService.contributesToAcceptedCoverage(act.getStatus()))
            .map(ServiceAcceptanceAct::getAmount)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return covered.compareTo(ordered) >= 0;
    }

    private boolean milestoneActionable(ServiceRequestMilestone milestone) {
        if ("QUANTITY".equals(milestone.getAcceptanceMode())) {
            return nvl(milestone.getRemainingQuantity()).signum() > 0
                || nvl(milestone.getRejectedQuantity()).signum() > 0;
        }
        return nvl(milestone.getRemainingAmount()).signum() > 0
            || nvl(milestone.getRejectedAmount()).signum() > 0;
    }

    private String actionCodeForNext(String nextAction) {
        return switch (nextAction) {
            case "SUBMIT_REQUEST" -> "SUBMIT_REQUEST";
            case "RECORD_PARTIAL_ACCEPTANCE", "COMPLETE_ACCEPTANCE" -> "PARTIAL_ACCEPT";
            case "RESOLVE_REWORK" -> "ADD_REMARKS";
            case "CLOSE_REQUEST" -> "CLOSE_REQUEST";
            default -> "";
        };
    }

    private List<WorkspaceBlocker> blockers(
        ServiceRequest request,
        List<ServiceExecution> executions,
        List<ServiceAcceptanceAct> acts,
        List<ServiceClaim> claims,
        Map<UUID, List<AttachmentDto>> evidence,
        List<IntegrationEvent> integrations
    ) {
        List<WorkspaceBlocker> result = new ArrayList<>();
        if (executions.stream().anyMatch(this::overdue)) result.add(blocker("OVERDUE_EXECUTION", "BLOCKER", "Execution milestone is overdue", "RESPONSIBLE_USER", "Update progress and resolve the overdue milestone"));
        long openClaims = claims.stream().filter(item -> !ServiceClaimStatus.CLOSED.name().equals(item.getStatus())).count();
        if (openClaims > 0) result.add(blocker("OPEN_CLAIMS", "BLOCKER", openClaims + " open claim(s)", "SUPPLIER_MANAGER", "Resolve and close the linked claims"));
        if (ServiceRequestStatus.PARTIALLY_ACCEPTED.name().equals(request.getStatus())) result.add(blocker("PARTIAL_ACCEPTANCE", "WARNING", "Service is only partially accepted", "SERVICE_OWNER", "Accept the remaining volume or record rework"));
        if (acts.stream().anyMatch(item -> Set.of("DRAFT", "TECHNICAL_REVIEW", "DOCUMENT_REVIEW", "FINANCE_REVIEW").contains(item.getStatus()))) result.add(blocker("ACT_REVIEW", "INFO", "Acceptance act review is incomplete", "REVIEW_COMMISSION", "Complete technical, document and finance review"));
        if (acts.stream()
            .filter(item -> !ServiceOrderAcceptanceLifecycleService.isInactiveActStatus(item.getStatus()))
            .anyMatch(item -> evidence.getOrDefault(item.getId(), List.of()).stream()
                .noneMatch(file -> "ACT".equals(file.getAttachmentType())))) {
            result.add(blocker("ACT_EVIDENCE", "BLOCKER", "Signed ACT evidence is missing", "SERVICE_OWNER", "Attach the signed acceptance act"));
        }
        if (integrations.stream().anyMatch(item -> FAILED_INTEGRATION_STATUSES.contains(item.getStatus()))) result.add(blocker("INTEGRATION_FAILURE", "WARNING", "EDO/FI/Treasury handoff needs attention", "INTEGRATION_OWNER", "Retry or resolve the failed integration event"));
        return List.copyOf(result);
    }

    private WorkspaceBlocker blocker(String code, String severity, String message, String owner, String resolution) {
        return new WorkspaceBlocker(code, severity, message, owner, resolution);
    }

    private String nextAction(ServiceRequest request, List<ServiceExecution> executions, List<ServiceAcceptanceAct> acts, List<ServiceClaim> claims, List<WorkspaceBlocker> blockers) {
        if (claims.stream().anyMatch(item -> !ServiceClaimStatus.CLOSED.name().equals(item.getStatus()))) return "RESOLVE_CLAIMS";
        if (ServiceRequestStatus.PARTIALLY_ACCEPTED.name().equals(request.getStatus())) return "RESOLVE_REWORK";
        ServiceAcceptanceAct latestAct = acts.stream().max(Comparator.comparing(ServiceAcceptanceAct::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
        if (latestAct != null) {
            return switch (display(latestAct.getStatus(), "")) {
                case "DRAFT" -> "SUBMIT_ACT";
                case "TECHNICAL_REVIEW", "DOCUMENT_REVIEW", "FINANCE_REVIEW" -> "COMPLETE_ACT_REVIEW";
                case "APPROVED" -> "SEND_TO_PAYMENT";
                case "SENT_TO_PAYMENT" -> "CONFIRM_PAYMENT";
                case "PAID" -> "CLOSE_REQUEST";
                case "REJECTED" -> "RESOLVE_REWORK";
                default -> "NONE";
            };
        }
        if (executions.stream().anyMatch(item -> Set.of("COMPLETED_BY_CONTRACTOR", "UNDER_REVIEW").contains(item.getStatus()))) return "COMPLETE_ACCEPTANCE";
        if (executions.stream().anyMatch(item -> ACTIVE_EXECUTION_STATUSES.contains(item.getStatus()))) return "UPDATE_EXECUTION";
        return switch (display(request.getStatus(), "")) {
            case "DRAFT", "RETURNED" -> "SUBMIT_REQUEST";
            case "APPROVAL_PENDING" -> "COMPLETE_REQUEST_APPROVAL";
            case "APPROVED", "WAITING_EXECUTION" -> "START_EXECUTION";
            case "WAITING_ACCEPTANCE" -> "RECORD_PARTIAL_ACCEPTANCE";
            case "ACCEPTED" -> "CREATE_ACT";
            case "SENT_TO_PAYMENT" -> "CONFIRM_PAYMENT";
            case "PAID" -> "CLOSE_REQUEST";
            default -> blockers.isEmpty() ? "NONE" : "RESOLVE_BLOCKERS";
        };
    }

    private List<IntegrationEvent> integrations(ServiceRequest request, List<ServiceOrder> orders, List<ServiceExecution> executions, List<ServiceAcceptanceAct> acts, List<ServiceClaim> claims) {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        Set<UUID> aggregateIds = new LinkedHashSet<>();
        aggregateIds.add(request.getId());
        orders.forEach(item -> aggregateIds.add(item.getId()));
        executions.forEach(item -> aggregateIds.add(item.getId()));
        acts.forEach(item -> aggregateIds.add(item.getId()));
        claims.forEach(item -> aggregateIds.add(item.getId()));
        Map<UUID, IntegrationEvent> events = new LinkedHashMap<>();
        for (UUID aggregateId : aggregateIds) {
            integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(tenant, company, aggregateId)
                .forEach(item -> events.put(item.getId(), item));
        }
        return events.values().stream().sorted(Comparator.comparing(IntegrationEvent::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    private List<AuditTimelineItem> auditTimeline(ServiceRequest request, List<ServiceOrder> orders, List<ServiceExecution> executions, List<ServiceAcceptanceAct> acts, List<ServiceClaim> claims) {
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        List<AuditLog> logs = new ArrayList<>();
        addAudit(logs, tenant, company, "ServiceRequest", request.getId());
        orders.forEach(item -> addAudit(logs, tenant, company, "ServiceOrder", item.getId()));
        executions.forEach(item -> addAudit(logs, tenant, company, "ServiceExecution", item.getId()));
        acts.forEach(item -> addAudit(logs, tenant, company, "ServiceAcceptanceAct", item.getId()));
        claims.forEach(item -> addAudit(logs, tenant, company, "ServiceClaim", item.getId()));
        return logs.stream().sorted(Comparator.comparing(AuditLog::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(item -> new AuditTimelineItem(item.getId(), item.getEntityType(), item.getEntityId(), item.getAction(), item.getFullName(), item.getDetails(), item.getOccurredAt()))
            .toList();
    }

    private void addAudit(List<AuditLog> target, String tenant, UUID company, String type, UUID id) {
        target.addAll(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(tenant, company, type, id));
    }

    private IntegrationImpactItem integrationItem(IntegrationEvent event) {
        return new IntegrationImpactItem(event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(),
            event.getStatus(), event.getRetryCount(), event.getNextRetryAt(), event.getExternalId(), event.getLastError(), event.getCreatedAt(),
            event.getVersion(), integrationRetryAction(event));
    }

    private WorkspaceAction integrationRetryAction(IntegrationEvent event) {
        if (!hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER")) return null;
        boolean retryable = Set.of("NEW", "FAILED", "RETRY", "DEAD").contains(display(event.getStatus(), ""));
        return action(
            "RETRY_INTEGRATION", "INTEGRATION_EVENT", event.getId(), event.getVersion(), false, retryable, true, true,
            retryable ? null : "Integration event is not in a retryable state"
        );
    }

    private java.util.Optional<ApprovalContext> approval(String moduleKey, UUID entityId, List<String> loadErrors) {
        try {
            var instance = approvalEngineService.currentInstance(moduleKey, entityId);
            return java.util.Optional.of(new ApprovalContext(moduleKey, instance, approvalEngineService.history(instance.id())));
        } catch (NotFoundException exception) {
            return java.util.Optional.empty();
        } catch (RuntimeException exception) {
            loadErrors.add("Unable to load " + moduleKey + " approval");
            return java.util.Optional.empty();
        }
    }

    private WorkspaceAction action(String code, String targetType, UUID targetId, Long version, boolean primary, boolean enabled, boolean reason, boolean idempotency, String disabledReason) {
        return new WorkspaceAction(code, targetType, targetId, version, primary, enabled, reason, idempotency, disabledReason);
    }

    private boolean overdue(ServiceExecution execution) {
        return execution.getPlannedEndDate() != null && execution.getPlannedEndDate().isBefore(LocalDate.now())
            && ACTIVE_EXECUTION_STATUSES.contains(execution.getStatus());
    }

    private boolean canProcure() {
        return hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST");
    }

    private boolean canSubmitOwnRequest(ServiceRequest request) {
        if (!hasAnyRole("DEPARTMENT_HEAD", "DEPARTMENT_WORKER")) return false;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) return false;
        User currentUser = currentCompanyUser();
        if (currentUser == null) return false;
        boolean own = Objects.equals(request.getResponsibleUserId(), currentUser.getId())
            || authentication.getName().equalsIgnoreCase(display(request.getCreatedBy(), ""));
        if (hasAnyRole("DEPARTMENT_WORKER")) return own;
        UUID currentDepartmentId = departmentIdOf(currentUser);
        return own || (currentDepartmentId != null && Objects.equals(request.getDepartmentId(), currentDepartmentId));
    }

    private UUID departmentIdOf(User user) {
        Department department = departmentOf(user);
        return department == null ? null : department.getId();
    }

    private Department departmentOf(User user) {
        if (user == null) {
            return null;
        }
        if (user.getDepartment() != null) {
            return user.getDepartment();
        }
        return user.getEmployee() == null
            ? null
            : user.getEmployee().getDepartment();
    }

    private boolean hasAnyRole(String... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        Set<String> expected = Set.of(roles);
        return authentication.getAuthorities().stream()
            .map(item -> item.getAuthority().replaceFirst("^ROLE_", ""))
            .anyMatch(expected::contains);
    }

    private User currentCompanyUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) return null;
        return userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), authentication.getName()
        ).orElse(null);
    }

    private Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return Set.of();
        return authentication.getAuthorities().stream()
            .filter(Objects::nonNull)
            .map(item -> item.getAuthority())
            .filter(Objects::nonNull)
            .map(item -> item.replaceFirst("^ROLE_", "").toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String userName(User user) {
        return user == null ? "—" : display(user.getFullName(), user.getUsername(), user.getName());
    }

    private String display(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return "—";
    }

    private <T> List<T> safe(List<String> loadErrors, String section, Supplier<List<T>> loader) {
        try {
            return loader.get();
        } catch (RuntimeException exception) {
            loadErrors.add("Unable to load " + section);
            return List.of();
        }
    }

    private record AuditTarget(String type, UUID id) {
    }

}
