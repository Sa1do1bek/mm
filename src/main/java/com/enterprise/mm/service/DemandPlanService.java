package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.payload.DemandManagementDtos.DemandPurchaseRequestResult;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanResponse;

import com.enterprise.mm.payload.DemandPlanUpdateRequest;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DemandPlanItemMapper;
import com.enterprise.mm.mapper.DemandPlanMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanItemPurchaseRequestAllocation;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.DemandPlanApprovalAdapter;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.DemandPlanSearchRequest;

@Service
public class DemandPlanService {
    private final DemandPlanRepository repository;
    private final DemandPlanMapper mapper;
    private final DemandPlanItemMapper itemMapper;
    private final String entityName = "DemandPlan";
    private final BudgetItemRepository budgetItemRepository;
    private final DemandPlanItemRepository itemRepository;
    private final DemandPlanningCycleRepository demandPlanningCycleRepository;
    private final DepartmentRepository departmentRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final DemandPlanItemPurchaseRequestAllocationRepository demandItemPrAllocationRepository;
    private final MaterialRepository materialRepository;
    private final DemandPlanItemService demandPlanItemService;
    private final IntegrationEventService integrationEventService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final ApprovalEngineService approvalEngineService;
    private final DemandPlanActionPolicy actionPolicy;
    private final DemandPlanNamingService demandPlanNamingService;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    public DemandPlanService(
        DemandPlanRepository repository,
        DemandPlanMapper mapper,
        DemandPlanItemMapper itemMapper,
        BudgetItemRepository budgetItemRepository,
        DemandPlanItemRepository itemRepository,
        DemandPlanningCycleRepository demandPlanningCycleRepository,
        DepartmentRepository departmentRepository,
        PurchaseRequestRepository purchaseRequestRepository,
        PurchaseRequestItemRepository purchaseRequestItemRepository,
        DemandPlanItemPurchaseRequestAllocationRepository demandItemPrAllocationRepository,
        MaterialRepository materialRepository,
        DemandPlanItemService demandPlanItemService,
        IntegrationEventService integrationEventService,
        SegregationOfDutyRuleService segregationOfDutyRuleService,
        AuditService auditService,
        RoleDataScopeService roleDataScopeService,
        ApprovalEngineService approvalEngineService,
        DemandPlanActionPolicy actionPolicy,
        DemandPlanNamingService demandPlanNamingService,
        ProcurementMaterialTypeSnapshotService materialTypeSnapshotService
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.budgetItemRepository = budgetItemRepository;
        this.itemRepository = itemRepository;
        this.demandPlanningCycleRepository = demandPlanningCycleRepository;
        this.departmentRepository = departmentRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.purchaseRequestItemRepository = purchaseRequestItemRepository;
        this.demandItemPrAllocationRepository = demandItemPrAllocationRepository;
        this.materialRepository = materialRepository;
        this.demandPlanItemService = demandPlanItemService;
        this.integrationEventService = integrationEventService;
        this.segregationOfDutyRuleService = segregationOfDutyRuleService;
        this.auditService = auditService;
        this.roleDataScopeService = roleDataScopeService;
        this.approvalEngineService = approvalEngineService;
        this.actionPolicy = actionPolicy;
        this.demandPlanNamingService = demandPlanNamingService;
        this.materialTypeSnapshotService = materialTypeSnapshotService;
    }
    protected void beforeCreate(DemandPlan entity, DemandPlanCreateRequest dto) {
        applyAuthoritativeCreateDepartment(entity, dto);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getOwnerUsername() == null || entity.getOwnerUsername().isBlank()) {
            entity.setOwnerUsername(currentUsername());
        }

