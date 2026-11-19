package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MobileWarehouseOperation;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MobileWarehouseOperationType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.repository.MobileWarehouseOperationRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WmsValuationReconciliationService {
    private final MobileWarehouseOperationRepository wmsOperationRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockBalanceRepository stockBalanceRepository;

    @Transactional(readOnly = true)
    public List<InventoryValuationReconciliationIssueResponse> run(UUID warehouseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<MobileWarehouseOperation> operations = wmsOperationRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId).stream()
            .filter(operation -> warehouseId == null || warehouseId.equals(operation.getWarehouseId()) || warehouseId.equals(operation.getTargetWarehouseId()))
            .toList();
        List<StockMovement> movements = stockMovementRepository.findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(tenantId, companyId).stream()
            .filter(movement -> warehouseId == null || warehouseId.equals(movement.getWarehouseId()) || warehouseId.equals(movement.getTargetWarehouseId()))
            .toList();

        Map<UUID, StockMovement> movementById = new HashMap<>();
        for (StockMovement movement : movements) {
            movementById.put(movement.getId(), movement);
        }
        Set<UUID> wmsMovementIds = new HashSet<>();
        java.util.ArrayList<InventoryValuationReconciliationIssueResponse> issues = new java.util.ArrayList<>();

        for (MobileWarehouseOperation operation : operations) {
            if (operation.getStockMovementId() == null || !movementById.containsKey(operation.getStockMovementId())) {
                issues.add(issue("WMS_MOVEMENT_WITHOUT_VALUATION_EFFECT", "BLOCKER", operation.getMaterialId(), operation.getWarehouseId(), null, operation.getStockMovementId(), "Posted WMS operation has no valuation stock movement"));
                continue;
            }
            wmsMovementIds.add(operation.getStockMovementId());
            StockMovement movement = movementById.get(operation.getStockMovementId());
            if (!Objects.equals(operation.getMaterialId(), movement.getMaterialId())
                || !Objects.equals(operation.getWarehouseId(), movement.getWarehouseId())
                || nvl(operation.getQuantity()).compareTo(nvl(movement.getQuantity())) != 0) {
                issues.add(issue("WMS_VALUATION_MOVEMENT_MISMATCH", "BLOCKER", operation.getMaterialId(), operation.getWarehouseId(), null, movement.getId(), "WMS operation does not match valuation movement material, warehouse, or quantity"));
            }
        }

        for (StockMovement movement : movements) {
            if (!"POSTED".equals(movement.getStatus()) || wmsMovementIds.contains(movement.getId()) || controlledValuationMovement(movement)) {
                continue;
            }
            issues.add(issue("VALUATION_MOVEMENT_NOT_BACKED_BY_WMS", "WARNING", movement.getMaterialId(), movement.getWarehouseId(), movement.getStockBatchId(), movement.getId(), "Posted valuation stock movement is not backed by WMS operation"));
        }

        compareQuantities(warehouseId, operations, issues);
        return issues;
    }

    private void compareQuantities(UUID warehouseId, List<MobileWarehouseOperation> operations, java.util.List<InventoryValuationReconciliationIssueResponse> issues) {
        Map<StockKey, BigDecimal> wmsQuantity = new HashMap<>();
        for (MobileWarehouseOperation operation : operations) {
            BigDecimal signed = signedQuantity(operation.getOperationType(), operation.getQuantity());
            wmsQuantity.merge(new StockKey(operation.getMaterialId(), operation.getWarehouseId()), signed, BigDecimal::add);
            if (operation.getOperationType() == MobileWarehouseOperationType.PUT_AWAY && operation.getTargetWarehouseId() != null) {
                wmsQuantity.merge(new StockKey(operation.getMaterialId(), operation.getTargetWarehouseId()), nvl(operation.getQuantity()), BigDecimal::add);
            }
        }
        for (var balance : stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())) {
            if (warehouseId != null && !warehouseId.equals(balance.getWarehouseId())) {
                continue;
            }
            BigDecimal wms = wmsQuantity.getOrDefault(new StockKey(balance.getMaterialId(), balance.getWarehouseId()), BigDecimal.ZERO);
            if (wms.compareTo(nvl(balance.getQuantity())) != 0) {
                issues.add(issue("WMS_VALUATION_QUANTITY_MISMATCH", "WARNING", balance.getMaterialId(), balance.getWarehouseId(), balance.getStockBatchId(), null, "WMS quantity does not match valuation stock balance quantity"));
            }
        }
    }

    private boolean controlledValuationMovement(StockMovement movement) {
        if (movement.getMovementType() == MovementType.ADJUSTMENT || movement.getMovementType() == MovementType.REVERSAL) {
            return true;
        }
        String ref = movement.getReferenceType();
        return ref != null && (ref.equals("InventoryDocument") || ref.equals("OPENING_BALANCE") || ref.equals("PurchaseOrder"));
    }

    private BigDecimal signedQuantity(MobileWarehouseOperationType type, BigDecimal quantity) {
        BigDecimal value = nvl(quantity);
        return switch (type) {
            case RECEIVE -> value;
            case PICK, SHIP, PUT_AWAY -> value.negate();
        };
    }

    private InventoryValuationReconciliationIssueResponse issue(String type, String severity, UUID materialId, UUID warehouseId, UUID stockBatchId, UUID stockMovementId, String message) {
        String seed = String.join("|", type, severity, Objects.toString(materialId, ""), Objects.toString(warehouseId, ""), Objects.toString(stockBatchId, ""), Objects.toString(stockMovementId, ""), message);
        return new InventoryValuationReconciliationIssueResponse(
            UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
            type,
            severity,
            CompanyContext.currentCompany(),
            materialId,
            warehouseId,
            stockBatchId,
            stockMovementId,
            null,
            null,
            null,
            null,
            null,
            null,
            message
        );
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record StockKey(UUID materialId, UUID warehouseId) {
    }
}
