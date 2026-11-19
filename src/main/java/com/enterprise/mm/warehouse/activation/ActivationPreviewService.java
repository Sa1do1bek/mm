package com.enterprise.mm.warehouse.activation;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.enterprise.mm.service.RoleDataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates and classifies 1C source positions against current native balances.
 * Read-only preview service that never mutates stock or creates unapproved entries.
 */
@Service
public class ActivationPreviewService {
    private final NamedParameterJdbcTemplate jdbc;
    private final com.enterprise.mm.service.RoleDataScopeService roleDataScopeService;

    @Autowired
    public ActivationPreviewService(
        NamedParameterJdbcTemplate jdbc,
        @Autowired(required = false)
        RoleDataScopeService roleDataScopeService
    ) {
        this.jdbc = jdbc;
        this.roleDataScopeService = roleDataScopeService;
    }

    public ActivationPreviewService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Transactional(readOnly = true)
    public ActivationPreviewResult preview(ActivationPreviewQuery query) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        if (tenantId == null || companyId == null) {
            return emptyResult(query);
        }

        UUID effectiveWarehouseId = query.warehouseId();
        if (roleDataScopeService != null) {
            effectiveWarehouseId = roleDataScopeService.availabilityWarehouseId(query.warehouseId());
            if (effectiveWarehouseId != null) {
                roleDataScopeService.assertWarehouseAccess(effectiveWarehouseId);
            }
        }

        // Check if warehouse_account_entries exists and has completed imports
        var hasImportsSql = """
            SELECT count(*) FROM warehouse_account_imports
            WHERE tenant_id = :tenantId AND company_id = :companyId AND status = 'COMPLETED'
            """;
        var params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("companyId", companyId)
            .addValue("importId", query.importId())
            .addValue("cutoverDate", query.cutoverDate());

        Long completedImports = jdbc.queryForObject(hasImportsSql, params, Long.class);
        if (completedImports == null || completedImports == 0) {
            return emptyResult(query);
        }

