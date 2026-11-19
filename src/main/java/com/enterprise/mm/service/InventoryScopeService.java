package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.inventory.policy.InventoryFreezePolicy;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class InventoryScopeService {
    private final InventoryDocumentRepository documents;
    private final InventoryDocumentMaterialRepository selectedMaterials;
    private final InventoryDocumentMaterialGroupRepository selectedGroups;
    private final InventoryItemRepository items;
    private final MaterialRepository materials;
    private final WarehouseRepository warehouses;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final InventoryScopeLockService locks;
    private final RoleDataScopeService authority;
    private final DepartmentWarehouseAccessRepository departmentAccess;
    private final DepartmentRepository departments;
    public void lock(InventoryDocument d) {locks.lock(d.getCompanyId(),d.isGlobalWarehouse()?List.of():warehouseIds(d));}
    public List<InventoryDocument> active(UUID company) {
        return documents.findActiveFreezes(TenantContext.currentTenant(),company,InventoryFreezePolicy.FROZEN.stream().map(Enum::name).toList());
    }
    public void validate(InventoryDocument d) {
        if (d.getScopeMode() != null) {
            if (d.isGlobalWarehouse()) throw new BadRequestException("globalWarehouse cannot be combined with scopeMode");
            if (d.getResolvedWarehouseIds().isEmpty()) throw new BadRequestException("Inventory scope has no warehouses");
            if (d.getResolvedWarehouseIds().size() > 1 && (d.getStorageLocationId() != null || d.getStorageBinId() != null))
                throw new BadRequestException("A location or bin requires one warehouse");
            for (UUID id : d.getResolvedWarehouseIds()) validateWarehouse(d, id);
            if (d.getStorageLocationId() != null || d.getStorageBinId() != null) validatePosition(d, d.getResolvedWarehouseIds().iterator().next());
            return;
        }
        if(d.isGlobalWarehouse()) {
            if(d.getStorageLocationId()!=null || d.getStorageBinId()!=null)throw new BadRequestException("A location or bin requires a single warehouse");
            if(authority.currentScope().warehouseScoped())throw new org.springframework.security.access.AccessDeniedException("Company-wide inventory requires authority over all warehouses");
        } else {
            validateWarehouse(d, d.getWarehouseId());
            validatePosition(d, d.getWarehouseId());
        }
    }
    public void prepareScope(InventoryDocument d) {
        if (d.getScopeMode() == null) {
            if (!d.getDepartmentIds().isEmpty() || !d.getSelectedWarehouseIds().isEmpty())
                throw new BadRequestException("scopeMode is required for departmentIds or warehouseIds");
            d.getResolvedWarehouseIds().clear();
            return;
        }
        if (d.isGlobalWarehouse() || d.getWarehouseId() != null)
            throw new BadRequestException("Legacy warehouseId/globalWarehouse cannot be combined with scopeMode");
        Set<UUID> resolved = new LinkedHashSet<>();
        if (d.getScopeMode() == com.enterprise.mm.enums.InventoryScopeMode.DEPARTMENTS
            || d.getScopeMode() == com.enterprise.mm.enums.InventoryScopeMode.MIXED) {
            if (d.getScopeMode() == com.enterprise.mm.enums.InventoryScopeMode.DEPARTMENTS
                && (d.getDepartmentIds().isEmpty() || !d.getSelectedWarehouseIds().isEmpty()))
                throw new BadRequestException("DEPARTMENTS requires departmentIds and no warehouseIds");
            if (d.getScopeMode() == com.enterprise.mm.enums.InventoryScopeMode.MIXED
                && d.getDepartmentIds().isEmpty() && d.getSelectedWarehouseIds().isEmpty())
                throw new BadRequestException("MIXED requires departmentIds or warehouseIds");
            for (UUID departmentId : d.getDepartmentIds()) {
                if (departmentId == null) throw new BadRequestException("departmentIds cannot contain null");
                departments.findByIdAndIsDeletedFalse(departmentId)
                    .filter(department -> TenantContext.currentTenant().equals(department.getTenantId())
                        && d.getCompanyId().equals(department.getCompanyId()) && "ACTIVE".equals(department.getStatus()))
                    .orElseThrow(() -> new BadRequestException("Department is outside inventory company or inactive"));
                Set<UUID> mapped = departmentAccess.findActiveWarehouseIds(TenantContext.currentTenant(), d.getCompanyId(), departmentId);
                if (mapped.isEmpty()) throw new BadRequestException("Department has no active warehouses: " + departmentId);
                resolved.addAll(mapped);
            }
            if (d.getScopeMode() == com.enterprise.mm.enums.InventoryScopeMode.MIXED)
                resolved.addAll(d.getSelectedWarehouseIds());
        } else {
            if (!d.getDepartmentIds().isEmpty() || d.getSelectedWarehouseIds().isEmpty())
                throw new BadRequestException("WAREHOUSES requires warehouseIds and no departmentIds");
            resolved.addAll(d.getSelectedWarehouseIds());
        }
        if (resolved.contains(null)) throw new BadRequestException("warehouseIds cannot contain null");
        d.setResolvedWarehouseIds(resolved);
        validate(d);
    }
    private void validateWarehouse(InventoryDocument d, UUID id) {
        warehouses.findByIdAndIsDeletedFalse(id).filter(w -> scoped(w, d.getCompanyId()) && "ACTIVE".equals(w.getStatus()))
            .orElseThrow(() -> new BadRequestException("Warehouse is outside inventory company or inactive"));
        if (!authority.canAccessWarehouse(id)) throw new org.springframework.security.access.AccessDeniedException("Inventory warehouse authority is required");
    }
    private void validatePosition(InventoryDocument d, UUID id) {
        if (d.getStorageLocationId() != null) locations.findByIdAndIsDeletedFalse(d.getStorageLocationId())
            .filter(l -> scoped(l, d.getCompanyId()) && id.equals(l.getWarehouseId()))
            .orElseThrow(() -> new BadRequestException("Location is outside inventory warehouse"));
        if (d.getStorageBinId() != null) {
            var bin = bins.findByIdAndIsDeletedFalse(d.getStorageBinId())
                .filter(b -> scoped(b, d.getCompanyId()) && id.equals(b.getWarehouseId()))
                .orElseThrow(() -> new BadRequestException("Bin is outside inventory warehouse"));
            if (d.getStorageLocationId() == null || !d.getStorageLocationId().equals(bin.getStorageLocationId()))
                throw new BadRequestException("Bin requires its parent location");
        }
    }
    public List<UUID> warehouseIds(InventoryDocument d) {
        if (d.getScopeMode() != null) return d.getResolvedWarehouseIds().stream().sorted().toList();
        return d.getWarehouseId() == null ? List.of() : List.of(d.getWarehouseId());
    }
    public boolean includesWarehouse(InventoryDocument d, UUID warehouseId) {
        return d.isGlobalWarehouse() || warehouseId != null && (d.getScopeMode() == null
            ? warehouseId.equals(d.getWarehouseId()) : d.getResolvedWarehouseIds().contains(warehouseId));
    }
    private boolean scoped(BusinessEntity e,UUID company){return TenantContext.currentTenant().equals(e.getTenantId())&&company.equals(e.getCompanyId());}
    public boolean contains(InventoryDocument d,UUID warehouse,UUID location,UUID bin,UUID material) {
        return includesWarehouse(d, warehouse)
            && (d.getStorageLocationId()==null||d.getStorageLocationId().equals(location))
            && (d.getStorageBinId()==null||d.getStorageBinId().equals(bin)) && includesMaterial(d,material);
    }
    private Set<UUID> materialIds(InventoryDocument d){return selectedMaterials.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(d.getTenantId(),d.getId()).stream().map(InventoryDocumentMaterial::getMaterialId).collect(java.util.stream.Collectors.toSet());}
    private Set<UUID> groupIds(InventoryDocument d){return selectedGroups.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(d.getTenantId(),d.getId()).stream().map(InventoryDocumentMaterialGroup::getMaterialGroupId).collect(java.util.stream.Collectors.toSet());}
    public boolean includesMaterial(InventoryDocument d,UUID material) {
        if(material==null)return true;
        var selected=materialIds(d);var groups=groupIds(d);
        if(selected.isEmpty()&&groups.isEmpty() || selected.contains(material))return true;
        if(items.existsByTenantIdAndInventoryDocumentIdAndMaterialIdAndIsDeletedFalse(d.getTenantId(),d.getId(),material))return true;
        return materials.findByIdAndIsDeletedFalse(material).map(m->groups.contains(m.getMaterialGroupId())).orElse(false);
    }
    public void requireNoOverlap(InventoryDocument d) {
        if(!d.isFreezeOperations())return;
        for(var other:active(d.getCompanyId()))if(!d.getId().equals(other.getId()) && overlaps(d,other))throw new ConflictException("Another active physical count intersects this scope", "INVENTORY_SCOPE_FROZEN");
    }
    public boolean overlaps(InventoryDocument a,InventoryDocument b) {
        if(!a.isGlobalWarehouse()&&!b.isGlobalWarehouse() && warehouseIds(a).stream().noneMatch(warehouseIds(b)::contains))return false;
        if(!overlap(a.getStorageLocationId(),b.getStorageLocationId())||!overlap(a.getStorageBinId(),b.getStorageBinId()))return false;
        var am=materialIds(a);var ag=groupIds(a);var bm=materialIds(b);var bg=groupIds(b);
        if(am.isEmpty()&&ag.isEmpty() || bm.isEmpty()&&bg.isEmpty())return true;
        // Snapshot members remain frozen even if their material master moves to another group.
        am.addAll(items.findSnapshotMaterialIds(a.getTenantId(),a.getId()));
        bm.addAll(items.findSnapshotMaterialIds(b.getTenantId(),b.getId()));
        if(ag.stream().anyMatch(bg::contains))return true;
        return am.stream().anyMatch(m->includesMaterial(b,m)) || bm.stream().anyMatch(m->includesMaterial(a,m));
    }
    private boolean overlap(UUID a,UUID b){return a==null||b==null||a.equals(b);}
}
