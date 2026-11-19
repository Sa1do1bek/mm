package com.enterprise.mm.warehouse.execution.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.IssueRequestCostObjectType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.IssueRequestSearchRequest;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReservationSearchRequest;
import com.enterprise.mm.payload.MaterialReturnResponse;
import com.enterprise.mm.payload.MaterialReturnSearchRequest;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.payload.TransferSearchRequest;
import com.enterprise.mm.repository.AssetManagementSettingsRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.IssueRequestService;
import com.enterprise.mm.service.MaterialReservationService;
import com.enterprise.mm.service.MaterialReturnService;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.AuditTimelineItem;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.DocumentType;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.EntityRef;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.IntegrationImpactItem;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.IssueRequestContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.Overview;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueFilter;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueItem;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueResponse;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueSummary;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.RelatedDocument;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.ReservationContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.ReturnContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.SelectorOption;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.TransferContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.WorkspaceAction;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.WorkspaceBlocker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseExecutionWorkspaceService {
    private static final Map<String, List<String>> ACTION_ROLES = Map.ofEntries(
        Map.entry("edit", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD")),
        Map.entry("return-for-correction", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD")),
        Map.entry("cancel-remainder", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD")),
        Map.entry("submit", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")),
        Map.entry("approve", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD")),
        Map.entry("reject", List.of("ADMIN", "MM_MANAGER", "DEPARTMENT_HEAD")),
        Map.entry("reserve", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("issue", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("dispatch", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("receive", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("record-discrepancy", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("accept", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("release", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")),
        Map.entry("close", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD")),
        Map.entry("cancel", List.of("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER"))
    );
    private static final Set<String> IDEMPOTENT_ACTIONS = Set.of("issue", "dispatch", "receive", "accept", "release", "cancel-remainder");

    private final IssueRequestService issueRequestService;
    private final MaterialReservationService reservationService;
    private final TransferDocumentService transferService;
    private final MaterialReturnService returnService;
    private final AuditLogRepository auditLogRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final com.enterprise.mm.service.RoleDataScopeService roleDataScopeService;
    private final DemandPlanItemRepository demandPlanItemRepository;
    private final AssetManagementSettingsRepository assetManagementSettingsRepository;

    public WarehouseExecutionWorkspaceService(
        IssueRequestService issueRequestService,
        MaterialReservationService reservationService,
        TransferDocumentService transferService,
        MaterialReturnService returnService,
        AuditLogRepository auditLogRepository,
        IntegrationEventRepository integrationEventRepository,
        UserRepository userRepository,
        DepartmentRepository departmentRepository,
        com.enterprise.mm.service.RoleDataScopeService roleDataScopeService,
        DemandPlanItemRepository demandPlanItemRepository,
        AssetManagementSettingsRepository assetManagementSettingsRepository
    ) {
        this.issueRequestService = issueRequestService;
        this.reservationService = reservationService;
        this.transferService = transferService;
        this.returnService = returnService;
        this.auditLogRepository = auditLogRepository;
        this.integrationEventRepository = integrationEventRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.roleDataScopeService = roleDataScopeService;
        this.demandPlanItemRepository = demandPlanItemRepository;
        this.assetManagementSettingsRepository = assetManagementSettingsRepository;
    }

    @Transactional(readOnly = true)
    public Page<SelectorOption> userSelector(String search, Pageable pageable) {
        if (roleDataScopeService.isDepartmentIssueScoped()) {
            return roleDataScopeService.page(userRepository.searchActiveCompanyUsers(TenantContext.currentTenant(),
                CompanyContext.currentCompany(), normalizedSearch(search), Pageable.unpaged()).stream()
                .filter(user -> roleDataScopeService.canReadIssueDepartment(user.getEmployee() != null && user.getEmployee().getDepartment() != null
                    ? user.getEmployee().getDepartment().getId() : user.getDepartment() == null ? null : user.getDepartment().getId()))
                .map(this::userOption).toList(), pageable);
        }
        return userRepository.searchActiveCompanyUsers(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            normalizedSearch(search),
            pageable
        ).map(this::userOption);
    }

    @Transactional(readOnly = true)
    public Page<SelectorOption> costObjectSelector(String type, String search, Pageable pageable) {
        IssueRequestCostObjectType costObjectType = parseCostObjectType(type);
        if (costObjectType == IssueRequestCostObjectType.DEPARTMENT) {
            if (roleDataScopeService.isDepartmentIssueScoped()) {
                UUID own = roleDataScopeService.issueDepartmentId();
                var departments = own == null ? List.<com.enterprise.mm.entity.Department>of()
                    : departmentRepository.findByIdAndIsDeletedFalse(own).stream()
                        .filter(d -> Objects.equals(d.getTenantId(), TenantContext.currentTenant()) && Objects.equals(d.getCompanyId(), CompanyContext.currentCompany()))
                        .filter(d -> normalizedSearch(search) == null || (String.valueOf(d.getName()) + " " + d.getCode()).toLowerCase(Locale.ROOT).contains(normalizedSearch(search).toLowerCase(Locale.ROOT)))
                        .toList();
                return roleDataScopeService.page(departments.stream().map(this::departmentOption).toList(), pageable);
            }
            return departmentRepository.search(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                false,
                normalizedSearch(search),
                null,
                null,
                List.of(),
                true,
                pageable
            ).map(this::departmentOption);
        }
        if (roleDataScopeService.isDepartmentIssueScoped()) {
            return roleDataScopeService.page(demandPlanItemRepository.searchWarehouseCostObjects(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), costObjectType.name(), normalizedSearch(search), Pageable.unpaged())
                .stream().filter(item -> item.getDemandPlan() != null && item.getDemandPlan().getDepartment() != null
                    && roleDataScopeService.canReadIssueDepartment(item.getDemandPlan().getDepartment().getId()))
                .map(item -> demandItemOption(item, costObjectType)).toList(), pageable);
        }
        return demandPlanItemRepository.searchWarehouseCostObjects(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            costObjectType.name(),
            normalizedSearch(search),
            pageable
        ).map(item -> demandItemOption(item, costObjectType));
    }

    @Transactional(readOnly = true)
    public QueueResponse queue(DocumentType type, QueueFilter filter, Pageable pageable) {
        QueueFilter normalized = (filter == null ? new QueueFilter(null, List.of(), null) : filter).normalized();
        List<String> statuses = normalized.status().isEmpty() ? activeStatuses(type) : normalized.status();
        QueueFilter effective = new QueueFilter(normalized.search(), statuses,
            type == DocumentType.TRANSFER ? roleDataScopeService.effectiveWarehouseId(normalized.warehouseId()) : normalized.warehouseId(),
            normalized.action());
        return switch (type) {
            case ISSUE_REQUEST -> queueFrom(type, effective, issueRequestService.search(
                new IssueRequestSearchRequest(
                    effective.search(), effective.status(), effective.warehouseId(), null, null, null, null, null, false
                ),
                pageable
            ).map(this::issueQueueItem));
            case RESERVATION -> queueFrom(type, effective, reservationService.search(
                new MaterialReservationSearchRequest(
                    effective.search(), effective.status(), effective.warehouseId(), null, null, null, false, null
                ),
                pageable
            ).map(this::reservationQueueItem));
            case TRANSFER -> queueFrom(type, effective, transferService.search(
                new TransferSearchRequest(effective.search(), effective.status(), effective.warehouseId(), null, null, null, null, effective.action()),
                pageable
            ).map(d -> transferQueueItem(d, effective.action(), effective.warehouseId())));
            case RETURN -> queueFrom(type, effective, returnService.search(
                new MaterialReturnSearchRequest(effective.search(), effective.status(), null, effective.warehouseId(), null, null),
                pageable
            ).map(this::returnQueueItem));
        };
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        return overview(null);
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID requestedWarehouseId) {
        UUID warehouseId = roleDataScopeService.effectiveWarehouseId(requestedWarehouseId);
        roleDataScopeService.assertWarehouseAccess(warehouseId);
        List<QueueSummary> queues = new ArrayList<>();
        for (DocumentType type : DocumentType.values()) {
            QueueResponse response = queue(
                type,
                new QueueFilter(null, List.of(), warehouseId, type == DocumentType.TRANSFER ? "tasks" : null),
                PageRequest.of(0, 1, Sort.by("createdAt").descending()));
            queues.add(new QueueSummary(
                type,
                "warehouseExecution.queue." + type.path(),
                response.totalElements(),
                response.appliedFilters(),
                route(type),
                defaultOwner(type)
            ));
        }
        boolean canReceiveFromSupplier = warehouseId != null && assetManagementSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .map(settings -> warehouseId.equals(settings.getCentralWarehouseId()))
            .orElse(false);
        return new Overview(
            List.copyOf(queues), LocalDateTime.now(), dataScope(), warehouseId,
            canReceiveFromSupplier, warehouseId != null
        );
    }

    @Transactional(readOnly = true)
    public IssueRequestContext issueRequestContext(UUID id) {
        IssueRequestResponse document = issueRequestService.get(id);
        if (document.getCostObjectId() != null && document.getCostObjectType() == IssueRequestCostObjectType.DEPARTMENT) {
            departmentRepository.findByIdAndIsDeletedFalse(document.getCostObjectId())
                .filter(department -> Objects.equals(department.getTenantId(), TenantContext.currentTenant())
                    && Objects.equals(department.getCompanyId(), document.getCompanyId()))
                .ifPresent(department -> document.setCostObjectName(department.getName()));
        } else if (document.getCostObjectId() != null && document.getCostObjectType() != null) {
            demandPlanItemRepository.findByIdAndIsDeletedFalse(document.getCostObjectId())
                .filter(item -> Objects.equals(item.getTenantId(), TenantContext.currentTenant())
                    && Objects.equals(item.getCompanyId(), document.getCompanyId()))
                .ifPresent(item -> document.setCostObjectName(demandItemOption(item, document.getCostObjectType()).displayName()));
        }
        return new IssueRequestContext(
            document,
            blockers(issueQueueItem(document)),
            actions(document.getAllowedActions(), true),
            issueRelated(document),
            audit("IssueRequest", id, document.getCompanyId()),
            integrations(id, document.getCompanyId()),
            LocalDateTime.now(),
            dataScope()
        );
    }

    @Transactional(readOnly = true)
    public ReservationContext reservationContext(UUID id) {
        MaterialReservationResponse document = reservationService.get(id);
        return new ReservationContext(
            document,
            blockers(reservationQueueItem(document)),
            actions(document.getAllowedActions()),
            reservationRelated(document),
            audit("MaterialReservation", id, document.getCompanyId()),
            integrations(id, document.getCompanyId()),
            LocalDateTime.now(),
            dataScope()
        );
    }

    @Transactional(readOnly = true)
    public TransferContext transferContext(UUID id) {
        TransferDocumentResponse document = transferService.get(id);
        return new TransferContext(
            document,
            transferBlockers(document),
            transferActions(document),
            transferRelated(document),
            audit("TransferDocument", id, document.getCompanyId()),
            integrations(id, document.getCompanyId()),
            LocalDateTime.now(),
            dataScope(),
            transferService.receiptContext(document)
        );
    }

    @Transactional(readOnly = true)
    public ReturnContext returnContext(UUID id) {
        MaterialReturnResponse document = returnService.get(id);
        return new ReturnContext(
            document,
            blockers(returnQueueItem(document)),
            actions(document.getAllowedActions()),
            returnRelated(document),
            audit("MaterialReturnDocument", id, document.getCompanyId()),
            integrations(id, document.getCompanyId()),
            LocalDateTime.now(),
            dataScope()
        );
    }

    private QueueResponse queueFrom(DocumentType type, QueueFilter filter, Page<QueueItem> page) {
        return new QueueResponse(
            type,
            filter,
            page.getTotalElements(),
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalPages(),
            LocalDateTime.now(),
            dataScope()
        );
    }

    private QueueItem issueQueueItem(IssueRequestResponse document) {
        BigDecimal remaining = safe(document.getLines()).stream()
            .map(line -> nvl(line.getRemainingQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String blocker = "PARTIALLY_ISSUED".equals(document.getStatus()) ? "STOCK_SHORTAGE" : null;
        String action = firstAction(document.getAllowedActions());
        return item(
            DocumentType.ISSUE_REQUEST,
            document.getId(),
            document.getCode(),
            document.getName(),
            document.getStatus(),
            warehouse(document.getWarehouseId(), document.getWarehouseName()),
            null,
            document.getRequiredDate(),
            safe(document.getLines()).size(),
            remaining,
            "warehouseExecution.quantity.remaining",
            action,
            ownerForIssue(document.getStatus()),
            blocker,
            blocker == null ? null : "Issue request has quantities still awaiting issue",
            document.getVersion()
        );
    }

    private QueueItem reservationQueueItem(MaterialReservationResponse document) {
        String blocker = document.getExpiresAt() != null && document.getExpiresAt().isBefore(LocalDateTime.now())
            && !List.of("RELEASED", "CONSUMED", "CANCELLED").contains(document.getStatus())
            ? "RESERVATION_EXPIRED"
            : null;
        return item(
            DocumentType.RESERVATION,
            document.getId(),
            document.getCode(),
            document.getName(),
            document.getStatus(),
            warehouse(document.getWarehouseId(), document.getWarehouseName()),
            null,
            document.getRequiredDate(),
            1,
            nvl(document.getReservedQuantity()),
            "warehouseExecution.quantity.reserved",
            firstAction(document.getAllowedActions()),
            "WAREHOUSE_OPERATOR",
            blocker,
            blocker == null ? null : "Reservation has expired and requires review",
            document.getVersion()
        );
    }

    private QueueItem transferQueueItem(TransferDocumentResponse document, String preferredAction, UUID warehouseId) {
        var summaries = safe(document.getUnitSummaries());
        // A headline is only a quantity when its unit is unambiguous.
        BigDecimal inTransit = summaries.size() == 1 ? summaries.getFirst().inTransit() : null;
        boolean incoming = warehouseId != null && warehouseId.equals(document.getDestinationWarehouseId());
        var availableActions = safe(document.getAllowedActions()).stream()
            .filter(a -> warehouseId == null || (incoming ? "receive".equals(a) : !"receive".equals(a)))
            .toList();
        var blockers = transferBlockers(document).stream()
            .filter(b -> !List.of("DRAFT", "SUBMITTED").contains(document.getStatus()))
            .filter(b -> warehouseId == null || !(incoming ? "SOURCE_WAREHOUSE" : "DESTINATION_WAREHOUSE").equals(b.ownerRole()))
            .toList();
        var blocker = blockers.isEmpty() ? null : blockers.getFirst();
        String nextAction = preferredAction;
        if (incoming && availableActions.contains("receive")) nextAction = "receive";
        else if ("DRAFT".equals(document.getStatus()) && availableActions.contains("submit")) nextAction = "submit";
        var item = item(DocumentType.TRANSFER, document.getId(), document.getCode(), document.getName(), document.getStatus(),
            warehouse(document.getSourceWarehouseId(), document.getSourceWarehouseName()), warehouse(document.getDestinationWarehouseId(), document.getDestinationWarehouseName()),
            document.getPlannedDate(), safe(document.getLines()).size(), inTransit, "warehouseExecution.quantity.inTransit", (nextAction != null && availableActions.contains(nextAction) ? nextAction : firstAction(availableActions)),
            blocker == null ? "WAREHOUSE_OPERATOR" : blocker.ownerRole(), blocker == null ? null : blocker.code(), blocker == null ? null : blocker.message(), document.getVersion());
        return new QueueItem(item.documentType(), item.document(), item.primaryWarehouse(), item.secondaryWarehouse(), item.statusLabelKey(), item.dueDate(), item.overdue(), item.lineCount(),
            item.primaryQuantity(), item.quantityLabelKey(), item.nextAction(), item.nextActionLabelKey(), item.ownerRole(), item.blockerCode(), item.blockerMessage(), item.version(), summaries,
            document.getInTransitAmount(), document.getBookCurrencyCode());
    }

    private List<WorkspaceAction> transferActions(TransferDocumentResponse document) {
        var result = new ArrayList<>(actions(document.getAllowedActions()));
        if (document.getProgress() != null && !document.getProgress().canClose()
            && Set.of("IN_TRANSIT", "DISCREPANCY_REVIEW", "RECEIVED").contains(document.getStatus())) {
            result.add(new WorkspaceAction("CLOSE", "warehouseExecution.action.CLOSE", false,
                document.getProgress().closeBlockers().isEmpty() ? "transfers.permission.close" : "transfers.close.blocked",
                ACTION_ROLES.get("close"), List.of("expectedVersion"), "transfers.close.impact", false, false));
        }
        return result;
    }

    private List<WorkspaceBlocker> transferBlockers(TransferDocumentResponse document) {
        if (document.getProgress() == null) return List.of();
        return document.getProgress().closeBlockers().stream().map(code -> {
            String action = switch (code) {
                case "UNDISPATCHED_REMAINDER" -> "dispatch";
                case "UNRESOLVED_TRANSIT" -> document.getReturnExceptionId() == null ? "receive" : "exceptions";
                case "LEGACY_RECONCILIATION_REQUIRED" -> "reconcile";
                default -> "exceptions";
            };
            String owner = switch (code) {
                case "UNDISPATCHED_REMAINDER" -> "SOURCE_WAREHOUSE";
                case "UNRESOLVED_TRANSIT" -> "DESTINATION_WAREHOUSE";
                case "UNPOSTED_OPERATIONS" -> "FINANCE_CONTROLLER";
                default -> "WAREHOUSE_MANAGER";
            };
            return new WorkspaceBlocker(code, "INFO", "transfers.blocker." + code, owner, action.toUpperCase(Locale.ROOT),
                "transfers.blocker." + code, "/app/stock-transfers?document=" + document.getId() + "&action=" + action, document.getVersion());
        }).toList();
    }

    private QueueItem returnQueueItem(MaterialReturnResponse document) {
        BigDecimal returnable = safe(document.getLines()).stream()
            .map(line -> nvl(line.getReturnableQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String blocker = "REJECTED".equals(document.getStatus()) ? "RETURN_REJECTED" : null;
        LocalDate dueDate = document.getCreatedAt() == null ? null : document.getCreatedAt().toLocalDate();
        return item(
            DocumentType.RETURN,
            document.getId(),
            document.getCode(),
            document.getName(),
            document.getStatus(),
            warehouse(document.getWarehouseId(), document.getWarehouseName()),
            null,
            dueDate,
            safe(document.getLines()).size(),
            returnable,
            "warehouseExecution.quantity.returnable",
            firstAction(document.getAllowedActions()),
            "WAREHOUSE_OPERATOR",
            blocker,
            blocker == null ? null : "Returned material was rejected during inspection",
            document.getVersion()
        );
    }

    private QueueItem item(
        DocumentType type,
        UUID id,
        String code,
        String name,
        String status,
        EntityRef primaryWarehouse,
        EntityRef secondaryWarehouse,
        LocalDate dueDate,
        long lineCount,
        BigDecimal quantity,
        String quantityLabelKey,
        String action,
        String owner,
        String blocker,
        String blockerMessage,
        Long version
    ) {
        String normalizedAction = action == null ? null : action.toUpperCase(Locale.ROOT).replace('-', '_');
        return new QueueItem(
            type,
            new EntityRef(id, code, display(name, code, id), status),
            primaryWarehouse,
            secondaryWarehouse,
            "warehouseExecution.status." + status,
            dueDate,
            dueDate != null && dueDate.isBefore(LocalDate.now()) && !terminal(type, status),
            lineCount,
            quantity,
            quantityLabelKey,
            normalizedAction,
            normalizedAction == null ? null : "warehouseExecution.action." + normalizedAction,
            owner,
            blocker,
            blockerMessage,
            version,
            List.of(),
            null,
            null
        );
    }

    private List<WorkspaceBlocker> blockers(QueueItem item) {
        if (item.blockerCode() == null) {
            return List.of();
        }
        return List.of(new WorkspaceBlocker(
            item.blockerCode(),
            "ERROR",
            item.blockerMessage(),
            item.ownerRole(),
            switch (item.blockerCode()) {
                case "STOCK_SHORTAGE" -> "RESOLVE_SHORTAGE";
                case "TRANSFER_DISCREPANCY" -> "RECORD_DISCREPANCY";
                case "RESERVATION_EXPIRED" -> "RELEASE_RESERVATION";
                default -> "REVIEW_DOCUMENT";
            }
        ));
    }

    private List<WorkspaceAction> actions(List<String> allowedActions) {
        return actions(allowedActions, false);
    }

    private List<WorkspaceAction> actions(List<String> allowedActions, boolean alreadyAuthorized) {
        return safe(allowedActions).stream().map(value -> {
            String normalized = value.toLowerCase(Locale.ROOT);
            List<String> roles = ACTION_ROLES.getOrDefault(normalized, List.of("ADMIN", "MM_MANAGER"));
            boolean enabled = alreadyAuthorized || hasAnyRole(roles);
            boolean idempotent = IDEMPOTENT_ACTIONS.contains(normalized);
            return new WorkspaceAction(
                normalized.toUpperCase(Locale.ROOT).replace('-', '_'),
                "warehouseExecution.action." + normalized.toUpperCase(Locale.ROOT).replace('-', '_'),
                enabled,
                enabled ? null : "Current role cannot perform this action",
                roles,
                requiredFields(normalized),
                actionImpact(normalized),
                idempotent || List.of("approve", "close", "release").contains(normalized),
                idempotent
            );
        }).toList();
    }

    private List<String> requiredFields(String action) {
        return switch (action) {
            case "issue", "dispatch", "receive", "accept" -> List.of("expectedVersion", "lines", "Idempotency-Key");
            case "approve", "close", "cancel", "return-for-correction" -> List.of("expectedVersion");
            case "record-discrepancy" -> List.of("expectedVersion", "lines", "reason", "evidence");
            case "release" -> List.of("expectedVersion", "quantity", "Idempotency-Key");
            case "cancel-remainder" -> List.of("expectedVersion", "reason", "Idempotency-Key");
            default -> List.of();
        };
    }

    private String actionImpact(String action) {
        return switch (action) {
            case "issue" -> "Posts a warehouse issue movement and decreases available stock";
            case "dispatch" -> "Moves source stock into company-owned transit";
            case "receive" -> "Posts destination receipt and clears in-transit quantity";
            case "accept" -> "Posts accepted return quantities and routes exceptions to quarantine";
            case "release" -> "Releases reserved stock back to available quantity";
            default -> "Changes document workflow state and writes an audit event";
        };
    }

    private List<AuditTimelineItem> audit(String entityType, UUID id, UUID companyId) {
        return auditLogRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                TenantContext.currentTenant(), companyId, entityType, id
            )
            .stream()
            .map(this::auditItem)
            .toList();
    }

    private AuditTimelineItem auditItem(AuditLog log) {
        return new AuditTimelineItem(log.getId(), log.getAction(), log.getFullName(), log.getDetails(), log.getOccurredAt());
    }

    private List<IntegrationImpactItem> integrations(UUID id, UUID companyId) {
        return integrationEventRepository
            .findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), companyId, id
            )
            .stream()
            .map(this::integrationItem)
            .toList();
    }

    private IntegrationImpactItem integrationItem(IntegrationEvent event) {
        return new IntegrationImpactItem(
            event.getId(),
            event.getEventType(),
            event.getStatus(),
            event.getRetryCount(),
            event.getLastError(),
            event.getCreatedAt()
        );
    }

    private List<RelatedDocument> issueRelated(IssueRequestResponse document) {
        ArrayList<RelatedDocument> related = new ArrayList<>();
        if (document.getStockMovementDocumentId() != null) {
            related.add(related("STOCK_MOVEMENT", document.getStockMovementDocumentId(), null, "Warehouse issue movement", "stock-movements"));
        }
        safe(document.getLines()).stream()
            .map(line -> line.getReservationId())
            .filter(Objects::nonNull)
            .distinct()
            .forEach(id -> related.add(related("RESERVATION", id, null, "Material reservation", "material-reservations")));
        return List.copyOf(related);
    }

    private List<RelatedDocument> reservationRelated(MaterialReservationResponse document) {
        if (document.getReferenceId() == null) {
            return List.of();
        }
        return List.of(related(
            display(document.getReferenceType(), "REFERENCE", document.getReferenceId()),
            document.getReferenceId(),
            null,
            display(document.getReferenceType(), "Source document", document.getReferenceId()),
            referenceRoute(document.getReferenceType())
        ));
    }

    private List<RelatedDocument> transferRelated(TransferDocumentResponse document) {
        ArrayList<RelatedDocument> related = new ArrayList<>();
        if (document.getDispatchMovementDocumentId() != null) {
            related.add(related("STOCK_MOVEMENT", document.getDispatchMovementDocumentId(), null, "Source issue movement", "stock-movements"));
        }
        if (document.getReceiveMovementDocumentId() != null) {
            related.add(related("STOCK_MOVEMENT", document.getReceiveMovementDocumentId(), null, "Destination receipt movement", "stock-movements"));
        }
        return List.copyOf(related);
    }

    private List<RelatedDocument> returnRelated(MaterialReturnResponse document) {
        ArrayList<RelatedDocument> related = new ArrayList<>();
        if (document.getIssueRequestId() != null) {
            related.add(related("ISSUE_REQUEST", document.getIssueRequestId(), document.getIssueRequestCode(), "Source issue request", "issue-requests"));
        }
        if (document.getStockMovementDocumentId() != null) {
            related.add(related("STOCK_MOVEMENT", document.getStockMovementDocumentId(), null, "Return movement", "stock-movements"));
        }
        return List.copyOf(related);
    }

    private RelatedDocument related(String type, UUID id, String code, String name, String module) {
        return new RelatedDocument(type, id, code, name, "/app/" + module + "?document=" + id);
    }

    private EntityRef warehouse(UUID id, String name) {
        return id == null ? null : new EntityRef(id, null, display(name, "Warehouse", id), "ACTIVE");
    }

    private String firstAction(List<String> actions) {
        return safe(actions).stream().findFirst().orElse(null);
    }

    private String ownerForIssue(String status) {
        return switch (Objects.requireNonNullElse(status, "")) {
            case "DRAFT" -> "DEPARTMENT_WORKER";
            case "SUBMITTED" -> "DEPARTMENT_HEAD";
            case "APPROVED", "RESERVED", "PARTIALLY_ISSUED" -> "WAREHOUSE_OPERATOR";
            default -> "WAREHOUSE_MANAGER";
        };
    }

    private String defaultOwner(DocumentType type) {
        return type == DocumentType.ISSUE_REQUEST ? "WAREHOUSE_OPERATOR" : "WAREHOUSE_OPERATOR";
    }

    private String route(DocumentType type) {
        return switch (type) {
            case ISSUE_REQUEST -> "/app/issue-requests";
            case RESERVATION -> "/app/material-reservations";
            case TRANSFER -> "/app/stock-transfers";
            case RETURN -> "/app/material-returns";
        };
    }

    private String referenceRoute(String referenceType) {
        if (referenceType == null) {
            return "records";
        }
        return switch (referenceType.toUpperCase(Locale.ROOT)) {
            case "ISSUEREQUEST", "ISSUE_REQUEST" -> "issue-requests";
            case "DEMANDPLANITEM", "DEMAND_PLAN_ITEM" -> "demand-plan-items";
            default -> "records";
        };
    }

    private List<String> activeStatuses(DocumentType type) {
        return switch (type) {
            case ISSUE_REQUEST -> List.of("DRAFT", "SUBMITTED", "APPROVED", "RESERVED", "PARTIALLY_ISSUED", "ISSUED");
            case RESERVATION -> List.of("ACTIVE", "PARTIALLY_RELEASED");
            case TRANSFER -> List.of("DRAFT", "SUBMITTED", "APPROVED", "IN_TRANSIT", "DISCREPANCY_REVIEW", "RECEIVED");
            case RETURN -> List.of("DRAFT", "SUBMITTED", "INSPECTION");
        };
    }

    private boolean terminal(DocumentType type, String status) {
        if (status == null) {
            return false;
        }
        return switch (type) {
            case ISSUE_REQUEST -> List.of("CLOSED", "REJECTED", "CANCELLED").contains(status);
            case RESERVATION -> List.of("RELEASED", "CONSUMED", "EXPIRED", "CANCELLED").contains(status);
            case TRANSFER -> List.of("CLOSED", "CANCELLED").contains(status);
            case RETURN -> List.of("POSTED", "REJECTED", "CANCELLED").contains(status);
        };
    }

    private boolean hasAnyRole(List<String> requiredRoles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", "").toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        return requiredRoles.stream().anyMatch(roles::contains);
    }

    private IssueRequestCostObjectType parseCostObjectType(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Cost object type is required");
        }
        try {
            return IssueRequestCostObjectType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unsupported cost object type: " + value);
        }
    }

    private SelectorOption userOption(User user) {
        String code = display(user.getCode(), user.getUsername(), user.getId());
        String name = display(user.getFullName(), user.getName(), user.getId());
        return new SelectorOption(user.getId(), code, name, name, user.isEnabled() ? "ACTIVE" : "INACTIVE");
    }

    private SelectorOption departmentOption(Department department) {
        String name = display(department.getName(), "Department", department.getId());
        return new SelectorOption(department.getId(), department.getCode(), name, name, department.getStatus());
    }

    private SelectorOption demandItemOption(DemandPlanItem item, IssueRequestCostObjectType type) {
        String costObjectCode = type == IssueRequestCostObjectType.PROJECT
            ? item.getProjectCode()
            : item.getRepairOrderCode();
        String name = display(item.getName(), type == IssueRequestCostObjectType.PROJECT ? "Project" : "Repair order", item.getId());
        return new SelectorOption(item.getId(), costObjectCode, name, name, item.getStatus());
    }

    private String normalizedSearch(String search) {
        return search == null || search.isBlank() ? null : search.trim();
    }

    private String dataScope() {
        return "COMPANY:" + CompanyContext.currentCompany();
    }

    private String display(String preferred, String fallback, UUID id) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return id == null ? "—" : id.toString();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