        // Query all balance positions from 1C joined with master links and proven native accounting positions
        var sql = """
            SELECT e.source_key,
                   e.account_code,
                   e.account_name,
                   coalesce(e.material_id, lm.master_id) AS material_id,
                   e.material_code,
                   e.material_name,
                   coalesce(e.warehouse_id, lw.master_id) AS warehouse_id,
                   e.warehouse_code,
                   e.warehouse_name,
                   coalesce(e.stock_category, 'OWNED') AS stock_category,
                   e.uom,
                   coalesce(nullif(upper(e.content::jsonb->>'currencyCode'), ''),
                            nullif(upper(e.additional_dimensions->>'currencyCode'), '')) AS source_currency_code,
                   e.quantity AS source_quantity,
                   e.amount AS source_amount,
                   e.row_sha256,
                   e.material_source_id,
                   e.warehouse_source_id,
                   count(*) OVER (PARTITION BY r.snapshot_date, e.account_code,
                       coalesce(e.material_id, lm.master_id), coalesce(e.warehouse_id, lw.master_id),
                       coalesce(e.stock_category, 'OWNED'), e.uom) AS source_position_count,
                   coalesce(stock.on_hand, 0) AS native_on_hand,
                   coalesce(stock.total_value, 0) AS native_total_value,
                   coalesce(unproven.unproven_on_hand, 0) AS unproven_on_hand,
                   EXISTS (
                       SELECT 1 FROM warehouse_posting_effects conflict_effect
                       JOIN warehouse_posting_batches conflict_batch ON conflict_batch.id = conflict_effect.batch_id
                       WHERE conflict_batch.tenant_id = e.tenant_id AND conflict_batch.company_id = e.company_id
                         AND conflict_batch.status = 'COMMITTED' AND conflict_effect.effect_type = 'ACCOUNT'
                         AND conflict_effect.material_id = coalesce(e.material_id, lm.master_id)
                         AND conflict_effect.warehouse_id = coalesce(e.warehouse_id, lw.master_id)
                         AND conflict_effect.account_code = e.account_code
                         AND coalesce(conflict_effect.stock_category, 'OWNED') = coalesce(e.stock_category, 'OWNED')
                         AND (coalesce(conflict_effect.owner_id, e.company_id) <> e.company_id
                              OR conflict_effect.base_unit_code IS DISTINCT FROM e.uom
                              OR conflict_effect.currency_code IS DISTINCT FROM 'UZS')
                   ) AS incompatible_native_identity,
                   EXISTS (
                       SELECT 1 FROM warehouse_posting_effects activity_effect
                       JOIN warehouse_posting_batches activity_batch ON activity_batch.id = activity_effect.batch_id
                       WHERE activity_batch.tenant_id = e.tenant_id
                         AND activity_batch.company_id = e.company_id
                         AND activity_batch.status = 'COMMITTED'
                         AND activity_effect.material_id = coalesce(e.material_id, lm.master_id)
                         AND activity_effect.warehouse_id = coalesce(e.warehouse_id, lw.master_id)
                         AND (activity_batch.posting_date > r.snapshot_date
                              OR activity_batch.committed_at > r.completed_at)
                   ) AS post_snapshot_native_activity,
                   EXISTS (
                       SELECT 1 FROM warehouse_account_entries source_movement
                       JOIN warehouse_account_imports movement_import ON movement_import.id = source_movement.import_id
                            AND movement_import.tenant_id = e.tenant_id AND movement_import.company_id = e.company_id
                            AND movement_import.status = 'COMPLETED'
                       WHERE source_movement.tenant_id = e.tenant_id AND source_movement.company_id = e.company_id
                         AND source_movement.kind = 'MOVEMENT'
                         AND source_movement.account_code = e.account_code
                         AND source_movement.material_source_id = e.material_source_id
                         AND source_movement.warehouse_source_id = e.warehouse_source_id
                         AND source_movement.source_date > r.snapshot_date
                   ) AS post_snapshot_source_activity
            FROM warehouse_account_entries e
            JOIN warehouse_account_imports r ON r.id = e.import_id AND r.status = 'COMPLETED'
               AND r.tenant_id = e.tenant_id AND r.company_id = e.company_id
            LEFT JOIN warehouse_source_master_links lm
                   ON lm.tenant_id = e.tenant_id AND lm.company_id = e.company_id
                  AND lm.reference_kind = 'MATERIAL' AND lm.reference_type = e.material_reference_type
                  AND lm.source_id = e.material_source_id
            LEFT JOIN warehouse_source_master_links lw
                   ON lw.tenant_id = e.tenant_id AND lw.company_id = e.company_id
                  AND lw.reference_kind = 'WAREHOUSE' AND lw.reference_type = '00000055'
                  AND lw.source_id = e.warehouse_source_id
            LEFT JOIN (
                SELECT b.tenant_id, b.company_id, pe.material_id, pe.warehouse_id, pe.account_code,
                       coalesce(pe.stock_category, 'OWNED') AS stock_category,
                       coalesce(pe.owner_id, b.company_id) AS owner_id,
                       pe.base_unit_code, pe.currency_code,
                       coalesce(sum(pe.quantity), 0) AS on_hand,
                       coalesce(sum(pe.amount), 0) AS total_value
                FROM warehouse_posting_effects pe
                JOIN warehouse_posting_batches b ON b.id = pe.batch_id
                WHERE b.tenant_id = :tenantId AND b.company_id = :companyId
                  AND pe.effect_type = 'ACCOUNT' AND b.status = 'COMMITTED'
                GROUP BY b.tenant_id, b.company_id, pe.material_id, pe.warehouse_id, pe.account_code,
                         coalesce(pe.stock_category, 'OWNED'), coalesce(pe.owner_id, b.company_id),
                         pe.base_unit_code, pe.currency_code
            ) stock ON stock.material_id = coalesce(e.material_id, lm.master_id)
                   AND stock.warehouse_id = coalesce(e.warehouse_id, lw.master_id)
                   AND stock.account_code = e.account_code
                   AND stock.stock_category = coalesce(e.stock_category, 'OWNED')
                   AND stock.owner_id = e.company_id
                   AND stock.base_unit_code = e.uom
                   AND stock.currency_code = 'UZS'
            LEFT JOIN (
                SELECT coalesce(physical.material_id, proven.material_id) AS material_id,
                       coalesce(physical.warehouse_id, proven.warehouse_id) AS warehouse_id,
                       coalesce(physical.total_physical, 0) - coalesce(proven.total_proven, 0) AS unproven_on_hand
                FROM (
                    SELECT sb.material_id, sb.warehouse_id, coalesce(sum(sb.quantity), 0) AS total_physical
                    FROM stock_balances sb
                    WHERE sb.tenant_id = :tenantId AND sb.company_id = :companyId AND sb.is_delete = false
                    GROUP BY sb.material_id, sb.warehouse_id
                ) physical
                FULL OUTER JOIN (
                    SELECT pe.material_id, pe.warehouse_id, coalesce(sum(pe.quantity), 0) AS total_proven
                    FROM warehouse_posting_effects pe
                    JOIN warehouse_posting_batches pb ON pb.id = pe.batch_id
                    WHERE pb.tenant_id = :tenantId AND pb.company_id = :companyId
                      AND pe.effect_type = 'STOCK' AND pb.status = 'COMMITTED'
                    GROUP BY pe.material_id, pe.warehouse_id
                ) proven ON proven.material_id = physical.material_id AND proven.warehouse_id = physical.warehouse_id
            ) unproven ON unproven.material_id = coalesce(e.material_id, lm.master_id)
                       AND unproven.warehouse_id = coalesce(e.warehouse_id, lw.master_id)
            WHERE e.tenant_id = :tenantId AND e.company_id = :companyId AND e.kind = 'BALANCE'
              AND ((cast(:importId as uuid) IS NOT NULL AND r.id = cast(:importId as uuid))
                   OR (cast(:importId as uuid) IS NULL AND r.snapshot_date = (
                       SELECT max(latest.snapshot_date) FROM warehouse_account_imports latest
                       WHERE latest.tenant_id = :tenantId AND latest.company_id = :companyId
                         AND latest.status = 'COMPLETED'
                   )))
              AND (cast(:cutoverDate as date) IS NULL OR e.source_date <= :cutoverDate)
            """;

