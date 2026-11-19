package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.entity.InventoryOrder;
import com.enterprise.mm.entity.InventoryOrderAllocation;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.InventoryDocumentCreateRequest;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.InventoryOrderAllocationRepository;
import com.enterprise.mm.repository.InventoryOrderRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryOrderService {
    private static final Set<String> TASK_TYPES = Set.of("STOCK_COUNT", "FIXED_ASSET_CHECK", "FINANCIAL_CHECK", "CONSTRUCTION_CHECK");
    private final InventoryOrderRepository orders;
    private final InventoryOrderAllocationRepository allocations;
    private final AiAnalysisJobRepository jobs;
    private final InventoryAiDraftService drafts;
    private final InventoryDocumentService documents;
    private final InventoryAccessService inventoryAccess;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final InventoryOrderCommissionMatchService commissionMatches;
    private final InventoryOrderObjectMatchService objectMatches;
    private final AttachmentService attachments;
    private final UserRepository users;
    private final CommissionRepository commissions;

    public record CreateOrderRequest(UUID draftId, UUID analysisJobId) {}
    public record AllocationRequest(UUID allocationId, List<String> sourceKeys, String taskType,
        InventoryDocumentCreateRequest count, UUID commissionId, String notes, boolean allowSplit) {}
    public record AllocationResponse(UUID id, List<String> sourceKeys, String taskType, String status,
        UUID inventoryDocumentId, UUID commissionId, String commissionName, String notes, String resultNote, UUID approverUserId,
        LocalDateTime submittedAt, LocalDateTime approvedAt, LocalDateTime acceptedAt, boolean canApprove) {}
    public record TaskReviewRequest(String resultNote, UUID approverUserId) {}
    public record AssignedTask(UUID orderId, AllocationResponse task) {}
    public record TaskDetail(AllocationResponse task, String orderNumber, List<JsonNode> sourceBands) {}
    public record OrderResponse(UUID id, UUID sourceDraftId, UUID analysisJobId, JsonNode analysis,
        LocalDateTime createdAt, List<AllocationResponse> allocations) {}
    public record OrderSummary(UUID id, String orderNumber, String title, String orderDate, String stockAsOfDate,
        String plannedStartDate, String plannedEndDate, String analysisStatus, int analysisVersion, String scopeMode,
        int chairAssignmentsCount, int commissionRostersCount, int workItemsCount,
        LocalDateTime createdAt, String ownerUsername, int taskCount, int countCount) {}
    public record OrderPage(int offset, int limit, int total, List<JsonNode> items) {}

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        if (request == null || request.draftId() == null || request.analysisJobId() == null)
            throw new BadRequestException("Draft and completed order analysis are required");
        drafts.requireOwner(request.draftId(), inventoryAccess.username());
        var existing = orders.findBySourceDraftIdAndTenantIdAndCompanyId(request.draftId(), tenant(), company());
        if (existing.isPresent()) {
            if (!existing.get().getAnalysisJobId().equals(request.analysisJobId()))
                throw new ConflictException("This source draft is already bound to a different order analysis");
            return response(existing.get());
        }
        jdbc.queryForObject("SELECT id FROM inventory_ai_drafts WHERE id = ? AND tenant_id = ? AND company_id = ? FOR UPDATE",
            UUID.class, request.draftId(), tenant(), company());
        existing = orders.findBySourceDraftIdAndTenantIdAndCompanyId(request.draftId(), tenant(), company());
        if (existing.isPresent()) {
            if (!existing.get().getAnalysisJobId().equals(request.analysisJobId()))
                throw new ConflictException("This source draft is already bound to a different order analysis");
            return response(existing.get());
        }
        var draft = drafts.requireOpen(request.draftId(), inventoryAccess.username());
        AiAnalysisJob job = jobs.findScoped(request.analysisJobId(), tenant(), company())
            .orElseThrow(() -> new NotFoundException("Order analysis not found"));
        if (job.getAnalysisType() != AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION
            || job.getStatus() != AiAnalysisJobStatus.COMPLETED || !request.draftId().equals(job.getEntityId())
            || !InventoryAiDraftService.ENTITY_TYPE.equals(job.getEntityType()))
            throw new BadRequestException("A completed analysis for these source files is required");
        JsonNode requestPayload = json(job.getRequestPayload());
        JsonNode result = json(job.getResultPayload());
        if (!Set.of(1, 2).contains(requestPayload.path("orderAnalysisVersion").asInt())
            || result.path("orderAnalysis").path("schemaVersion").asInt()
                != requestPayload.path("orderAnalysisVersion").asInt())
            throw new BadRequestException("The analysis does not contain a versioned inventory order");
        if (!inventoryAccess.hasRole("ADMIN")
            && requestPayload.path("orderAnalysisVersion").asInt() != 2)
            throw new AccessDeniedException("Non-admin users require department-scoped order analysis");
        if (!draft.getId().equals(request.draftId())) throw new BadRequestException("Source draft mismatch");
        InventoryOrder order = new InventoryOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId(tenant());
        order.setCompanyId(company());
        order.setOwnerUsername(inventoryAccess.username());
        order.setSourceDraftId(request.draftId());
        order.setAnalysisJobId(request.analysisJobId());
        order.setAnalysisPayload(job.getResultPayload());
        order.setCreatedAt(LocalDateTime.now());
        orders.saveAndFlush(order);
        drafts.bindToOrder(request.draftId(), order.getId());
        return response(order);
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> list() {
        return orders.findTop50ByTenantIdAndCompanyIdOrderByCreatedAtDesc(tenant(), company()).stream()
            .filter(this::canRead).map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> history(int page, int size, String search) {
        if (page < 0 || size < 1 || size > 100 || search != null && search.length() > 120)
            throw new BadRequestException("Invalid order history request");
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        boolean ownerOnly = !inventoryAccess.hasRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT");
        boolean v2Only = inventoryAccess.hasRole("DEPARTMENT_HEAD") && !inventoryAccess.hasRole("ADMIN");
        return orders.history(tenant(), company(), ownerOnly, inventoryAccess.username(), v2Only,
            query, pattern, PageRequest.of(page, size)).map(this::summary);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) { return response(requireVisible(id)); }

    @Transactional(readOnly = true)
    public OrderPage page(UUID id, String section, int offset, int limit, String search) {
        if (!Set.of("chair-assignments", "work-items", "commission-rosters").contains(section)
            || offset < 0 || limit < 1 || limit > 100 || search != null && search.length() > 120)
            throw new BadRequestException("Invalid order page request");
        JsonNode analysis = json(requireVisible(id).getAnalysisPayload());
        String field = switch (section) {
            case "chair-assignments" -> "chairAssignments";
            case "work-items" -> "workItems";
            default -> "commissionRosters";
        };
        JsonNode rows = analysis.path("orderAnalysis").path(field);
        if (!rows.isArray()) return new OrderPage(offset, limit, 0, List.of());
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<JsonNode> filtered = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!term.isEmpty() && !row.toString().toLowerCase(Locale.ROOT).contains(term)) continue;
            filtered.add(row);
        }
        if ("DEPARTMENTS".equals(analysis.path("scope").path("mode").asText()))
            filtered.sort(Comparator.comparing((JsonNode row) -> row.path("matchedDepartmentId").asText())
                .thenComparing(row -> row.path("itemNumber").asText())
                .thenComparing(row -> row.path("sourceKey").asText()));
        List<JsonNode> items = new ArrayList<>();
        for (int i = offset; i < Math.min(filtered.size(), (long) offset + limit); i++) items.add(filtered.get(i));
        return new OrderPage(offset, limit, filtered.size(), items);
    }

    @Transactional(readOnly = true)
    public List<AssignedTask> assignedTasks() {
        return allocations.findAssignedSubmitted(tenant(), company(), inventoryAccess.currentUserId()).stream()
            .map(task -> new AssignedTask(task.getOrderId(), allocation(task))).toList();
    }

    @Transactional(readOnly = true)
    public TaskDetail getTask(UUID orderId, UUID taskId) {
        var order = orders.findByIdAndTenantIdAndCompanyId(orderId, tenant(), company())
            .orElseThrow(() -> new NotFoundException("Inventory order not found"));
        var task = allocations.findByIdAndOrderId(taskId, orderId)
            .orElseThrow(() -> new NotFoundException("Inventory order task not found"));
        if (!canRead(order) && !inventoryAccess.currentUserId().equals(task.getApproverUserId()))
            throw new AccessDeniedException("Inventory order task access is restricted");
        JsonNode analysis = json(order.getAnalysisPayload());
        List<String> keys = readKeys(task.getSourceKeys());
        var bands = new ArrayList<JsonNode>();
        for (JsonNode item : analysis.path("orderAnalysis").path("workItems"))
            if (keys.contains(item.path("sourceKey").asText())) bands.add(item);
        return new TaskDetail(allocation(task), analysis.path("orderAnalysis").path("header").path("orderNumber").path("value").asText(null), bands);
    }

    @Transactional(readOnly = true)
    public List<InventoryOrderCommissionMatchService.Recommendation> commissionMatches(UUID id) {
        return commissionMatches.match(json(requireVisible(id).getAnalysisPayload()));
    }

    @Transactional(readOnly = true)
    public List<InventoryOrderObjectMatchService.BandMatch> objectMatches(UUID id) {
        return objectMatches.match(json(requireVisible(id).getAnalysisPayload()));
    }

    @Transactional
    public AllocationResponse createAllocation(UUID orderId, AllocationRequest request) {
        InventoryOrder order = orders.lockScoped(orderId, tenant(), company())
            .orElseThrow(() -> new NotFoundException("Inventory order not found"));
        if (!canRead(order)) throw new AccessDeniedException("Inventory order access is restricted");
        requirePlanner();
        if (request == null || request.allocationId() == null || request.sourceKeys() == null
            || request.sourceKeys().isEmpty() || request.sourceKeys().size() > 200
            || !TASK_TYPES.contains(request.taskType())) throw new BadRequestException("Invalid order task allocation");
        var keys = new ArrayList<>(new LinkedHashSet<>(request.sourceKeys()));
        if (keys.size() != request.sourceKeys().size() || keys.stream().anyMatch(key -> key == null || key.isBlank()))
            throw new BadRequestException("Duplicate or empty order band");
        if (request.count() != null) request.count().setSourceAiDraftId(null);
        String payload = write(request);
        var existingAllocation = allocations.findByIdAndOrderId(request.allocationId(), orderId);
        if (existingAllocation.isPresent()) {
            if (!json(existingAllocation.get().getRequestPayload()).equals(json(payload)))
                throw new ConflictException("Allocation ID was already used with different fields");
            return allocation(existingAllocation.get());
        }
        JsonNode analysis = json(order.getAnalysisPayload());
        var sourceCommissions = new LinkedHashSet<String>();
        for (String key : keys) {
            JsonNode source = null;
            for (JsonNode item : analysis.path("orderAnalysis").path("workItems"))
                if (key.equals(item.path("sourceKey").asText())) { source = item; break; }
            if (source == null || !contains(source.path("taskTypes"), request.taskType()))
                throw new BadRequestException("Task type is not supported by the selected source band");
            if (source.path("workingCommissionSourceKey").isTextual())
                sourceCommissions.add(source.path("workingCommissionSourceKey").asText());
            JsonNode rosterLinks = source.path("commissionRosterSourceKeys");
            if (rosterLinks.isArray()) {
                if ("STOCK_COUNT".equals(request.taskType()) && rosterLinks.size() > 1)
                    throw new BadRequestException("A band linked to multiple commissions requires a separate review");
                rosterLinks.forEach(link -> sourceCommissions.add(link.asText()));
            }
        }
        if ("STOCK_COUNT".equals(request.taskType()) && sourceCommissions.size() > 1)
            throw new BadRequestException("Bands with different working commissions require separate counts");
        if (!request.allowSplit()) for (var previous : allocations.findByOrderIdOrderByCreatedAtAsc(orderId)) {
            if (previous.getId().equals(request.allocationId()) || !previous.getTaskType().equals(request.taskType())) continue;
            if (readKeys(previous.getSourceKeys()).stream().anyMatch(keys::contains))
                throw new ConflictException("This order band already has a task of the same type");
        }
        if ("STOCK_COUNT".equals(request.taskType()) && request.count() == null)
            throw new BadRequestException("Reviewed count fields are required");
        if (!"STOCK_COUNT".equals(request.taskType()) && request.count() != null)
            throw new BadRequestException("A non-stock task cannot create a warehouse count");
        if (request.count() != null && request.commissionId() != null
            && !request.commissionId().equals(request.count().getCommissionId()))
            throw new BadRequestException("Task and count commission must match");
        UUID commissionId = "STOCK_COUNT".equals(request.taskType()) ? request.count().getCommissionId() : request.commissionId();
        if (commissionId == null) throw new BadRequestException("An ERP inventory commission must be selected for this task");
        commissions.findByIdAndIsDeletedFalse(commissionId)
            .filter(commission -> tenant().equals(commission.getTenantId()) && company().equals(commission.getCompanyId())
                && commission.getType() == CommissionType.INVENTORY && commission.isActive()
                && (commission.getValidFrom() == null || !commission.getValidFrom().isAfter(LocalDate.now()))
                && (commission.getValidTo() == null || !commission.getValidTo().isBefore(LocalDate.now())))
            .orElseThrow(() -> new BadRequestException("The selected inventory commission is unavailable in this company"));
        jdbc.update("""
            INSERT INTO inventory_order_allocations(id, order_id, source_keys, task_type, request_payload, commission_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING
            """, request.allocationId(), orderId, write(keys), request.taskType(), payload, commissionId);
        InventoryOrderAllocation allocation = allocations.lock(request.allocationId(), orderId)
            .orElseThrow(() -> new ConflictException("Allocation ID belongs to another order"));
        if (!json(allocation.getRequestPayload()).equals(json(payload)))
            throw new ConflictException("Allocation ID was already used with different fields");
        if (allocation.getInventoryDocumentId() == null && "STOCK_COUNT".equals(request.taskType())) {
            var count = documents.create(request.count());
            allocation.setInventoryDocumentId(count.getId());
            allocation.setStatus("COUNT_CREATED");
            allocations.saveAndFlush(allocation);
        }
        return allocation(allocation);
    }

    @Transactional
    public AllocationResponse reviewTask(UUID orderId, UUID taskId, TaskReviewRequest request) {
        requireVisible(orderId); requirePlanner();
        var task = task(orderId, taskId);
        if ("STOCK_COUNT".equals(task.getTaskType()) || !"OPEN".equals(task.getStatus()))
            throw new BadRequestException("Only an open non-stock task can be reviewed");
        if (request == null || request.resultNote() == null || request.resultNote().isBlank()
            || request.resultNote().length() > 4000 || request.approverUserId() == null)
            throw new BadRequestException("Result note and approver are required");
        users.findByIdAndIsDeletedFalse(request.approverUserId())
            .filter(user -> tenant().equals(user.getTenantId()) && company().equals(user.getCompanyId()) && user.isEnabled())
            .orElseThrow(() -> new BadRequestException("Approver must be an active user in this company"));
        task.setResultNote(request.resultNote().trim()); task.setApproverUserId(request.approverUserId());
        return allocation(allocations.save(task));
    }

    @Transactional
    public AllocationResponse submitTask(UUID orderId, UUID taskId) {
        requireVisible(orderId); requirePlanner();
        var task = task(orderId, taskId);
        if (!"OPEN".equals(task.getStatus()) || "STOCK_COUNT".equals(task.getTaskType()))
            throw new BadRequestException("Task cannot be submitted from this status");
        if (task.getResultNote() == null || task.getApproverUserId() == null
            || !attachments.hasAttachment("INVENTORY_ORDER_TASK", taskId, "REPORT", company()))
            throw new BadRequestException("Result note, approver and report file are required");
        task.setStatus("SUBMITTED"); task.setSubmittedAt(LocalDateTime.now());
        return allocation(allocations.save(task));
    }

    @Transactional
    public AllocationResponse approveTask(UUID orderId, UUID taskId) {
        orders.findByIdAndTenantIdAndCompanyId(orderId, tenant(), company())
            .orElseThrow(() -> new NotFoundException("Inventory order not found"));
        var task = task(orderId, taskId);
        if (!"SUBMITTED".equals(task.getStatus())) throw new BadRequestException("Task is not submitted");
        if (!inventoryAccess.currentUserId().equals(task.getApproverUserId()))
            throw new AccessDeniedException("Only the selected approver can approve this task");
        task.setStatus("APPROVED"); task.setApprovedAt(LocalDateTime.now()); task.setApprovedBy(inventoryAccess.username());
        return allocation(allocations.save(task));
    }

    @Transactional
    public AllocationResponse acceptTask(UUID orderId, UUID taskId) {
        requireVisible(orderId);
        if (!inventoryAccess.hasRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT"))
            throw new AccessDeniedException("Accounting receipt authority is required");
        var task = task(orderId, taskId);
        if (!"APPROVED".equals(task.getStatus())) throw new BadRequestException("Only approved tasks can be received");
        task.setStatus("ACCEPTED"); task.setAcceptedAt(LocalDateTime.now()); task.setAcceptedBy(inventoryAccess.username());
        return allocation(allocations.save(task));
    }

    private InventoryOrderAllocation task(UUID orderId, UUID taskId) {
        return allocations.lock(taskId, orderId).orElseThrow(() -> new NotFoundException("Inventory order task not found"));
    }

    private boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }
    private InventoryOrder requireVisible(UUID id) {
        InventoryOrder order = orders.findByIdAndTenantIdAndCompanyId(id, tenant(), company())
            .orElseThrow(() -> new NotFoundException("Inventory order not found"));
        if (!canRead(order)) throw new AccessDeniedException("Inventory order access is restricted");
        return order;
    }
    private boolean canRead(InventoryOrder order) {
        if (inventoryAccess.hasRole("DEPARTMENT_HEAD") && !inventoryAccess.hasRole("ADMIN")
            && json(order.getAnalysisPayload()).path("orderAnalysis").path("schemaVersion").asInt() != 2)
            return false;
        return order.getOwnerUsername().equals(inventoryAccess.username())
            || inventoryAccess.hasRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT");
    }
    private void requirePlanner() {
        if (!inventoryAccess.hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD"))
            throw new AccessDeniedException("Inventory planning authority is required");
    }
    private OrderResponse response(InventoryOrder order) {
        JsonNode analysis = json(order.getAnalysisPayload());
        if (analysis.path("orderAnalysis").path("schemaVersion").asInt() == 2 && analysis instanceof ObjectNode root) {
            ObjectNode summary = root.deepCopy();
            jobs.findScoped(order.getAnalysisJobId(), tenant(), company()).ifPresent(job -> {
                JsonNode targets = json(job.getRequestPayload()).path("scope").path("targets");
                if (summary.path("scope") instanceof ObjectNode scope && targets.isArray()) scope.set("targets", targets);
            });
            ObjectNode orderAnalysis = (ObjectNode) summary.path("orderAnalysis");
            for (String section : List.of("chairAssignments", "workItems", "commissionRosters")) {
                orderAnalysis.put(section + "Count", orderAnalysis.path(section).size());
                orderAnalysis.putArray(section);
            }
            analysis = summary;
        }
        return new OrderResponse(order.getId(), order.getSourceDraftId(), order.getAnalysisJobId(),
            analysis, order.getCreatedAt(),
            allocations.findByOrderIdOrderByCreatedAtAsc(order.getId()).stream().map(this::allocation).toList());
    }
    private OrderSummary summary(InventoryOrder order) {
        JsonNode analysis = json(order.getAnalysisPayload());
        JsonNode orderAnalysis = analysis.path("orderAnalysis");
        JsonNode header = orderAnalysis.path("header");
        var tasks = allocations.findByOrderIdOrderByCreatedAtAsc(order.getId());
        return new OrderSummary(order.getId(), header.path("orderNumber").path("value").asText(null),
            header.path("orderTitle").path("value").asText(null),
            header.path("orderDate").path("value").asText(null),
            header.path("stockAsOfDate").path("value").asText(null),
            header.path("plannedStartDate").path("value").asText(null),
            header.path("plannedEndDate").path("value").asText(null),
            analysis.path("status").asText(null), orderAnalysis.path("schemaVersion").asInt(1),
            analysis.path("scope").path("mode").asText(null),
            orderAnalysis.path("chairAssignments").size(), orderAnalysis.path("commissionRosters").size(),
            orderAnalysis.path("workItems").size(), order.getCreatedAt(), order.getOwnerUsername(),
            tasks.size(), (int) tasks.stream().filter(task -> task.getInventoryDocumentId() != null).count());
    }
    private AllocationResponse allocation(InventoryOrderAllocation row) {
        JsonNode payload = json(row.getRequestPayload());
        return new AllocationResponse(row.getId(), readKeys(row.getSourceKeys()), row.getTaskType(),
            row.getStatus(), row.getInventoryDocumentId(), row.getCommissionId(),
            row.getCommissionId() == null ? null : commissions.findByIdAndIsDeletedFalse(row.getCommissionId())
                .filter(commission -> tenant().equals(commission.getTenantId()) && company().equals(commission.getCompanyId()))
                .map(com.enterprise.mm.entity.Commission::getName).orElse(null),
            payload.path("notes").isTextual() ? payload.path("notes").asText() : null,
            row.getResultNote(), row.getApproverUserId(), row.getSubmittedAt(), row.getApprovedAt(), row.getAcceptedAt(),
            inventoryAccess.currentUserId().equals(row.getApproverUserId()));
    }
    private List<String> readKeys(String json) {
        var keys = new ArrayList<String>();
        for (JsonNode item : json(json)) keys.add(item.asText());
        return keys;
    }
    private JsonNode json(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException ex) { throw new BadRequestException("Invalid stored inventory order data"); }
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new BadRequestException("Cannot serialize inventory order task"); }
    }
    private String tenant() { return TenantContext.currentTenant(); }
    private UUID company() { return CompanyContext.currentCompany(); }
}
