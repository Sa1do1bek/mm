package com.enterprise.mm.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockMovementAllocationReferenceVerifierTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID INVENTORY_ITEM = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID BALANCE = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID COST_LAYER = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID PARENT_COST_LAYER = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID COST_MOVEMENT = UUID.fromString("60000000-0000-0000-0000-000000000001");

    private final InventoryItemRepository inventoryItemRepository = mock(InventoryItemRepository.class);
    private final StockBalanceRepository stockBalanceRepository = mock(StockBalanceRepository.class);
    private final InventoryCostLayerRepository costLayerRepository = mock(InventoryCostLayerRepository.class);
    private final StockCostMovementRepository costMovementRepository = mock(StockCostMovementRepository.class);
    private StockMovementAllocationReferenceVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new StockMovementAllocationReferenceVerifier(
            inventoryItemRepository,
            stockBalanceRepository,
            costLayerRepository,
            costMovementRepository
        );
    }

    @Test
    void acceptsEveryActiveReferenceInTheMovementScope() {
        stubValidReferences();

        assertThatCode(() -> verifier.verify(movement(), evidence()))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsCrossTenantInventoryItem() {
        stubValidReferences();
        InventoryItem item = inventoryItem(INVENTORY_ITEM);
        item.setTenantId("tenant-b");
        when(inventoryItemRepository.findByIdAndIsDeletedFalse(INVENTORY_ITEM)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inventory item", "tenant/company/material");
    }

    @Test
    void rejectsCrossCompanyBalance() {
        stubValidReferences();
        StockBalance balance = balance(BALANCE);
        balance.setCompanyId(UUID.randomUUID());
        when(stockBalanceRepository.findByIdAndIsDeletedFalse(BALANCE)).thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("balance", "tenant/company/material");
    }

    @Test
    void rejectsCrossMaterialCostLayer() {
        stubValidReferences();
        InventoryCostLayer layer = costLayer(COST_LAYER);
        layer.setMaterialId(UUID.randomUUID());
        when(costLayerRepository.findByIdAndIsDeletedFalse(COST_LAYER)).thenReturn(Optional.of(layer));

        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cost layer", "tenant/company/material");
    }

    @Test
    void rejectsMissingAndSoftDeletedReferences() {
        stubValidReferences();
        when(costLayerRepository.findByIdAndIsDeletedFalse(PARENT_COST_LAYER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parent cost layer", "missing or deleted");

        stubValidReferences();
        StockCostMovement deleted = costMovement(COST_MOVEMENT);
        deleted.setDeleted(true);
        when(costMovementRepository.findByIdAndIsDeletedFalse(COST_MOVEMENT)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cost movement", "missing or deleted");

        stubValidReferences();
        when(inventoryItemRepository.findByIdAndIsDeletedFalse(INVENTORY_ITEM)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inventory item", "missing or deleted");
    }

    @Test
    void validatesEachNonNullLineageReferenceIndependently() {
        stubValidReferences();
        StockCostMovement wrongTenant = costMovement(COST_MOVEMENT);
        wrongTenant.setTenantId("tenant-b");
        when(costMovementRepository.findByIdAndIsDeletedFalse(COST_MOVEMENT))
            .thenReturn(Optional.of(wrongTenant));

        assertThatThrownBy(() -> verifier.verify(movement(), evidence()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cost movement", "tenant/company/material");
    }

    private void stubValidReferences() {
        when(inventoryItemRepository.findByIdAndIsDeletedFalse(INVENTORY_ITEM))
            .thenReturn(Optional.of(inventoryItem(INVENTORY_ITEM)));
        when(stockBalanceRepository.findByIdAndIsDeletedFalse(BALANCE))
            .thenReturn(Optional.of(balance(BALANCE)));
        when(costLayerRepository.findByIdAndIsDeletedFalse(COST_LAYER))
            .thenReturn(Optional.of(costLayer(COST_LAYER)));
        when(costLayerRepository.findByIdAndIsDeletedFalse(PARENT_COST_LAYER))
            .thenReturn(Optional.of(costLayer(PARENT_COST_LAYER)));
        when(costMovementRepository.findByIdAndIsDeletedFalse(COST_MOVEMENT))
            .thenReturn(Optional.of(costMovement(COST_MOVEMENT)));
    }

    private StockMovement movement() {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.fromString("70000000-0000-0000-0000-000000000001"));
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setMaterialId(MATERIAL);
        movement.setSourceInventoryItemId(INVENTORY_ITEM);
        return movement;
    }

    private StockMovementAllocationEvidence evidence() {
        StockMovementCostLineageDraft lineage = new StockMovementCostLineageDraft(
            "CONSUMED", COST_LAYER, PARENT_COST_LAYER, COST_MOVEMENT,
            BigDecimal.ONE.negate(), BigDecimal.TEN.negate(), BigDecimal.TEN,
            "UZS", BigDecimal.ONE);
        StockMovementAllocationDraft allocation = new StockMovementAllocationDraft(
            "SOURCE", BALANCE, StockMovementStockKeySnapshot.empty(), StockMovementStockKeySnapshot.empty(),
            null, "PCS", BigDecimal.ONE.negate(), BigDecimal.ONE.negate(), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.TEN.negate(), BigDecimal.ONE.negate(), BigDecimal.TEN,
            "UZS", BigDecimal.ONE, List.of(lineage));
        StockMovement movement = movement();
        return new StockMovementAllocationEvidence(
            TENANT, COMPANY, movement.getId(), MATERIAL, INVENTORY_ITEM, List.of(allocation));
    }

    private InventoryItem inventoryItem(UUID id) {
        InventoryItem entity = new InventoryItem();
        scoped(entity, id);
        entity.setMaterialId(MATERIAL);
        return entity;
    }

    private StockBalance balance(UUID id) {
        StockBalance entity = new StockBalance();
        scoped(entity, id);
        entity.setMaterialId(MATERIAL);
        return entity;
    }

    private InventoryCostLayer costLayer(UUID id) {
        InventoryCostLayer entity = new InventoryCostLayer();
        scoped(entity, id);
        entity.setMaterialId(MATERIAL);
        return entity;
    }

    private StockCostMovement costMovement(UUID id) {
        StockCostMovement entity = new StockCostMovement();
        scoped(entity, id);
        entity.setMaterialId(MATERIAL);
        return entity;
    }

    private void scoped(com.enterprise.mm.common.BusinessEntity entity, UUID id) {
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setCompanyId(COMPANY);
        entity.setDeleted(false);
    }
}