        if (query.accountCode() != null && !query.accountCode().isBlank()) {
            sql += " AND e.account_code = :accountCode";
            params.addValue("accountCode", query.accountCode().trim());
        }
        if (effectiveWarehouseId != null) {
            sql += " AND coalesce(e.warehouse_id, lw.master_id) = :warehouseId";
            params.addValue("warehouseId", effectiveWarehouseId);
        }

        sql += " ORDER BY e.account_code, e.material_code, e.source_key";

        List<ActivationPreviewCandidate> allEvaluated = jdbc.query(sql, params, this::mapAndClassify);

        // Filter by requested classification if specified
        List<ActivationPreviewCandidate> filtered = allEvaluated;
        if (query.classification() != null) {
            filtered = allEvaluated.stream()
                .filter(item -> item.classification() == query.classification())
                .toList();
        }

        // Aggregate statistics across all evaluated items
        long eligibleCount = 0;
        long alreadyRepresentedCount = 0;
        long valueOnlyCount = 0;
        long negativeDisputedCount = 0;
        long historicalOnlyCount = 0;
        long unresolvedCount = 0;

        BigDecimal totalSourceQuantity = BigDecimal.ZERO;
        BigDecimal totalSourceAmount = BigDecimal.ZERO;
        BigDecimal totalDeltaQuantity = BigDecimal.ZERO;
        BigDecimal totalDeltaAmount = BigDecimal.ZERO;

        for (var c : allEvaluated) {
            totalSourceQuantity = totalSourceQuantity.add(c.sourceQuantity());
            totalSourceAmount = totalSourceAmount.add(c.sourceAmount());
            totalDeltaQuantity = totalDeltaQuantity.add(c.deltaQuantity());
            totalDeltaAmount = totalDeltaAmount.add(c.deltaAmount());

            switch (c.classification()) {
                case ELIGIBLE_DELTA -> eligibleCount++;
                case ALREADY_REPRESENTED -> alreadyRepresentedCount++;
                case VALUE_ONLY -> valueOnlyCount++;
                case NEGATIVE_DISPUTED -> negativeDisputedCount++;
                case HISTORICAL_ONLY -> historicalOnlyCount++;
                case UNRESOLVED -> unresolvedCount++;
            }
        }