        entity.setStatus(DemandPlanStatus.DRAFT.name());
        applyPlanningCycle(entity);
        normalize(entity);
        demandPlanNamingService.applyCanonicalName(entity);
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(idOf(entity.getDepartment()), "Demand plan");
    }
    protected void beforeUpdate(DemandPlan entity, DemandPlanUpdateRequest dto) {
        applyPlanningCycle(entity);
        normalize(entity);
        demandPlanNamingService.applyCanonicalName(entity);
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(idOf(entity.getDepartment()), "Demand plan");
    }
    @Transactional
    public DemandPlanResponse update(UUID id, DemandPlanUpdateRequest dto) {
        DemandPlan current = findEntity(id);
        actionPolicy.requireActionAllowed(current, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.EDIT);
        if (!DemandPlanStatus.DRAFT.name().equals(current.getStatus())
            && !DemandPlanStatus.COLLECTING.name().equals(current.getStatus())) {
            throw new BusinessException("Demand plan must be DRAFT or COLLECTING to be edited, current status is " + current.getStatus());
        }
        dto.setStatus(current.getStatus());
        dto.setDepartmentId(idOf(current.getDepartment()));
        dto.setConsolidatedAt(current.getConsolidatedAt());
        dto.setApprovedAt(current.getApprovedAt());
        resolveBudgetItem(dto);
        DemandPlan entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        recalculateTotals(entity, dto);
        beforeUpdate(entity, dto);
        demandPlanItemService.validateForPlanRequestType(entity);
        DemandPlan saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), true);
        demandPlanItemService.validateForPlanRequestType(saved);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponseWithItems(saved);
    }
    @Transactional
    public DemandPlanResponse startCollection(UUID id) {
        DemandPlan plan = findEntity(id);
        actionPolicy.requireActionAllowed(plan, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.START_COLLECTION);
        requireStatus(plan, DemandPlanStatus.DRAFT);
        plan.setStatus(DemandPlanStatus.COLLECTING.name());
        auditService.log("DemandPlan", id, "START_COLLECTION", plan.getCode(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public DemandPlanResponse submit(UUID id) {
        DemandPlan plan = findEntity(id);
        actionPolicy.requireActionAllowed(plan, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.SUBMIT);
        demandPlanItemService.validateForPlanRequestType(plan);
        approvalEngineService.start(DemandPlanApprovalAdapter.MODULE_KEY, id);
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public DemandPlanResponse approve(UUID id, String comment) {
        throw new BusinessException("Demand plan approval must be completed through approval task endpoints");
    }
    @Transactional
    public DemandPlanResponse activate(UUID id) {
        DemandPlan plan = findEntity(id);
        actionPolicy.requireActionAllowed(plan, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.ACTIVATE);
        requireStatus(plan, DemandPlanStatus.APPROVED);
        plan.setStatus(DemandPlanStatus.ACTIVE.name());
        auditService.log("DemandPlan", id, "ACTIVATE", plan.getCode(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public DemandPlanResponse close(UUID id) {
        DemandPlan plan = findEntity(id);
        actionPolicy.requireActionAllowed(plan, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.CLOSE);
        if (!DemandPlanStatus.ACTIVE.name().equals(plan.getStatus()) && !DemandPlanStatus.APPROVED.name().equals(plan.getStatus())) {
            throw new BusinessException("Only APPROVED or ACTIVE demand plan can be closed");
        }
        plan.setStatus(DemandPlanStatus.CLOSED.name());
        auditService.log("DemandPlan", id, "CLOSE", plan.getCode(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public DemandPlanResponse cancel(UUID id) {
        DemandPlan plan = findEntity(id);
        actionPolicy.requireActionAllowed(plan, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.CANCEL);
        if (DemandPlanStatus.CLOSED.name().equals(plan.getStatus())) {
            throw new BusinessException("Closed demand plan cannot be cancelled");
        }
        plan.setStatus(DemandPlanStatus.CANCELLED.name());
        auditService.log("DemandPlan", id, "CANCEL", plan.getCode(), null, null);
        return mapper.toResponse(repository.save(plan));
    }
    @Transactional
    public DemandPurchaseRequestResult createPurchaseRequests(UUID id) {
        repository.lockSource(id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new BusinessException("Demand plan is unavailable"));
        DemandPlan plan = findEntity(id);
        actionPolicy.requireActionAllowed(plan, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.CREATE_PURCHASE_REQUEST);
        if (!DemandPlanStatus.APPROVED.name().equals(plan.getStatus()) && !DemandPlanStatus.ACTIVE.name().equals(plan.getStatus())) {
            throw new BusinessException("Only APPROVED or ACTIVE demand plan can create purchase requests");
        }
        requireCycleReadyForPurchaseRequests(plan);
        List<DemandPlanItem> sourceItems = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), id).stream()
            .filter(this::isReadyForPurchaseRequest)
            .toList();
        if (sourceItems.isEmpty()) {
            List<UUID> existingRequestIds = purchaseRequestRepository
                .findByTenantIdAndCompanyIdAndDemandPlanIdAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    id
                ).stream()
                .map(PurchaseRequest::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (!existingRequestIds.isEmpty()) {
                return new DemandPurchaseRequestResult(id, existingRequestIds.size(), existingRequestIds);
            }
            boolean coveredByStock = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), id)
                .stream().allMatch(item -> nvl(item.getApprovedQuantity()).signum() == 0
                    && nvl(item.getStockFulfillmentQuantity()).signum() > 0
                    && List.of("APPROVED", "RESERVED").contains(item.getStatus()));
            if (coveredByStock) return new DemandPurchaseRequestResult(id, 0, List.of());
            throw new BusinessException("No approved demand items ready for purchase request");
        }
        Map<DemandItemPurchaseRequestKey, List<DemandPlanItem>> grouped = new HashMap<>();
        for (DemandPlanItem item : sourceItems) {
            if (item.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(item, plan.getRequestType());
            }
            grouped.computeIfAbsent(
                new DemandItemPurchaseRequestKey(
                    item.getMaterialId(),
                    item.getMaterialTypeSnapshot(),
                    item.getBudgetItemId(),
                    currency(item.getCurrencyCode()),
                    item.fulfillmentDate()
                ),
                ignored -> new ArrayList<>()
            ).add(item);
        }
        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode("PR-" + plan.getCode());
        pr.setDescription(plan.getDescription());
        pr.setName(BusinessNameGenerator.workflowDocumentName("PurchaseRequest", plan.getName()));
        pr.setStatus(PurchaseRequestStatus.DRAFT.name());
        pr.setDepartmentId(idOf(plan.getDepartment()));
        pr.setDemandPlanId(plan.getId());
        pr.setRequestType(plan.getRequestType());
        pr.setRequestedBy(plan.getOwnerUsername());
        pr.setRequiredDate(resolvePurchaseRequestRequiredDate(plan, sourceItems));
        pr.setCurrencyCode(currency(plan));
        pr.setTotalAmount(sourceItems.stream().map(DemandPlanItem::getEstimatedAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        PurchaseRequest savedPr = purchaseRequestRepository.save(pr);
        for (Map.Entry<DemandItemPurchaseRequestKey, List<DemandPlanItem>> entry : grouped.entrySet()) {
            DemandItemPurchaseRequestKey key = entry.getKey();
            List<DemandPlanItem> groupItems = entry.getValue();
            BigDecimal quantity = groupItems.stream().map(DemandPlanItem::getApprovedQuantity).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal amount = groupItems.stream().map(DemandPlanItem::getEstimatedAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            PurchaseRequestItem item = new PurchaseRequestItem();
            item.setCode("PRI-" + UUID.randomUUID().toString().substring(0, 8));
            item.setName("Потребность " + materialName(key.materialId()));
            item.setStatus("OPEN");
            item.setPurchaseRequestId(savedPr.getId());
            item.setMaterialId(key.materialId());
            item.setMaterialTypeSnapshot(key.materialTypeSnapshot());
            item.setUnit(materialStockUnit(key.materialId()));
            item.setBudgetItemId(key.budgetItemId());
            item.setRequiredDate(key.requiredDate());
            item.setQuantity(quantity);
            item.setEstimatedPrice(unitPrice(amount, quantity));
            PurchaseRequestItem savedItem = purchaseRequestItemRepository.save(item);
            for (DemandPlanItem source : groupItems) {
                saveDemandItemPurchaseRequestAllocation(source, savedItem);
                source.setPurchaseRequestItemId(savedItem.getId());
                source.setStatus(DemandPlanItemStatus.CONVERTED_TO_PR.name());
                itemRepository.save(source);
            }
        }
        integrationEventService.emit("MM_DEMAND_PR_CREATED", "PurchaseRequest", savedPr.getId(), Map.of(
            "demandPlanId", plan.getId(),
            "purchaseRequestId", savedPr.getId(),
            "amount", nvl(savedPr.getTotalAmount()),
            "currency", currency(savedPr)
        ));
        linkPurchaseRequestApprovalIfConfigured(savedPr);
        auditService.log("PurchaseRequest", savedPr.getId(), "CREATE_FROM_DEMAND_PLAN", plan.getId().toString(), null, null);
        return new DemandPurchaseRequestResult(id, 1, List.of(savedPr.getId()));
    }

    private void saveDemandItemPurchaseRequestAllocation(DemandPlanItem source, PurchaseRequestItem purchaseRequestItem) {
        if (source == null || source.getId() == null || purchaseRequestItem == null || purchaseRequestItem.getId() == null) {
            return;
        }
        DemandPlanItemPurchaseRequestAllocation allocation = new DemandPlanItemPurchaseRequestAllocation();
        allocation.setCode("DPI-PR-" + UUID.randomUUID().toString().substring(0, 8));
        allocation.setName(BusinessDisplayNamePolicy.requireHumanName(
            "Demand item purchase request allocation",
            "Demand item to PR allocation",
            null
        ));
        allocation.setStatus("ACTIVE");
        allocation.setCompanyId(source.getCompanyId());
        allocation.setDemandPlanItemId(source.getId());
        allocation.setPurchaseRequestItemId(purchaseRequestItem.getId());
        allocation.setAllocatedQuantity(nvl(source.getApprovedQuantity()));
        allocation.setAllocatedAmount(nvl(source.getEstimatedAmount()));
        allocation.setCurrencyCode(currency(source.getCurrencyCode()));
        demandItemPrAllocationRepository.save(allocation);
    }

    private void linkPurchaseRequestApprovalIfConfigured(PurchaseRequest request) {
        var requestType = request.getRequestType() == null
            ? RequestType.GOODS
            : request.getRequestType();
        if (!approvalEngineService.hasActiveFlow(
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            request.getCompanyId(),
            request.getDepartmentId(),
            requestType
        )) {
            return;
        }
        approvalEngineService.linkItem(
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            request.getId(),
            request.getCompanyId(),
            request.getCode()
        );
    }
    private void normalize(DemandPlan plan) {
        if (plan.getPlanYear() == null) {
            plan.setPlanYear(Year.now().getValue());
        }
        if (plan.getPeriodType() == null || plan.getPeriodType().isBlank()) {
            plan.setPeriodType(DemandPeriodType.YEAR.name());
        }
        DemandPeriodType periodType = DemandPeriodType.valueOf(plan.getPeriodType());
        if (periodType == DemandPeriodType.QUARTER) {
            if (plan.getQuarter() == null || plan.getQuarter() < 1 || plan.getQuarter() > 4) {
                throw new BusinessException("Quarter must be between 1 and 4");
            }
            int startMonth = (plan.getQuarter() - 1) * 3 + 1;
            plan.setPeriodStart(LocalDate.of(plan.getPlanYear(), startMonth, 1));
            plan.setPeriodEnd(plan.getPeriodStart().plusMonths(3).minusDays(1));
        } else {
            plan.setQuarter(null);
            plan.setPeriodStart(LocalDate.of(plan.getPlanYear(), 1, 1));
            plan.setPeriodEnd(LocalDate.of(plan.getPlanYear(), 12, 31));
        }
        if (plan.getCurrencyCode() == null || plan.getCurrencyCode().isBlank()) {
            plan.setCurrencyCode("UZS");
        }
        plan.setTotalPlannedAmount(nvl(plan.getTotalPlannedAmount()));
        plan.setTotalApprovedAmount(nvl(plan.getTotalApprovedAmount()));
        plan.setTotalReservedQuantity(nvl(plan.getTotalReservedQuantity()));
    }
    private void applyPlanningCycle(DemandPlan plan) {
        UUID planningCycleId = idOf(plan.getPlanningCycle());
        if (planningCycleId == null) {
            return;
        }
        DemandPlanningCycle cycle = plan.getPlanningCycle() != null ? plan.getPlanningCycle() : loadCycle(planningCycleId);
        if (DemandPlanningCycleStatus.CLOSED.name().equals(cycle.getStatus()) || DemandPlanningCycleStatus.CANCELLED.name().equals(cycle.getStatus())) {
            throw new BusinessException("Demand plan cannot be linked to CLOSED or CANCELLED planning cycle");
        }
        plan.setPlanYear(cycle.getPlanYear());
        plan.setPeriodType(cycle.getPeriodType());
        plan.setQuarter(cycle.getQuarter());
        plan.setPeriodStart(cycle.getPeriodStart());
        plan.setPeriodEnd(cycle.getPeriodEnd());
        if (plan.getCurrencyCode() == null || plan.getCurrencyCode().isBlank()) {
            plan.setCurrencyCode(cycle.getCurrencyCode());
        }
        if (plan.getOwnerUsername() == null || plan.getOwnerUsername().isBlank()) {
            plan.setOwnerUsername(cycle.getOwnerUsername());
        }
    }
    private DemandPlanningCycle loadCycle(UUID id) {
        return demandPlanningCycleRepository.findByIdAndIsDeletedFalse(id)
            .filter(cycle -> !cycle.isDeleted() && TenantContext.currentTenant().equals(cycle.getTenantId()))
            .orElseThrow(() -> new BusinessException("Demand planning cycle not found: " + id));
    }
    private void requireCycleReadyForPurchaseRequests(DemandPlan plan) {
        UUID planningCycleId = idOf(plan.getPlanningCycle());
        if (planningCycleId == null) {
            return;
        }
        DemandPlanningCycle cycle = plan.getPlanningCycle() != null ? plan.getPlanningCycle() : loadCycle(planningCycleId);
        if (!DemandPlanningCycleStatus.BUDGET_APPROVED.name().equals(cycle.getStatus()) && !DemandPlanningCycleStatus.ACTIVE.name().equals(cycle.getStatus())) {
            throw new BusinessException("Linked planning cycle must be BUDGET_APPROVED or ACTIVE before purchase requests are created");
        }
    }
    private void requireStatus(DemandPlan plan, DemandPlanStatus status) {
        if (!status.name().equals(plan.getStatus())) {
            throw new BusinessException("Demand plan must be " + status + ", current status is " + plan.getStatus());
        }
    }
    private UUID idOf(com.enterprise.mm.common.BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }
    private boolean isSubmittedOrApproved(DemandPlanItem item) {
        return DemandPlanItemStatus.SUBMITTED.name().equals(item.getStatus())
            || DemandPlanItemStatus.APPROVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.RESERVED.name().equals(item.getStatus());
    }
    private boolean isReadyForPurchaseRequest(DemandPlanItem item) {
        return item.getPurchaseRequestItemId() == null
            && (DemandPlanItemStatus.APPROVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.RESERVED.name().equals(item.getStatus()))
            && nvl(item.getApprovedQuantity()).signum() > 0;
    }
    private String materialName(UUID materialId) {
        return materialRepository.findByIdAndIsDeletedFalse(materialId).map(Material::getName).orElse(materialId.toString());
    }
    private String materialStockUnit(UUID materialId) {
        return materialRepository.findByIdAndIsDeletedFalse(materialId)
            .map(Material::getUnitOfMeasure)
            .map(unit -> unit.getCode())
            .orElse(null);
    }
    private BigDecimal unitPrice(BigDecimal amount, BigDecimal quantity) {
        if (amount == null || quantity == null || quantity.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(quantity, 4, RoundingMode.HALF_UP);
    }
    private String currency(DemandPlan plan) {
        return currency(plan.getCurrencyCode());
    }
    private String currency(PurchaseRequest request) {
        return currency(request.getCurrencyCode());
    }
    private String currency(String currencyCode) {
        return currencyCode == null || currencyCode.isBlank() ? "UZS" : currencyCode;
    }

    private DemandPlanResponse toResponseWithItems(DemandPlan entity) {
        DemandPlanResponse response = mapper.toResponse(entity);
        List<DemandPlanItemResponse> itemResponses = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getId())
            .stream()
            .sorted(Comparator.comparing(DemandPlanItem::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(itemMapper::toResponse)
            .toList();
        demandPlanItemService.attachMonthsToResponses(itemResponses);
        response.setItems(itemResponses);
        return response;
    }


    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BusinessException("Authenticated user is required to create demand plan");
        }
        return authentication.getName();
    }

    private record DemandItemPurchaseRequestKey(
        UUID materialId,
        MaterialType materialTypeSnapshot,
        UUID budgetItemId,
        String currencyCode,
        LocalDate requiredDate
    ) {
    }
    @Transactional(readOnly = true)
    public Page<DemandPlanResponse> search(DemandPlanSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            DemandPlan.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!dataScope.hasDocumentScope()) {
            return records.map(this::toResponseWithItems);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> roleDataScopeService.canAccessDocument(
                entity.getCreatedBy(),
                entity.getOwnerUsername(),
                entity.getDepartment() == null ? null : entity.getDepartment().getId()
            ),
            this::toResponseWithItems
        );
    }
    @Transactional(readOnly = true)
    public DemandPlanResponse get(UUID id) {
        return toResponseWithItems(findEntity(id));
    }
    @Transactional
    public DemandPlanResponse create(DemandPlanCreateRequest dto) {
        return createWithApprovalWarning(dto).response();
    }

    @Transactional
    public DemandPlanCreateResult createWithApprovalWarning(DemandPlanCreateRequest dto) {
        resolveBudgetItem(dto);
        DemandPlan entity = mapper.toEntity(dto);
        recalculateTotals(entity, dto);
        beforeCreate(entity, dto);
        DemandPlan saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), false);
        String warning = linkApprovalFlowOrWarning(saved);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return new DemandPlanCreateResult(toResponseWithItems(saved), warning);
    }
    @Transactional
    public void softDelete(UUID id) {
        DemandPlan entity = findEntity(id);
        actionPolicy.requireActionAllowed(entity, com.enterprise.mm.enums.DemandPlanWorkspaceActionKey.DELETE);
        String oldSnapshot = auditService.snapshot(entity);
        if (!DemandPlanStatus.DRAFT.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT demand plan can be deleted");
        }
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public DemandPlanResponse restore(UUID id) {
        DemandPlan entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        UUID departmentId = entity.getDepartment() == null ? null : entity.getDepartment().getId();
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), entity.getOwnerUsername(), departmentId)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        DemandPlan saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected DemandPlan findEntity(UUID id) {
        DemandPlan entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        UUID departmentId = entity.getDepartment() == null ? null : entity.getDepartment().getId();
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), entity.getOwnerUsername(), departmentId)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private String linkApprovalFlowOrWarning(DemandPlan plan) {
        UUID departmentId = idOf(plan.getDepartment());
        if (!approvalEngineService.hasActiveFlow(DemandPlanApprovalAdapter.MODULE_KEY, plan.getCompanyId(), departmentId, plan.getRequestType())) {
            return "warning: No active approval flow configured for Demand Plan. Configure module DEMAND_PLAN before submitting this plan.";
        }
        approvalEngineService.linkItem(DemandPlanApprovalAdapter.MODULE_KEY, plan.getId(), plan.getCompanyId(), plan.getCode());
        return null;
    }

    private void applyAuthoritativeCreateDepartment(DemandPlan entity, DemandPlanCreateRequest dto) {
        Set<String> roles = currentRoles();
        boolean admin = roles.contains("ADMIN");
        boolean departmentOriginator = roles.contains("DEPARTMENT_HEAD") || roles.contains("DEPARTMENT_WORKER");
        if (!admin && !departmentOriginator) {
            throw new BusinessException("Only department head or department worker can create demand plan");
        }
        if (admin || !departmentOriginator) {
            return;
        }
        UUID authenticatedDepartmentId = roleDataScopeService.currentScope().departmentId();
        if (authenticatedDepartmentId == null) {
            throw new BusinessException("Your user account must be assigned to a department before creating a demand plan");
        }
        UUID payloadDepartmentId = dto == null ? null : dto.getDepartmentId();
        if (payloadDepartmentId != null && !Objects.equals(payloadDepartmentId, authenticatedDepartmentId)) {
            throw new BusinessException("Demand plan department must match the authenticated user's department");
        }
        Department department = departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                authenticatedDepartmentId,
                TenantContext.currentTenant(),
                CompanyContext.currentCompany()
            )
            .orElseThrow(() -> new BusinessException("Authenticated department is not available"));
        entity.setDepartment(department);
    }

    private Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
            .map(authority -> authority == null ? null : authority.getAuthority())
            .filter(Objects::nonNull)
            .map(value -> value.replaceFirst("^ROLE_", "").toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    }

    public record DemandPlanCreateResult(DemandPlanResponse response, String warning) {
    }

    private void resolveBudgetItem(DemandPlanCreateRequest dto) {
        if (dto == null || dto.getBudgetItemId() != null) {
            return;
        }
        String budgetItemCode = dto.getBudgetItemCode();
        if (budgetItemCode == null || budgetItemCode.isBlank()) {
            return;
        }
        UUID budgetItemId = budgetItemRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), budgetItemCode)
            .orElseThrow(() -> new BusinessException("Budget item not found: " + budgetItemCode))
            .getId();
        dto.setBudgetItemId(budgetItemId);
    }

    private void saveSubmittedItems(UUID demandPlanId, List<DemandPlanItemCreateRequest> items, boolean replaceExisting) {
        if (items == null) {
            return;
        }
        if (replaceExisting) {
            List<DemandPlanItem> existingItems = itemRepository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), demandPlanId);
            if (existingItems.stream().anyMatch(item -> !DemandPlanItemStatus.DRAFT.name().equals(item.getStatus()))) {
                throw new BusinessException("Demand plan nested items can be replaced only while all existing items are DRAFT");
            }
            if (existingItems.stream().anyMatch(item -> item.getNeedByDate() != null)
                && items.stream().anyMatch(item -> !item.hasNeedByDate())) {
                // Replacement DTOs have no stable child IDs. Do not guess which
                // old deadline belongs to an older client's replacement rows.
                throw new BusinessException(
                    "Include needByDate for every replacement item; use explicit null to clear a deadline",
                    "demandPlanItem.needByDate.replacementRequired"
                );
            }
            existingItems.forEach(item -> {
                item.softDelete();
                itemRepository.save(item);
            });
        }
        items.forEach(item -> {
            item.setDemandPlanId(demandPlanId);
            demandPlanItemService.create(item);
        });
    }

    private void recalculateTotals(DemandPlan entity, DemandPlanCreateRequest dto) {
        if (dto.getItems() == null) {
            return;
        }
        BigDecimal planned = dto.getItems().stream()
            .map(item -> nvl(item.getEstimatedAmount()).signum() == 0
                ? nvl(item.getRequestedQuantity()).multiply(nvl(item.getEstimatedUnitPrice()))
                : nvl(item.getEstimatedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal approved = dto.getItems().stream()
            .map(item -> nvl(item.getApprovedQuantity()).multiply(nvl(item.getEstimatedUnitPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reserved = dto.getItems().stream()
            .map(DemandPlanItemCreateRequest::getReservedQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        entity.setTotalPlannedAmount(planned);
        entity.setTotalApprovedAmount(approved);
        entity.setTotalReservedQuantity(reserved);
    }

    /**
     * The header summarizes the earliest fulfillment date; each PR line retains
     * its own date. Monthly-only legacy demand keeps its existing derived date.
     */
    private LocalDate resolvePurchaseRequestRequiredDate(DemandPlan plan, List<DemandPlanItem> sourceItems) {
        return sourceItems.stream()
            .map(DemandPlanItem::fulfillmentDate)
            .filter(Objects::nonNull)
            .min(LocalDate::compareTo)
            .orElseGet(() -> plan.getPeriodStart() != null && plan.getPeriodStart().isAfter(LocalDate.now())
                ? plan.getPeriodStart()
                : LocalDate.now().plusDays(14));
    }
}
