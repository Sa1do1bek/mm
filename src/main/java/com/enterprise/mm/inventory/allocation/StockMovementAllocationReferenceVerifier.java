package com.enterprise.mm.inventory.allocation;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StockMovementAllocationReferenceVerifier {
    private final InventoryItemRepository inventoryItemRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final InventoryCostLayerRepository costLayerRepository;
    private final StockCostMovementRepository costMovementRepository;

    public StockMovementAllocationReferenceVerifier(
        InventoryItemRepository inventoryItemRepository,
        StockBalanceRepository stockBalanceRepository,
        InventoryCostLayerRepository costLayerRepository,
        StockCostMovementRepository costMovementRepository
    ) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockBalanceRepository = stockBalanceRepository;
        this.costLayerRepository = costLayerRepository;
        this.costMovementRepository = costMovementRepository;
    }

    public void verify(StockMovement movement, StockMovementAllocationEvidence evidence) {
        if (movement.getSourceInventoryItemId() != null) {
            InventoryItem item = active(
                "inventory item",
                movement.getSourceInventoryItemId(),
                inventoryItemRepository.findByIdAndIsDeletedFalse(movement.getSourceInventoryItemId())
            );
            verifyScope("inventory item", item, item.getMaterialId(), movement);
        }

        Map<UUID, InventoryCostLayer> verifiedLayers = new HashMap<>();
        Map<UUID, StockCostMovement> verifiedCostMovements = new HashMap<>();
        Map<UUID, StockBalance> verifiedBalances = new HashMap<>();
        for (StockMovementAllocationDraft allocation : evidence.allocations()) {
            if (allocation.balanceId() != null) {
                StockBalance balance = verifiedBalances.computeIfAbsent(
                    allocation.balanceId(),
                    id -> active("balance", id, stockBalanceRepository.findByIdAndIsDeletedFalse(id))
                );
                verifyScope("balance", balance, balance.getMaterialId(), movement);
            }
            for (StockMovementCostLineageDraft lineage : allocation.costLineages()) {
                verifyLayer("cost layer", lineage.costLayerId(), movement, verifiedLayers);
                verifyLayer("parent cost layer", lineage.parentCostLayerIdSnapshot(), movement, verifiedLayers);
                if (lineage.sourceCostMovementId() != null) {
                    StockCostMovement costMovement = verifiedCostMovements.computeIfAbsent(
                        lineage.sourceCostMovementId(),
                        id -> active(
                            "cost movement",
                            id,
                            costMovementRepository.findByIdAndIsDeletedFalse(id)
                        )
                    );
                    verifyScope("cost movement", costMovement, costMovement.getMaterialId(), movement);
                }
            }
        }
    }

    private void verifyLayer(
        String label,
        UUID id,
        StockMovement movement,
        Map<UUID, InventoryCostLayer> verifiedLayers
    ) {
        if (id == null) {
            return;
        }
        InventoryCostLayer layer = verifiedLayers.computeIfAbsent(
            id,
            layerId -> active(label, layerId, costLayerRepository.findByIdAndIsDeletedFalse(layerId))
        );
        verifyScope(label, layer, layer.getMaterialId(), movement);
    }

    private <T extends BusinessEntity> T active(String label, UUID id, Optional<T> candidate) {
        T entity = candidate.orElseThrow(() -> new IllegalArgumentException(
            label + " reference is missing or deleted: " + id));
        if (entity.isDeleted()) {
            throw new IllegalArgumentException(label + " reference is missing or deleted: " + id);
        }
        return entity;
    }

    private void verifyScope(
        String label,
        BusinessEntity referenced,
        UUID referencedMaterialId,
        StockMovement movement
    ) {
        if (!Objects.equals(referenced.getTenantId(), movement.getTenantId())
            || !Objects.equals(referenced.getCompanyId(), movement.getCompanyId())
            || !Objects.equals(referencedMaterialId, movement.getMaterialId())) {
            throw new IllegalArgumentException(
                label + " reference is outside movement tenant/company/material scope: " + referenced.getId());
        }
    }
}
