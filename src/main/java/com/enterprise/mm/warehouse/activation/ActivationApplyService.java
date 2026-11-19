package com.enterprise.mm.warehouse.activation;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.posting.PostingReceipt;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes approved source opening activation deltas into native stock.
 * Guaranteed idempotent and atomic: retrying an identical activation returns
 * the existing receipt without applying deltas a second time.
 */
@Service
public class ActivationApplyService {
    private final ActivationPreviewService previewService;
    private final AccountAwareStockPostingService stockPostingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WarehouseRepository warehouseRepository;
    private final MaterialRepository materialRepository;
    private final RoleDataScopeService roleDataScopeService;

    @Autowired
    public ActivationApplyService(
        ActivationPreviewService previewService,
        AccountAwareStockPostingService stockPostingService,
        JdbcTemplate jdbc,
        @Autowired(required = false)
        WarehouseRepository warehouseRepository,
        @Autowired(required = false)
        RoleDataScopeService roleDataScopeService,
        @Autowired(required = false)
        MaterialRepository materialRepository
    ) {
        this.previewService = previewService;
        this.stockPostingService = stockPostingService;
        this.jdbc = jdbc;
        this.warehouseRepository = warehouseRepository;
        this.roleDataScopeService = roleDataScopeService;
        this.materialRepository = materialRepository;
        this.mapper = new ObjectMapper();
    }

    public ActivationApplyService(
        ActivationPreviewService previewService,
        AccountAwareStockPostingService stockPostingService,
        JdbcTemplate jdbc
    ) {
        this(previewService, stockPostingService, jdbc, null, null, null);
    }

