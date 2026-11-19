package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.NonMovingStockMapper;
import com.enterprise.mm.payload.NonMovingStockCaseResponse;
import com.enterprise.mm.payload.NonMovingStockResponse;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.AuditTimelineItem;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.CaseWorkspace;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.EvidenceRequirement;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.EvidenceItem;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.IntegrationImpactItem;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SelectorOption;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceAction;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceBlocker;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceReadiness;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceSummary;
import com.enterprise.mm.payload.NonMovingStockWorkspaceFilter;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.NonMovingStockDecisionApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
@RequiredArgsConstructor
public class NonMovingStockWorkspaceService {
    private static final String CASE_ENTITY_TYPE = "NonMovingStockCase";
    private static final String CASE_ATTACHMENT_TYPE = "NON_MOVING_STOCK_CASE";
    private static final List<String> BLOCKING_INTEGRATION_STATUSES = List.of(
        "FAILED", "RETRY", "DEAD", "CANCELLED"
    );
    private final NonMovingStockCaseRepository caseRepository;
    private final NonMovingStockCaseLineRepository lineRepository;
    private final NonMovingStockRepository stockRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final AttachmentRepository attachmentRepository;
    private final NonMovingStockCaseService caseService;
    private final NonMovingStockMapper stockMapper;
    private final UserRepository userRepository;
    private final CommissionRepository commissionRepository;
    private final ApprovalEngineService approvalEngineService;
    private final NonMovingStockEvidencePolicy evidencePolicy;
    private final InventoryAccountingSettingsRepository inventoryAccountingSettingsRepository;

    private static final Set<String> MANAGER_ROLES = Set.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD");
    private static final Set<String> MM_MANAGER_ROLES = Set.of("ADMIN", "MM_MANAGER");
    private static final Set<String> DECISION_ROLES = Set.of("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT");
    private static final Set<String> EXECUTION_ROLES = Set.of(
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "FINANCE_CONTROLLER", "ACCOUNTANT"
    );
    private static final Set<String> CLOSURE_ROLES = Set.of("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT");



