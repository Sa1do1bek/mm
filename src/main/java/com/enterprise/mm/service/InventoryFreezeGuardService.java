package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.error.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class InventoryFreezeGuardService {
    private final InventoryScopeLockService locks;
    private final InventoryScopeService scopes;
    private final InventoryAdjustmentPermitService permits;
    public void ensureWarehouseNotFrozen(UUID companyId,UUID warehouseId,String referenceType) {
        if(warehouseId==null)return;
        var company=companyId==null?CompanyContext.currentCompany():companyId;
        locks.lock(company,List.of(warehouseId));
        // Unknown stock keys must conservatively check every location/material scope in the warehouse.
        for(var d:scopes.active(company))if(scopes.includesWarehouse(d,warehouseId))blocked();
    }
    public void ensureStockKeyAllowed(UUID companyId,UUID warehouseId,UUID locationId,UUID binId,UUID materialId) {
        if(warehouseId==null)return;
        var company=companyId==null?CompanyContext.currentCompany():companyId;
        locks.lock(company,List.of(warehouseId));
        for(var d:scopes.active(company))if(scopes.contains(d,warehouseId,locationId,binId,materialId))blocked();
    }
    public void ensureMovementAllowed(StockMovement movement) {
        if(movement==null)return;
        var company=movement.getCompanyId()==null?CompanyContext.currentCompany():movement.getCompanyId();
        var warehouses=new ArrayList<UUID>();if(movement.getWarehouseId()!=null)warehouses.add(movement.getWarehouseId());if(movement.getTargetWarehouseId()!=null)warehouses.add(movement.getTargetWarehouseId());
        if(warehouses.isEmpty())return;locks.lock(company,warehouses);
        for(var d:scopes.active(company)) {
            boolean intersects=scopes.contains(d,movement.getWarehouseId(),movement.getStorageLocationId(),movement.getStorageBinId(),movement.getMaterialId())
                || movement.getTargetWarehouseId()!=null&&scopes.contains(d,movement.getTargetWarehouseId(),movement.getTargetStorageLocationId(),movement.getTargetStorageBinId(),movement.getMaterialId());
            if(intersects&&!permits.allows(movement,d))blocked();
        }
    }
    private void blocked(){throw new BusinessException("Stock operations are blocked by the active physical count for this scope","INVENTORY_SCOPE_FROZEN");}
}