    public record ActivationApplyRequest(
        String idempotencyKey,
        String accountCode,
        UUID warehouseId,
        LocalDate postingDate,
        UUID planId
    ) {
        public ActivationApplyRequest(String idempotencyKey, String accountCode, UUID warehouseId, LocalDate postingDate) {
            this(idempotencyKey, accountCode, warehouseId, postingDate, null);
        }

        public ActivationApplyRequest {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new BadRequestException("Idempotency-Key is required for source activation");
            }
            if (idempotencyKey.length() > 160) {
                throw new BadRequestException("Idempotency-Key exceeds the supported 160 characters");
            }
        }
    }

    public record CreatePlanRequest(
        String planCode,
        String accountCode,
        UUID warehouseId,
        LocalDate postingDate,
        UUID sourceImportId,
        LocalDate cutoverDate
    ) {}

    public record ApprovePlanRequest(
        String approvalNotes
    ) {}

    public record ActivationPlanResponse(
        UUID id,
        String planCode,
        String accountCode,
        UUID warehouseId,
        LocalDate postingDate,
        String previewHash,
        String sourceContentHash,
        String status,
        int itemCount,
        BigDecimal totalQuantity,
        BigDecimal totalAmount,
        String proposedBy,
        LocalDateTime proposedAt,
        String approvedBy,
        LocalDateTime approvedAt,
        String rejectionReason,
        UUID sourceImportId,
        LocalDate sourceSnapshotDate,
        String sourceManifestSha256,
        LocalDate cutoverDate
    ) {}

    private record SourceImport(LocalDate snapshotDate, String manifestSha256, long manifestCount) {}

    public record ActivationApplyResult(
        String idempotencyKey,
        int appliedCount,
        BigDecimal totalActivatedQuantity,
        BigDecimal totalActivatedAmount,
        List<UUID> batchIds
    ) {}

    private record ExistingActivation(
        String idempotencyKey,
        String accountCode,
        UUID warehouseId,
        LocalDate postingDate,
        String previewHash,
        String sourceContentHash,
        int appliedCount,
        BigDecimal totalQuantity,
        BigDecimal totalAmount,
        List<UUID> batchIds,
        UUID planId
    ) {}

    @Transactional
    public ActivationApplyResult apply(ActivationApplyRequest request) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }

        if (request.warehouseId() != null) {
            if (warehouseRepository != null) {
                var wh = warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, request.warehouseId());
                if (wh.isEmpty()) {
                    throw new com.enterprise.mm.error.NotFoundException("Warehouse " + request.warehouseId() + " not found in active company scope");
                }
            }
            if (roleDataScopeService != null) {
                roleDataScopeService.assertWarehouseAccess(request.warehouseId());
            }
        }

        // 1. Journal-First Check (Idempotency & Replay without False Conflict - Blocker 2)
        var existingList = findExistingActivation(tenantId, companyId, request.idempotencyKey());
        if (!existingList.isEmpty()) {
            ExistingActivation existing = existingList.get(0);
            boolean scopeMatches = Objects.equals(existing.accountCode(), request.accountCode())
                && Objects.equals(existing.warehouseId(), request.warehouseId())
                && (request.postingDate() == null
                    || Objects.equals(existing.postingDate(), request.postingDate()));

            if (!scopeMatches) {
                throw new ConflictException("Activation idempotency key '" + request.idempotencyKey()
                    + "' already used with mismatched scope and parameters (account, warehouse, or posting date)");
            }

            assertPlanWarehouseAccess(existing.warehouseId());

            if (request.planId() == null || existing.planId() == null
                || !Objects.equals(request.planId(), existing.planId())) {
                throw new ConflictException("Activation idempotency key '" + request.idempotencyKey()
                    + "' already used with mismatched plan ID (expected: " + existing.planId() + ", got: " + request.planId() + ")");
            }

            return new ActivationApplyResult(
                existing.idempotencyKey(),
                existing.appliedCount(),
                existing.totalQuantity().setScale(7, RoundingMode.HALF_UP),
                existing.totalAmount().setScale(4, RoundingMode.HALF_UP),
                existing.batchIds()
            );
        }

        // 2. Plan Verification (Blocker 1: Require an approved, immutable activation plan for new apply)
        if (request.planId() == null) {
            throw new BadRequestException("An approved activation plan (planId) is required to apply source activation");
        }
        ActivationPlanResponse plan = getPlanForUpdate(request.planId());
        LocalDate effectiveDate = request.postingDate() != null ? request.postingDate() : plan.postingDate();
        if (effectiveDate == null) {
            throw new BadRequestException("Activation plan must have a posting date");
        }
        if (!"APPROVED".equals(plan.status())) {
            throw new BadRequestException("Activation plan " + request.planId()
                + " must be in APPROVED status to apply (current status: " + plan.status() + "). An authorized accountant must approve the plan before apply.");
        }
        if (!Objects.equals(request.accountCode(), plan.accountCode())) {
            throw new BadRequestException("Request account code " + request.accountCode()
                + " does not match plan account code " + plan.accountCode());
        }
        if (!Objects.equals(request.warehouseId(), plan.warehouseId())) {
            throw new BadRequestException("Request warehouse " + request.warehouseId()
                + " does not match plan warehouse " + plan.warehouseId());
        }
        if (plan.postingDate() != null && !effectiveDate.equals(plan.postingDate())) {
            throw new BadRequestException("Request posting date " + effectiveDate
                + " does not match plan posting date " + plan.postingDate());
        }

        if (plan.sourceImportId() == null || plan.cutoverDate() == null
            || plan.sourceSnapshotDate() == null || plan.sourceManifestSha256() == null) {
            throw new BadRequestException("Activation plan has no approved source import and cutover; propose a new plan");
        }
        SourceImport sourceImport = getCompletedSourceImport(tenantId, companyId, plan.sourceImportId());
        if (!Objects.equals(sourceImport.snapshotDate(), plan.sourceSnapshotDate())
            || !Objects.equals(sourceImport.manifestSha256(), plan.sourceManifestSha256())) {
            throw new ConflictException("Approved source import identity has changed");
        }
        assertPlanWarehouseAccess(plan.warehouseId());

        // Hash every source position so a corrected disputed or historical row also invalidates approval.
        var previewQuery = new ActivationPreviewQuery(plan.accountCode(), plan.warehouseId(),
            null, 0, Integer.MAX_VALUE, plan.sourceImportId(), plan.cutoverDate());
        ActivationPreviewResult previewResult = previewService.preview(previewQuery);

        List<ActivationPreviewCandidate> allItems = new ArrayList<>(previewResult.items());
        int page = 0;
        int pageSize = 5000;
        while (allItems.size() < previewResult.totalCount() && !previewResult.items().isEmpty()) {
            page++;
            var nextQuery = new ActivationPreviewQuery(plan.accountCode(), plan.warehouseId(),
                null, page, pageSize, plan.sourceImportId(), plan.cutoverDate());
            var nextResult = previewService.preview(nextQuery);
            if (nextResult.items().isEmpty()) {
                break;
            }
            allItems.addAll(nextResult.items());
        }

        List<ActivationPreviewCandidate> eligibleItems = allItems.stream()
            .filter(item -> item.classification() == SourcePositionClassification.ELIGIBLE_DELTA).toList();

        for (var item : eligibleItems) {
            validateTargetIdentity(tenantId, companyId, plan, item);
        }

        String currentPreviewHash = computePreviewHash(eligibleItems);
        String currentSourceContentHash = computeSourceContentHash(allItems);

        // Blocker 2: Verify source data hasn't drifted since the plan was approved (stale plan detection)
        if (plan != null) {
            if (!Objects.equals(currentPreviewHash, plan.previewHash())) {
                throw new ConflictException("Stale activation plan: source stock positions have changed since plan was approved (preview hash mismatch). Please propose and approve a new plan.");
            }
            if (!Objects.equals(currentSourceContentHash, plan.sourceContentHash())) {
                throw new ConflictException("Stale activation plan: source content hash mismatch (" + currentSourceContentHash
                    + " vs " + plan.sourceContentHash() + "). Source records changed after plan approval; a new plan must be proposed and approved.");
            }
        }

        if (eligibleItems.isEmpty()) {
            recordActivationJournal(tenantId, companyId, request, effectiveDate, currentPreviewHash,
                currentSourceContentHash, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), plan.id());
            markPlanApplied(tenantId, companyId, plan.id());
            return new ActivationApplyResult(request.idempotencyKey(), 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }

        // 4. Verify approved activation rules exist for each account (Fail-Closed)
        java.util.Set<String> verifiedAccounts = new java.util.HashSet<>();
        for (var item : eligibleItems) {
            if (item.accountCode() != null && verifiedAccounts.add(item.accountCode())) {
                validateApprovedPostingRuleExists(tenantId, companyId, item.accountCode(), effectiveDate);
            }
        }

        // 4. Apply eligible positive deltas through shared posting entry point
        List<UUID> batchIds = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;

        for (var item : eligibleItems) {
            if (item.deltaQuantity().signum() <= 0) {
                continue;
            }

            BigDecimal unitCost = item.deltaQuantity().signum() > 0 && item.deltaAmount().signum() > 0
                ? item.deltaAmount().divide(item.deltaQuantity(), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            String commandKey = "activation:" + sha256(plan.id() + ":" + item.sourceKey());
            var payloadNode = mapper.createObjectNode()
                .put("sourceKey", item.sourceKey())
                .put("materialCode", item.materialCode())
                .put("warehouseCode", item.warehouseCode())
                .put("accountCode", item.accountCode())
                .put("quantity", item.deltaQuantity().toPlainString())
                .put("amount", item.deltaAmount().toPlainString());

            var command = new PostingCommand(
                "WAREHOUSE",
                "RECEIPT",
                "OPENING",
                item.materialId(),
                commandKey,
                effectiveDate,
                1L,
                payloadNode
            );

            var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
                command,
                item.materialId(),
                item.warehouseId(),
                null,
                null,
                null,
                null,
                item.stockCategory() != null ? item.stockCategory() : "OWNED",
                "AVAILABLE",
                item.accountCode(),
                item.uom(),
                item.deltaQuantity(),
                unitCost,
                "UZS",
                "1c:activation:" + item.sourceKey(),
                item.deltaAmount()
            );

            PostingReceipt receipt = stockPostingService.postReceipt(receiptRequest);
            batchIds.add(receipt.batchId());
            totalQty = totalQty.add(item.deltaQuantity());
            totalAmt = totalAmt.add(item.deltaAmount());
        }

        BigDecimal scaledQty = totalQty.setScale(7, RoundingMode.HALF_UP);
        BigDecimal scaledAmt = totalAmt.setScale(4, RoundingMode.HALF_UP);

        // 6. Store journal entry and mark plan applied
        recordActivationJournal(tenantId, companyId, request, effectiveDate, currentPreviewHash,
            currentSourceContentHash, batchIds.size(), scaledQty, scaledAmt, batchIds, plan.id());

        markPlanApplied(tenantId, companyId, plan.id());

        return new ActivationApplyResult(
            request.idempotencyKey(),
            batchIds.size(),
            scaledQty,
            scaledAmt,
            batchIds
        );
    }

    @Transactional
    public ActivationPlanResponse createPlan(CreatePlanRequest request) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        if (tenantId == null || companyId == null) {
            throw new BadRequestException("Tenant and Company context are required");
        }

        if (request.sourceImportId() == null || request.cutoverDate() == null) {
            throw new BadRequestException("A completed sourceImportId and cutoverDate are required for an activation plan");
        }
        SourceImport sourceImport = getCompletedSourceImport(tenantId, companyId, request.sourceImportId());
        if (request.cutoverDate().isBefore(sourceImport.snapshotDate())) {
            throw new BadRequestException("Cutover cannot precede the source snapshot date");
        }
        if (roleDataScopeService != null && request.warehouseId() == null
            && roleDataScopeService.currentScope().warehouseScoped()) {
            throw new org.springframework.security.access.AccessDeniedException("A warehouse assignment cannot propose a company-wide plan");
        }

        if (request.warehouseId() != null) {
            if (warehouseRepository != null) {
                var wh = warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, request.warehouseId());
                if (wh.isEmpty()) {
                    throw new com.enterprise.mm.error.NotFoundException("Warehouse " + request.warehouseId() + " not found in active company scope");
                }
            }
            if (roleDataScopeService != null) {
                roleDataScopeService.assertWarehouseAccess(request.warehouseId());
            }
        }

        LocalDate effectiveDate = request.postingDate() != null ? request.postingDate() : LocalDate.now();
        if (effectiveDate.isBefore(request.cutoverDate())) {
            throw new BadRequestException("Posting date cannot precede cutover date");
        }

        var previewQuery = new ActivationPreviewQuery(request.accountCode(), request.warehouseId(),
            null, 0, Integer.MAX_VALUE, request.sourceImportId(), request.cutoverDate());
        ActivationPreviewResult previewResult = previewService.preview(previewQuery);
        List<ActivationPreviewCandidate> allItems = previewResult.items();
        List<ActivationPreviewCandidate> items = allItems.stream()
            .filter(item -> item.classification() == SourcePositionClassification.ELIGIBLE_DELTA).toList();

        String previewHash = computePreviewHash(items);
        String sourceContentHash = computeSourceContentHash(allItems);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (var it : items) {
            if (it.deltaQuantity() != null) totalQty = totalQty.add(it.deltaQuantity());
            if (it.deltaAmount() != null) totalAmt = totalAmt.add(it.deltaAmount());
        }

        String planCode = request.planCode() != null && !request.planCode().isBlank()
            ? request.planCode().trim()
            : "PLAN-" + (request.accountCode() != null ? request.accountCode() : "ALL") + "-" + System.currentTimeMillis();

        UUID planId = UUID.randomUUID();
        String manifestJson;
        try {
            manifestJson = mapper.writeValueAsString(items);
        } catch (Exception e) {
            manifestJson = "[]";
        }

        String currentUser = resolveCurrentUser();
        if (currentUser == null || currentUser.isBlank()) {
            currentUser = "SYSTEM";
        }

        var insertSql = """
            INSERT INTO warehouse_activation_plans (
                id, tenant_id, company_id, plan_code, account_code, warehouse_id,
                posting_date, preview_hash, source_content_hash, status, item_count,
                total_quantity, total_amount, proposed_by, proposed_at, manifest_json,
                source_import_id, source_snapshot_date, source_manifest_sha256, cutover_date
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, ?, ?, CURRENT_TIMESTAMP, ?::jsonb, ?, ?, ?, ?)
            """;
        jdbc.update(insertSql, planId, tenantId, companyId, planCode, request.accountCode(), request.warehouseId(),
            effectiveDate, previewHash, sourceContentHash, items.size(), totalQty, totalAmt, currentUser, manifestJson,
            request.sourceImportId(), sourceImport.snapshotDate(), sourceImport.manifestSha256(), request.cutoverDate());

        return new ActivationPlanResponse(
            planId, planCode, request.accountCode(), request.warehouseId(), effectiveDate,
            previewHash, sourceContentHash, "PROPOSED", items.size(),
            totalQty.setScale(7, RoundingMode.HALF_UP),
            totalAmt.setScale(4, RoundingMode.HALF_UP),
            currentUser, LocalDateTime.now(), null, null, null,
            request.sourceImportId(), sourceImport.snapshotDate(), sourceImport.manifestSha256(), request.cutoverDate()
        );
    }

    public ActivationPlanResponse getPlan(UUID planId) {
        return loadPlan(planId, false);
    }

    private ActivationPlanResponse getPlanForUpdate(UUID planId) {
        return loadPlan(planId, true);
    }

    private ActivationPlanResponse loadPlan(UUID planId, boolean forUpdate) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        var sql = """
            SELECT id, plan_code, account_code, warehouse_id, posting_date, preview_hash,
                   source_content_hash, status, item_count, total_quantity, total_amount,
                   proposed_by, proposed_at, approved_by, approved_at, rejection_reason,
                   source_import_id, source_snapshot_date, source_manifest_sha256, cutover_date
            FROM warehouse_activation_plans
            WHERE tenant_id = ? AND company_id = ? AND id = ?
            """;
        var list = jdbc.query(forUpdate ? sql + " FOR UPDATE" : sql, this::mapPlan,
            tenantId, companyId, planId);

        if (list.isEmpty()) {
            throw new com.enterprise.mm.error.NotFoundException("Activation plan " + planId + " not found");
        }
        ActivationPlanResponse plan = list.get(0);
        assertPlanWarehouseAccess(plan.warehouseId());
        return plan;
    }

    public List<ActivationPlanResponse> listPlans(String accountCode, UUID warehouseId, String status) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        UUID effectiveWarehouseId = warehouseId;
        if (roleDataScopeService != null) {
            effectiveWarehouseId = roleDataScopeService.availabilityWarehouseId(warehouseId);
        }
        var sql = "SELECT id, plan_code, account_code, warehouse_id, posting_date, preview_hash, "
            + "source_content_hash, status, item_count, total_quantity, total_amount, "
            + "proposed_by, proposed_at, approved_by, approved_at, rejection_reason, "
            + "source_import_id, source_snapshot_date, source_manifest_sha256, cutover_date "
            + "FROM warehouse_activation_plans WHERE tenant_id = ? AND company_id = ?";
        List<Object> params = new ArrayList<>(List.of(tenantId, companyId));
        if (accountCode != null && !accountCode.isBlank()) {
            sql += " AND account_code = ?";
            params.add(accountCode.trim());
        }
        if (effectiveWarehouseId != null) {
            sql += " AND warehouse_id = ?";
            params.add(effectiveWarehouseId);
        }
        if (status != null && !status.isBlank()) {
            sql += " AND status = ?";
            params.add(status.trim().toUpperCase());
        }
        sql += " ORDER BY proposed_at DESC";

        return jdbc.query(sql, this::mapPlan, params.toArray());
    }

    private ActivationPlanResponse mapPlan(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ActivationPlanResponse(
            rs.getObject("id", UUID.class),
            rs.getString("plan_code"),
            rs.getString("account_code"),
            rs.getObject("warehouse_id", UUID.class),
            rs.getObject("posting_date", LocalDate.class),
            rs.getString("preview_hash"),
            rs.getString("source_content_hash"),
            rs.getString("status"),
            rs.getInt("item_count"),
            rs.getBigDecimal("total_quantity"),
            rs.getBigDecimal("total_amount"),
            rs.getString("proposed_by"),
            rs.getTimestamp("proposed_at") == null ? null : rs.getTimestamp("proposed_at").toLocalDateTime(),
            rs.getString("approved_by"),
            rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toLocalDateTime(),
            rs.getString("rejection_reason"),
            rs.getObject("source_import_id", UUID.class),
            rs.getObject("source_snapshot_date", LocalDate.class),
            rs.getString("source_manifest_sha256"),
            rs.getObject("cutover_date", LocalDate.class)
        );
    }

    private void assertPlanWarehouseAccess(UUID warehouseId) {
        if (roleDataScopeService == null) return;
        if (warehouseId == null) {
            if (roleDataScopeService.currentScope().warehouseScoped()) {
                throw new org.springframework.security.access.AccessDeniedException(
                    "A warehouse assignment cannot access a company-wide activation plan");
            }
        } else {
            roleDataScopeService.assertWarehouseAccess(warehouseId);
        }
    }

    @Transactional
    public ActivationPlanResponse approvePlan(UUID planId, String approvalNotes) {
        ActivationPlanResponse plan = getPlanForUpdate(planId);
        if (!"PROPOSED".equals(plan.status())) {
            throw new BadRequestException("Activation plan " + planId + " cannot be approved from status: " + plan.status());
        }

        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        String approver = resolveCurrentUser();
        if (approver == null || approver.isBlank()) {
            approver = "FINANCE_CONTROLLER";
        }

        // Segregation of duties: Proposer cannot approve their own plan (Blocker 1)
        if (plan.proposedBy() != null && !plan.proposedBy().equalsIgnoreCase("SYSTEM")
            && plan.proposedBy().equalsIgnoreCase(approver)) {
            throw new BadRequestException("Segregation of duties violation: Proposer '" + plan.proposedBy()
                + "' cannot approve their own activation plan " + planId);
        }

        if (plan.sourceImportId() == null || plan.cutoverDate() == null) {
            throw new BadRequestException("Activation plan has no source import and cutover; propose a new plan");
        }
        SourceImport sourceImport = getCompletedSourceImport(tenantId, companyId, plan.sourceImportId());
        if (!Objects.equals(sourceImport.snapshotDate(), plan.sourceSnapshotDate())
            || !Objects.equals(sourceImport.manifestSha256(), plan.sourceManifestSha256())) {
            throw new ConflictException("Source import identity changed before approval");
        }

        // Verify approved posting rule exists before approving plan
        if (plan.accountCode() != null) {
            validateApprovedPostingRuleExists(tenantId, companyId, plan.accountCode(), plan.postingDate());
        }

        var updateSql = """
            UPDATE warehouse_activation_plans
            SET status = 'APPROVED', approved_by = ?, approved_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND company_id = ? AND id = ? AND status = 'PROPOSED'
            """;
        if (jdbc.update(updateSql, approver, tenantId, companyId, planId) != 1) {
            throw new ConflictException("Activation plan status changed before approval");
        }
        return getPlan(planId);
    }

    private List<ExistingActivation> findExistingActivation(String tenantId, UUID companyId, String idempotencyKey) {
        var checkSql = """
            SELECT idempotency_key, account_code, warehouse_id, posting_date, preview_hash,
                   source_content_hash, applied_count, total_quantity, total_amount, batch_ids_json, plan_id
            FROM warehouse_activation_journals
            WHERE tenant_id = ? AND company_id = ? AND idempotency_key = ?
            """;
        return jdbc.query(checkSql, (rs, rowNum) -> {
            return new ExistingActivation(
                rs.getString("idempotency_key"),
                rs.getString("account_code"),
                rs.getObject("warehouse_id", UUID.class),
                rs.getObject("posting_date", LocalDate.class),
                rs.getString("preview_hash"),
                rs.getString("source_content_hash"),
                rs.getInt("applied_count"),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("total_amount"),
                parseBatchIds(rs.getString("batch_ids_json")),
                rs.getObject("plan_id", UUID.class)
            );
        }, tenantId, companyId, idempotencyKey);
    }

    private void recordActivationJournal(
        String tenantId,
        UUID companyId,
        ActivationApplyRequest request,
        LocalDate effectiveDate,
        String previewHash,
        String sourceContentHash,
        int appliedCount,
        BigDecimal totalQty,
        BigDecimal totalAmt,
        List<UUID> batchIds,
        UUID planId
    ) {
        String batchIdsJson;
        try {
            batchIdsJson = mapper.writeValueAsString(batchIds);
        } catch (Exception e) {
            batchIdsJson = "[]";
        }
        String receiptJson = "{}";
        try {
            var receiptMap = new java.util.HashMap<String, Object>();
            if (planId != null) receiptMap.put("planId", planId.toString());
            receiptMap.put("previewHash", previewHash);
            receiptMap.put("sourceContentHash", sourceContentHash);
            receiptJson = mapper.writeValueAsString(receiptMap);
        } catch (Exception ignored) {}

        var insertSql = """
            INSERT INTO warehouse_activation_journals (
                tenant_id, company_id, idempotency_key, account_code, warehouse_id,
                posting_date, preview_hash, source_content_hash, status, applied_count,
                total_quantity, total_amount, batch_ids_json, receipt_json, plan_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """;
        jdbc.update(insertSql, tenantId, companyId, request.idempotencyKey(),
            request.accountCode(), request.warehouseId(), effectiveDate,
            previewHash, sourceContentHash, appliedCount, totalQty, totalAmt, batchIdsJson, receiptJson, planId);
    }

    private List<UUID> parseBatchIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return mapper.readValue(json, new TypeReference<List<UUID>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    String computePreviewHash(List<ActivationPreviewCandidate> items) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (var item : items) {
                String row = (item.sourceKey() != null ? item.sourceKey() : "") + ":"
                    + (item.accountCode() != null ? item.accountCode() : "") + ":"
                    + (item.materialId() != null ? item.materialId() : "") + ":"
                    + (item.warehouseId() != null ? item.warehouseId() : "") + ":"
                    + (item.stockCategory() != null ? item.stockCategory() : "") + ":"
                    + (item.uom() != null ? item.uom() : "") + ":"
                    + (item.deltaQuantity() != null ? item.deltaQuantity().stripTrailingZeros().toPlainString() : "0") + ":"
                    + (item.deltaAmount() != null ? item.deltaAmount().stripTrailingZeros().toPlainString() : "0") + ":"
                    + item.classification() + ";";
                md.update(row.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute preview hash", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive activation posting key", e);
        }
    }

    String computeSourceContentHash(List<ActivationPreviewCandidate> items) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (var item : items) {
                String row = (item.sourceKey() != null ? item.sourceKey() : "") + ":"
                    + (item.sourceRowHash() != null ? item.sourceRowHash() : "") + ":"
                    + (item.accountCode() != null ? item.accountCode() : "") + ":"
                    + (item.materialId() != null ? item.materialId() : "") + ":"
                    + (item.warehouseId() != null ? item.warehouseId() : "") + ":"
                    + (item.stockCategory() != null ? item.stockCategory() : "") + ":"
                    + (item.uom() != null ? item.uom() : "") + ":"
                    + (item.sourceQuantity() != null ? item.sourceQuantity().stripTrailingZeros().toPlainString() : "0") + ":"
                    + (item.sourceAmount() != null ? item.sourceAmount().stripTrailingZeros().toPlainString() : "0") + ";";
                md.update(row.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute source content hash", e);
        }
    }

    private SourceImport getCompletedSourceImport(String tenantId, UUID companyId, UUID importId) {
        var imports = jdbc.query("""
            SELECT source.snapshot_date, source.manifest_sha256,
                   (SELECT count(*) FROM warehouse_account_imports peer
                    WHERE peer.tenant_id = source.tenant_id AND peer.company_id = source.company_id
                      AND peer.organization_source_id = source.organization_source_id
                      AND peer.snapshot_date = source.snapshot_date AND peer.status = 'COMPLETED') AS manifest_count
            FROM warehouse_account_imports source
            WHERE source.tenant_id = ? AND source.company_id = ? AND source.id = ?
              AND source.status = 'COMPLETED'
            """, (rs, rowNum) -> new SourceImport(
                rs.getObject("snapshot_date", LocalDate.class), rs.getString("manifest_sha256"),
                rs.getLong("manifest_count")),
            tenantId, companyId, importId);
        if (imports.isEmpty()) {
            throw new BadRequestException("Completed source import " + importId + " not found in company scope");
        }
        if (imports.get(0).manifestCount() > 1) {
            throw new BadRequestException("Source snapshot has multiple completed manifests; they require joint reconciliation");
        }
        return imports.get(0);
    }

    private void markPlanApplied(String tenantId, UUID companyId, UUID planId) {
        int changed = jdbc.update("""
            UPDATE warehouse_activation_plans SET status = 'APPLIED'
            WHERE tenant_id = ? AND company_id = ? AND id = ? AND status = 'APPROVED'
            """, tenantId, companyId, planId);
        if (changed != 1) {
            throw new ConflictException("Activation plan status changed during apply");
        }
    }

    private void validateTargetIdentity(String tenantId, UUID companyId, ActivationPlanResponse plan,
                                        ActivationPreviewCandidate item) {
        if (item.materialId() == null || item.warehouseId() == null
            || (plan.accountCode() != null && !plan.accountCode().equals(item.accountCode()))
            || (plan.warehouseId() != null && !plan.warehouseId().equals(item.warehouseId()))) {
            throw new ConflictException("Activation target differs from the approved account or warehouse scope");
        }
        if (warehouseRepository != null && warehouseRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, item.warehouseId()).isEmpty()) {
            throw new BadRequestException("Activation target warehouse is outside company scope");
        }
        if (roleDataScopeService != null) {
            roleDataScopeService.assertWarehouseAccess(item.warehouseId());
        }
        if (materialRepository != null) {
            var material = materialRepository.findById(item.materialId())
                .filter(found -> tenantId.equals(found.getTenantId())
                    && companyId.equals(found.getCompanyId()) && !found.isDeleted())
                .orElseThrow(() -> new BadRequestException("Activation target material is outside company scope"));
            if (material.getUnitOfMeasure() == null || material.getUnitOfMeasure().getCode() == null
                || item.uom() == null
                || !material.getUnitOfMeasure().getCode().equalsIgnoreCase(item.uom())) {
                throw new BadRequestException("Activation source unit does not match the material base unit");
            }
        }
    }

    private void validateApprovedPostingRuleExists(String tenantId, UUID companyId, String accountCode, LocalDate effectiveDate) {
        var checkSql = """
            SELECT account_code, currency_code FROM warehouse_posting_rules
            WHERE tenant_id = ? AND company_id = ? AND book = 'WAREHOUSE'
              AND operation = 'RECEIPT' AND context_key = 'OPENING' AND approved = true
              AND (account_code = ? OR account_code IS NULL)
              AND effective_from <= ?
              AND (effective_through IS NULL OR effective_through >= ?)
            """;
        var rules = jdbc.queryForList(checkSql, tenantId, companyId, accountCode, effectiveDate, effectiveDate);
        var exact = rules.stream().filter(rule -> accountCode.equals(rule.get("account_code"))).toList();
        var selected = exact.isEmpty() ? rules : exact;
        if (selected.isEmpty()) {
            throw new BadRequestException("No approved opening posting rule exists for account " + accountCode
                + " in company " + companyId + ". An authorized accountant must approve a posting rule before activation.");
        }
        if (selected.size() != 1) {
            throw new ConflictException("Ambiguous approved opening posting rules for account " + accountCode);
        }
        if (!"UZS".equals(selected.getFirst().get("currency_code"))) {
            throw new BadRequestException("Opening posting rule currency must be UZS for source activation");
        }
    }

    private String resolveCurrentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank())
            ? auth.getName()
            : "SYSTEM";
    }
}