        // Paging on the filtered list (safe against integer overflow with MAX_VALUE)
        long requestedFrom = (long) query.page() * (long) query.size();
        int fromIndex = (int) Math.min(requestedFrom, (long) filtered.size());
        long requestedTo = requestedFrom + (long) query.size();
        int toIndex = (int) Math.min(requestedTo, (long) filtered.size());
        List<ActivationPreviewCandidate> pagedItems = filtered.subList(fromIndex, toIndex);

        return new ActivationPreviewResult(
            filtered.size(),
            eligibleCount,
            alreadyRepresentedCount,
            valueOnlyCount,
            negativeDisputedCount,
            historicalOnlyCount,
            unresolvedCount,
            totalSourceQuantity.setScale(7, RoundingMode.HALF_UP),
            totalSourceAmount.setScale(4, RoundingMode.HALF_UP),
            totalDeltaQuantity.setScale(7, RoundingMode.HALF_UP),
            totalDeltaAmount.setScale(4, RoundingMode.HALF_UP),
            pagedItems,
            query.page(),
            query.size()
        );
    }

    private ActivationPreviewCandidate mapAndClassify(ResultSet rs, int rowNum) throws SQLException {
        String sourceKey = rs.getString("source_key");
        String accountCode = rs.getString("account_code");
        String accountName = rs.getString("account_name");
        UUID materialId = rs.getObject("material_id", UUID.class);
        String materialCode = rs.getString("material_code");
        String materialName = rs.getString("material_name");
        UUID warehouseId = rs.getObject("warehouse_id", UUID.class);
        String warehouseCode = rs.getString("warehouse_code");
        String warehouseName = rs.getString("warehouse_name");
        String stockCategory = rs.getString("stock_category");
        String uom = rs.getString("uom");
        String sourceCurrencyCode = rs.getString("source_currency_code");
        BigDecimal sourceQuantity = rs.getBigDecimal("source_quantity");
        BigDecimal sourceAmount = rs.getBigDecimal("source_amount");
        String sourceRowHash = rs.getString("row_sha256");
        String materialSourceId = rs.getString("material_source_id");
        String warehouseSourceId = rs.getString("warehouse_source_id");
        BigDecimal nativeOnHand = rs.getBigDecimal("native_on_hand");
        BigDecimal nativeTotalValue = rs.getBigDecimal("native_total_value");
        BigDecimal unprovenOnHand = rs.getBigDecimal("unproven_on_hand");
        long sourcePositionCount = rs.getLong("source_position_count");
        boolean incompatibleNativeIdentity = rs.getBoolean("incompatible_native_identity");
        boolean postSnapshotNativeActivity = rs.getBoolean("post_snapshot_native_activity");
        boolean postSnapshotSourceActivity = rs.getBoolean("post_snapshot_source_activity");

        if (sourceQuantity == null) sourceQuantity = BigDecimal.ZERO;
        if (sourceAmount == null) sourceAmount = BigDecimal.ZERO;
        if (nativeOnHand == null) nativeOnHand = BigDecimal.ZERO;
        if (nativeTotalValue == null) nativeTotalValue = BigDecimal.ZERO;
        if (unprovenOnHand == null) unprovenOnHand = BigDecimal.ZERO;

        // Classification Decision Matrix:
        SourcePositionClassification classification;
        BigDecimal representedQuantity = BigDecimal.ZERO;
        BigDecimal representedAmount = BigDecimal.ZERO;
        BigDecimal deltaQuantity = BigDecimal.ZERO;
        BigDecimal deltaAmount = BigDecimal.ZERO;
        String blockedReason = null;

        boolean isUnresolvedMapping = materialId == null || warehouseId == null
            || "00000000000000000000000000000000".equals(materialSourceId)
            || "00000000000000000000000000000000".equals(warehouseSourceId);

        if (isUnresolvedMapping) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Unresolved material or warehouse master binding";
        } else if ("FINISHED_PRODUCTS".equalsIgnoreCase(stockCategory)) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Finished product accounts require netting and stock reconciliation before activation";
        } else if (!"OWNED".equalsIgnoreCase(stockCategory)) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Source position has no verified owner identity for non-owned stock";
        } else if (!"UZS".equals(sourceCurrencyCode)) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Source currency is missing or unsupported for automatic activation";
        } else if (sourcePositionCount > 1) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Multiple source rows share one account/material/warehouse position without owner identity";
        } else if (incompatibleNativeIdentity) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Native account position has another owner, unit, or currency";
        } else if (postSnapshotNativeActivity || postSnapshotSourceActivity) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Post-snapshot native or source activity requires an approved event boundary";
        } else if (unprovenOnHand.signum() != 0) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Physical stock and committed posting evidence differ by " + unprovenOnHand;
        } else if (nativeOnHand.signum() < 0 || nativeTotalValue.signum() < 0) {
            classification = SourcePositionClassification.UNRESOLVED;
            blockedReason = "Native account quantity or value is negative and requires reconciliation";
        } else if (sourceQuantity.signum() < 0) {
            classification = SourcePositionClassification.NEGATIVE_DISPUTED;
            blockedReason = "Negative balance in source snapshot; retained as dispute evidence without operational availability";
        } else if (sourceAmount.signum() < 0) {
            classification = SourcePositionClassification.NEGATIVE_DISPUTED;
            blockedReason = "Negative source value requires accounting review before operational activation";
        } else if (sourceQuantity.signum() == 0 && sourceAmount.signum() != 0) {
            classification = SourcePositionClassification.VALUE_ONLY;
            deltaAmount = sourceAmount;
            blockedReason = "Value-only accounting position without physical stock";
        } else if (sourceQuantity.signum() == 0 && sourceAmount.signum() == 0) {
            if (nativeOnHand.signum() != 0 || nativeTotalValue.signum() != 0) {
                classification = SourcePositionClassification.UNRESOLVED;
                blockedReason = "Native account position exists against a zero source balance";
            } else {
                classification = SourcePositionClassification.HISTORICAL_ONLY;
                blockedReason = "Zero balance historical record";
            }
        } else {
            // Positive source quantity
            if (nativeOnHand.compareTo(sourceQuantity) > 0) {
                classification = SourcePositionClassification.UNRESOLVED;
                blockedReason = "Native account quantity exceeds the selected source position";
            } else if (nativeOnHand.compareTo(sourceQuantity) == 0
                && nativeTotalValue.compareTo(sourceAmount) != 0) {
                classification = SourcePositionClassification.UNRESOLVED;
                blockedReason = "Native account value differs from the selected source position";
            } else if (nativeOnHand.compareTo(sourceQuantity) == 0) {
                classification = SourcePositionClassification.ALREADY_REPRESENTED;
                representedQuantity = sourceQuantity;
                representedAmount = sourceAmount;
                blockedReason = "Already covered by native operational balance";
            } else {
                classification = SourcePositionClassification.ELIGIBLE_DELTA;
                representedQuantity = nativeOnHand;
                representedAmount = nativeTotalValue;
                deltaQuantity = sourceQuantity.subtract(nativeOnHand);
                deltaAmount = sourceAmount.subtract(nativeTotalValue).setScale(4, RoundingMode.UNNECESSARY);
                if (deltaAmount.signum() < 0) {
                    classification = SourcePositionClassification.UNRESOLVED;
                    deltaQuantity = BigDecimal.ZERO;
                    deltaAmount = BigDecimal.ZERO;
                    blockedReason = "Native account value exceeds source value for the remaining quantity";
                }
            }
        }

        return new ActivationPreviewCandidate(
            sourceKey,
            accountCode,
            accountName,
            materialId,
            materialCode,
            materialName,
            warehouseId,
            warehouseCode,
            warehouseName,
            stockCategory,
            uom,
            sourceQuantity,
            sourceAmount,
            representedQuantity,
            representedAmount,
            deltaQuantity,
            deltaAmount,
            classification,
            sourceRowHash,
            blockedReason
        );
    }

    private ActivationPreviewResult emptyResult(ActivationPreviewQuery query) {
        return new ActivationPreviewResult(
            0, 0, 0, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            Collections.emptyList(),
            query.page(),
            query.size()
        );
    }
}