    @Transactional(readOnly = true)
    public Page<SelectorOption> userSelector(String search, Pageable pageable) {
        return userRepository.searchActiveCompanyUsers(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), normalizedSearch(search), pageable
        ).map(user -> new SelectorOption(
            user.getId(), user.getCode(), user.getName(), display(user.getFullName(), user.getName(), user.getUsername()), "ACTIVE"
        ));
    }

    @Transactional(readOnly = true)
    public Page<SelectorOption> commissionSelector(String search, Pageable pageable) {
        return commissionRepository.search(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), false, normalizedSearch(search),
            null, null, List.of("__NO_STATUS_FILTER__"), true, CommissionType.NON_MOVING_STOCK, true, pageable
        ).map(commission -> new SelectorOption(
            commission.getId(), commission.getCode(), commission.getName(), display(commission.getName(), commission.getCode()), "ACTIVE"
        ));
    }

    @Transactional(readOnly = true)
    public WorkspaceSummary summary(NonMovingStockWorkspaceFilter filter) {
        NonMovingStockWorkspaceFilter normalized = filter == null
            ? emptyFilter()
            : filter;
        NonMovingStockWorkspaceFilter allCaseScope = summaryScope(normalized, null, false);
        NonMovingStockWorkspaceFilter activeCaseScope = summaryScope(normalized, null, true);
        NonMovingStockWorkspaceFilter detectedScope = summaryScope(
            normalized, List.of("DETECTED", "UNDER_REVIEW"), false
        );
        List<NonMovingStockCase> allCases = caseEntities(allCaseScope, Pageable.unpaged()).getContent();
        List<NonMovingStockCase> activeCases = caseEntities(activeCaseScope, Pageable.unpaged()).getContent();
        long overdue = activeCases.stream().filter(item -> item.getReviewDueDate() != null
            && item.getReviewDueDate().isBefore(LocalDate.now())
            && !List.of("CLOSED", "CANCELLED").contains(item.getStatus())).count();
        long commission = activeCases.stream().filter(item -> "COMMISSION_REVIEW".equals(item.getStatus())).count();
        long approval = activeCases.stream().filter(item -> "APPROVAL_PENDING".equals(item.getStatus())).count();
        long inExecution = activeCases.stream().filter(item -> "IN_EXECUTION".equals(item.getStatus())).count();
        long closedPeriod = allCases.stream().filter(item -> "CLOSED".equals(item.getStatus())).count();
        List<UUID> caseIds = activeCases.stream().map(NonMovingStockCase::getId)
            .filter(java.util.Objects::nonNull).toList();
        long failedIntegrations = caseIds.isEmpty() ? 0 : integrationEventRepository
            .countDistinctCaseAggregatesWithStatuses(
                TenantContext.currentTenant(), CASE_ENTITY_TYPE, caseIds, BLOCKING_INTEGRATION_STATUSES
            );
        WorkspaceValue valueAtRisk = valueAtRisk(activeCases);
        return new WorkspaceSummary(
            positionEntities(detectedScope, Pageable.unpaged()).getTotalElements(),
            activeCases.size(),
            overdue, commission, approval, inExecution, failedIntegrations, closedPeriod, valueAtRisk.amount(),
            valueAtRisk.currency().code(), valueAtRisk.currency().health(), valueAtRisk.currency().message()
        );
    }

    @Transactional(readOnly = true)
    public Page<NonMovingStockResponse> positions(NonMovingStockWorkspaceFilter filter, Pageable pageable) {
        Map<UUID, ValueCurrency> currencies = new HashMap<>();
        return positionEntities(filter, pageable).map(item -> valueResponse(
            stockMapper.toResponse(item),
            currencies.computeIfAbsent(item.getCompanyId(), this::valueCurrency)
        ));
    }

    @Transactional(readOnly = true)
    public NonMovingStockResponse position(UUID id) {
        var item = stockRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("NonMovingStock not found: " + id));
        return valueResponse(stockMapper.toResponse(item), valueCurrency(item.getCompanyId()));
    }

    private NonMovingStockResponse valueResponse(NonMovingStockResponse response, ValueCurrency currency) {
        response.setValueCurrencyCode(currency.code());
        if (response.getEstimatedValue() == null) {
            response.setValueHealth("UNAVAILABLE");
            response.setValueHealthMessage(
                "Estimated value is unavailable because no stock balance value snapshot was found."
                    + (currency.message() == null ? "" : " " + currency.message())
            );
        } else {
            response.setValueHealth(currency.health());
            response.setValueHealthMessage(currency.message());
        }
        enrichCaseCreation(response);
        return response;
    }

    private void enrichCaseCreation(NonMovingStockResponse response) {
        if (response == null || response.getId() == null) {
            return;
        }
        var activeMembership = lineRepository
            .findByTenantIdAndNonMovingStockIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), response.getId()
            ).stream()
            .filter(line -> line.isActiveMembership())
            .findFirst();
        boolean statusEligible = List.of("DETECTED", "UNDER_REVIEW").contains(response.getStatus());
        response.setCaseCreationEligible(statusEligible && activeMembership.isEmpty());
        activeMembership.ifPresent(line -> {
            response.setActiveCaseId(line.getCaseId());
            caseRepository.findByIdAndIsDeletedFalse(line.getCaseId())
                .filter(stockCase -> TenantContext.currentTenant().equals(stockCase.getTenantId()))
                .filter(stockCase -> java.util.Objects.equals(response.getCompanyId(), stockCase.getCompanyId()))
                .ifPresent(stockCase -> response.setActiveCaseNo(stockCase.getCaseNo()));
        });
    }

    private ValueCurrency valueCurrency(UUID companyId) {
        return inventoryAccountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), companyId)
            .map(InventoryAccountingSettings::getBaseCurrencyCode)
            .filter(value -> value != null && value.trim().matches("(?i)[A-Z]{3}"))
            .map(value -> new ValueCurrency(value.trim().toUpperCase(Locale.ROOT), "READY", null))
            .orElseGet(() -> new ValueCurrency(
                "UZS",
                "PARTIAL",
                "Inventory accounting settings or a valid base currency are unavailable for the current company; "
                    + "stock values use the documented UZS fallback."
            ));
    }

    private WorkspaceValue valueAtRisk(List<NonMovingStockCase> activeCases) {
        if (activeCases.isEmpty()) {
            return new WorkspaceValue(BigDecimal.ZERO, valueCurrency(CompanyContext.currentCompany()));
        }
        List<ValueCurrency> currencies = activeCases.stream().map(this::caseValuationSnapshot).toList();
        if (currencies.stream().anyMatch(currency -> currency.code() == null)) {
            return new WorkspaceValue(null, new ValueCurrency(
                null, "UNAVAILABLE", joinedMessages(currencies)
            ));
        }
        List<String> currencyCodes = currencies.stream().map(ValueCurrency::code).distinct().sorted().toList();
        if (currencyCodes.size() > 1) {
            return new WorkspaceValue(null, new ValueCurrency(
                null,
                "UNAVAILABLE",
                "Value at risk spans multiple persisted case accounting currency snapshots ("
                    + String.join(", ", currencyCodes) + ") and is not comparable without conversion."
            ));
        }
        List<String> missingBookValueCases = activeCases.stream()
            .filter(stockCase -> stockCase.getTotalBookValue() == null)
            .map(stockCase -> display(stockCase.getCaseNo(), stockCase.getId()))
            .toList();
        if (!missingBookValueCases.isEmpty()) {
            return new WorkspaceValue(null, new ValueCurrency(
                currencyCodes.get(0),
                "UNAVAILABLE",
                "Cases " + String.join(", ", missingBookValueCases)
                    + " have no persisted book-value snapshot; value at risk cannot be aggregated."
            ));
        }
        if (currencies.stream().anyMatch(currency -> "UNAVAILABLE".equals(currency.health()))) {
            return new WorkspaceValue(null, new ValueCurrency(
                currencyCodes.get(0), "UNAVAILABLE", joinedMessages(currencies)
            ));
        }
        BigDecimal amount = activeCases.stream()
            .map(NonMovingStockCase::getTotalBookValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean partial = currencies.stream().anyMatch(currency -> !"READY".equals(currency.health()));
        return new WorkspaceValue(amount, new ValueCurrency(
            currencyCodes.get(0), partial ? "PARTIAL" : "READY", joinedMessages(currencies)
        ));
    }

    private ValueCurrency caseValuationSnapshot(NonMovingStockCase stockCase) {
        String reference = display(stockCase.getCaseNo(), stockCase.getId());
        String currency = stockCase.getAccountingCurrencyCode();
        if (currency == null || !currency.trim().matches("(?i)[A-Z]{3}")) {
            return new ValueCurrency(
                null,
                "UNAVAILABLE",
                "Case " + reference + " has no valid persisted accounting currency snapshot; its value is unavailable."
            );
        }
        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);
        String health = stockCase.getValuationHealth() == null
            ? null : stockCase.getValuationHealth().trim().toUpperCase(Locale.ROOT);
        if (health == null || !Set.of("READY", "PARTIAL", "UNAVAILABLE").contains(health)) {
            return new ValueCurrency(
                normalizedCurrency,
                "PARTIAL",
                "Case " + reference + " has a persisted accounting currency but no auditable valuation provenance; "
                    + "its value is partial."
            );
        }
        String message = normalizedMessage(stockCase.getValuationHealthMessage());
        if (!"READY".equals(health) && message == null) {
            message = "Case " + reference + " has valuation health " + health
                + " without a persisted explanation; review its valuation provenance.";
        } else if (!"READY".equals(health)) {
            message = "Case " + reference + ": " + message;
        }
        return new ValueCurrency(normalizedCurrency, health, message);
    }

    private String joinedMessages(List<ValueCurrency> currencies) {
        return currencies.stream()
            .map(ValueCurrency::message)
            .filter(java.util.Objects::nonNull)
            .filter(value -> !value.isBlank())
            .distinct()
            .reduce((left, right) -> left + " " + right)
            .orElse(null);
    }

    private String normalizedMessage(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ValueCurrency(String code, String health, String message) {
    }

    private record WorkspaceValue(BigDecimal amount, ValueCurrency currency) {
    }

    @Transactional(readOnly = true)
    public Page<NonMovingStockCaseResponse> cases(NonMovingStockWorkspaceFilter filter, Pageable pageable) {
        return caseEntities(filter, pageable).map(item -> enrichCommission(caseService.get(item.getId())));
    }

    @Transactional(readOnly = true)
    public CaseWorkspace workspace(UUID caseId) {
        NonMovingStockCaseResponse stockCase = enrichCommission(caseService.get(caseId));
        String tenant = TenantContext.currentTenant();
        UUID company = stockCase.getCompanyId();
        List<String> loadErrors = new ArrayList<>();
        var evidence = safe(loadErrors, "evidence", () -> attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, company, CASE_ATTACHMENT_TYPE, caseId
            ).stream().map(item -> new EvidenceItem(
                item.getId(), item.getOriginalFileName(), item.getAttachmentType(), item.getSizeBytes(),
                item.getUploadedAt(), item.isDownloadable(), item.isPreviewable()
            )).toList());
        var integrations = safe(loadErrors, "integration events", () -> integrationEventRepository
            .findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenant, company, CASE_ENTITY_TYPE, caseId
            ).stream().map(item -> new IntegrationImpactItem(
                item.getId(), item.getEventType(), item.getStatus(), item.getRetryCount(), item.getNextRetryAt(),
                item.getExternalId(), item.getLastError(), item.getCreatedAt()
            )).toList());
        var audit = safe(loadErrors, "audit timeline", () -> auditLogRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                tenant, company, CASE_ENTITY_TYPE, caseId
            ).stream().map(item -> new AuditTimelineItem(
                item.getId(), item.getAction(), item.getFullName() == null || item.getFullName().isBlank() ? item.getUsername() : item.getFullName(), item.getDetails(), item.getOccurredAt()
            )).toList());
        var caseEntity = caseRepository.findByIdAndIsDeletedFalse(caseId);
        List<WorkspaceBlocker> persistedBlockers = caseEntity
            .filter(item -> item.getBlockerType() != null && !item.getBlockerType().isBlank())
            .map(item -> List.of(new WorkspaceBlocker(item.getBlockerType(), item.getBlockerDetails())))
            .orElse(List.of());
        WorkspaceReadiness readiness = caseEntity
            .map(evidencePolicy::closureReadiness)
            .map(this::readiness)
            .orElseGet(() -> new WorkspaceReadiness(
                false, List.of(new WorkspaceBlocker("CASE_REQUIRED", "A persisted case is required for closure")), List.of()
            ));
        List<WorkspaceBlocker> blockers = new ArrayList<>(persistedBlockers);
        if ("EXECUTED".equals(stockCase.getStatus())) {
            readiness.blockers().stream()
                .filter(candidate -> blockers.stream().noneMatch(existing -> existing.code().equals(candidate.code())
                    && java.util.Objects.equals(existing.message(), candidate.message())))
                .forEach(blockers::add);
        }
        ApprovalBundle approval = approval(stockCase, loadErrors);
        return new CaseWorkspace(
            stockCase, nextAction(stockCase.getStatus()), List.copyOf(blockers),
            allowedActions(stockCase.getStatus(), readiness.closureReady()), readiness, evidence,
            integrations, audit, approval.instance(), approval.history(), loadErrors
        );
    }

    private Page<NonMovingStockCase> caseEntities(NonMovingStockWorkspaceFilter filter, Pageable pageable) {
        NonMovingStockWorkspaceFilter value = filter == null
            ? emptyFilter() : filter;
        String search = value.normalizedSearch();
        return caseRepository.search(
            TenantContext.currentTenant(), scopedCompany(), false, isBlank(search), searchPattern(search), from(value.dateFrom()),
            to(value.dateTo()), value.normalizedStatuses(), !value.hasStatuses(), value.warehouseId(),
            value.normalizedMaterialIds(), !value.hasMaterialIds(), value.responsibleUserId(),
            value.overdueOnlyEnabled(), value.normalizedDecisionTypes(), !value.hasDecisionTypes(),
            value.normalizedExecutionStatuses(), !value.hasExecutionStatuses(), value.materialGroupId(),
            value.minBookValue(), value.maxBookValue(), value.normalizedBlockerTypes(), !value.hasBlockerTypes(),
            value.normalizedIntegrationStatuses(), !value.hasIntegrationStatuses(),
            value.normalizedExclusionResults(), !value.hasExclusionResults(), value.activeCaseOnlyEnabled(), pageable
        );
    }

    private Page<com.enterprise.mm.entity.NonMovingStock> positionEntities(NonMovingStockWorkspaceFilter filter, Pageable pageable) {
        NonMovingStockWorkspaceFilter value = filter == null
            ? emptyFilter() : filter;
        String search = value.normalizedSearch();
        return stockRepository.search(
            TenantContext.currentTenant(), scopedCompany(), false, isBlank(search), searchPattern(search), from(value.dateFrom()),
            to(value.dateTo()), value.normalizedStatuses(), !value.hasStatuses(), value.warehouseId(),
            java.util.List.of(), true,
            value.normalizedMaterialIds(), !value.hasMaterialIds(), value.materialGroupId(),
            value.activeCaseOnlyEnabled(), pageable
        );
    }

    private NonMovingStockWorkspaceFilter emptyFilter() {
        return new NonMovingStockWorkspaceFilter(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
    }

    /**
     * KPI counts represent stable queue entry points. Workflow-state filters owned by a
     * queue are therefore replaced by the KPI's own contract, while shared business
     * scope (warehouse, owner, material group, value, decision type and period) remains.
     */
    private NonMovingStockWorkspaceFilter summaryScope(
        NonMovingStockWorkspaceFilter source,
        List<String> statuses,
        boolean activeCaseOnly
    ) {
        return new NonMovingStockWorkspaceFilter(
            source.search(), statuses, source.warehouseId(), source.materialIds(),
            source.dateFrom(), source.dateTo(), source.responsibleUserId(), false,
            source.decisionTypes(), null, source.materialGroupId(), source.minBookValue(),
            source.maxBookValue(), null, null, source.exclusionResults(), activeCaseOnly
        );
    }

    private NonMovingStockCaseResponse enrichCommission(NonMovingStockCaseResponse stockCase) {
        if (stockCase == null || stockCase.getCommissionId() == null) {
            return stockCase;
        }
        commissionRepository.findByIdAndIsDeletedFalse(stockCase.getCommissionId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> java.util.Objects.equals(stockCase.getCompanyId(), item.getCompanyId()))
            .ifPresent(item -> {
                stockCase.setCommissionCode(item.getCode());
                stockCase.setCommissionName(item.getName());
            });
        return stockCase;
    }

    private UUID scopedCompany() {
        return AccessScope.companyScoped(true) ? CompanyContext.currentCompany() : null;
    }

    private LocalDateTime from(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime to(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX);
    }

    private String nextAction(String status) {
        return switch (status == null ? "" : status) {
            case "CASE_CREATED" -> "ASSIGN_AND_START_REVIEW";
            case "UNDER_REVIEW" -> "COMPLETE_INVESTIGATION";
            case "COMMISSION_REVIEW" -> "RECORD_DECISION";
            case "APPROVAL_PENDING" -> "COMPLETE_APPROVAL";
            case "DECISION_APPROVED" -> "EXECUTE_DECISION";
            case "IN_EXECUTION" -> "RECONCILE_OUTPUTS";
            case "EXECUTED" -> "ATTACH_EVIDENCE_AND_CLOSE";
            case "REVIEW_DEFERRED" -> "RESUME_REVIEW";
            default -> "NONE";
        };
    }

    private List<WorkspaceAction> allowedActions(String status, boolean closureReady) {
        if (status == null) return List.of();
        List<WorkspaceAction> candidates = switch (status) {
            case "CASE_CREATED" -> List.of(
                action("START_REVIEW", true, false, false, false),
                action("ASSIGN", false, false, true, false),
                action("CHANGE_DUE_DATE", false, true, true, false),
                action("CANCEL", false, true, false, false));
            case "UNDER_REVIEW" -> List.of(
                action("SEND_TO_COMMISSION", true, false, false, false),
                action("RECORD_DECISION", false, false, false, false),
                action("ASSIGN", false, false, true, false),
                action("CHANGE_DUE_DATE", false, true, true, false),
                action("DEFER_REVIEW", false, true, true, false),
                action("CANCEL", false, true, false, false));
            case "COMMISSION_REVIEW" -> List.of(
                action("RECORD_DECISION", true, false, false, false),
                action("CANCEL", false, true, false, false));
            case "APPROVAL_PENDING" -> List.of(action("OPEN_APPROVAL", true, false, false, false));
            case "DECISION_APPROVED" -> List.of(action("EXECUTE_DECISION", true, false, false, true));
            case "EXECUTED" -> List.of(action("CLOSE", true, true, false, false));
            case "REVIEW_DEFERRED" -> List.of(
                action("START_REVIEW", true, false, false, false),
                action("CANCEL", false, true, false, false));
            case "CLOSED", "CANCELLED" -> List.of(action("REOPEN", true, true, false, false));
            default -> List.of();
        };
        return candidates.stream()
            .filter(item -> !"CLOSE".equals(item.code()) || closureReady)
            .filter(item -> permitted(item.code()))
            .toList();
    }

    private WorkspaceReadiness readiness(NonMovingStockEvidencePolicy.ClosureReadiness readiness) {
        List<WorkspaceBlocker> blockers = readiness.blockers().stream()
            .map(item -> new WorkspaceBlocker(item.code(), item.message()))
            .toList();
        List<EvidenceRequirement> requirements = readiness.evidenceRequirements().stream()
            .map(item -> new EvidenceRequirement(
                item.code(), item.decisionTypes(), item.acceptedTypes(), item.satisfied(), item.message()
            ))
            .toList();
        return new WorkspaceReadiness(readiness.closureReady(), blockers, requirements);
    }

    private WorkspaceAction action(String code, boolean primary, boolean reason, boolean date, boolean idempotency) {
        return new WorkspaceAction(code, primary, reason, date, idempotency);
    }

    private boolean permitted(String action) {
        Set<String> required = switch (action) {
            case "ASSIGN", "CHANGE_DUE_DATE", "START_REVIEW" -> MANAGER_ROLES;
            case "SEND_TO_COMMISSION", "DEFER_REVIEW", "CANCEL", "REOPEN" -> MM_MANAGER_ROLES;
            case "RECORD_DECISION", "OPEN_APPROVAL" -> DECISION_ROLES;
            case "EXECUTE_DECISION" -> EXECUTION_ROLES;
            case "CLOSE" -> CLOSURE_ROLES;
            default -> Set.of();
        };
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .anyMatch(required::contains);
    }

    private ApprovalBundle approval(NonMovingStockCaseResponse stockCase, List<String> loadErrors) {
        var latestDecision = stockCase.getDecisions() == null ? null : stockCase.getDecisions().stream()
            .filter(item -> item.getId() != null)
            .reduce((first, second) -> second)
            .orElse(null);
        if (latestDecision == null) return new ApprovalBundle(null, List.of());
        try {
            var instance = approvalEngineService.currentInstance(
                NonMovingStockDecisionApprovalAdapter.MODULE_KEY, latestDecision.getId());
            return new ApprovalBundle(instance, approvalEngineService.history(instance.id()));
        } catch (com.enterprise.mm.error.NotFoundException exception) {
            if (List.of("APPROVAL_PENDING", "DECISION_APPROVED").contains(latestDecision.getStatus())) {
                loadErrors.add("Unable to load approval workflow");
            }
            return new ApprovalBundle(null, List.of());
        } catch (RuntimeException exception) {
            loadErrors.add("Unable to load approval workflow");
            return new ApprovalBundle(null, List.of());
        }
    }

    private String normalizedSearch(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String searchPattern(String value) {
        return isBlank(value) ? "" : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String display(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return "—";
    }

    private record ApprovalBundle(
        com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse instance,
        List<com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse> history
    ) {
    }

    private <T> List<T> safe(List<String> errors, String section, java.util.function.Supplier<List<T>> loader) {
        try {
            return loader.get();
        } catch (RuntimeException exception) {
            errors.add("Unable to load " + section);
            return List.of();
        }
    }
}
