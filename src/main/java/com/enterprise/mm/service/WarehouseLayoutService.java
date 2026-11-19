package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.WarehouseLayout;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.WarehouseLayoutDtos.*;
import com.enterprise.mm.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class WarehouseLayoutService {
    private final WarehouseRepository warehouses;
    private final WarehouseLayoutRepository layouts;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final RoleDataScopeService scope;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Response get(UUID warehouseId) {
        warehouse(warehouseId);
        return find(warehouseId).map(this::response)
            .orElseGet(() -> new Response(warehouseId, null, new Layout(40, 24, 8, List.of()), null));
    }

    @Transactional
    public Response update(UUID warehouseId, UpdateRequest request) {
        Warehouse warehouse = warehouse(warehouseId);
        // Serialize first creation as well as subsequent saves on the existing owner row.
        entityManager.lock(warehouse, LockModeType.PESSIMISTIC_WRITE);
        var existing = find(warehouseId);
        if (!Objects.equals(existing.map(WarehouseLayout::getVersion).orElse(null), request.version()))
            throw new ConflictException("Warehouse layout changed; reload before saving");
        validateGeometry(request.layout());
        for (Item item : request.layout().items()) {
            if (item.storageLocationId() != null) {
                locations.findByIdAndIsDeletedFalse(item.storageLocationId())
                    .filter(l -> Objects.equals(l.getTenantId(), TenantContext.currentTenant())
                        && Objects.equals(l.getCompanyId(), CompanyContext.currentCompany())
                        && warehouseId.equals(l.getWarehouseId()))
                    .orElseThrow(() -> new NotFoundException("Storage location not found in this warehouse"));
            }
            if (item.storageBinId() != null) {
                bins.findByIdAndIsDeletedFalse(item.storageBinId())
                    .filter(b -> Objects.equals(b.getTenantId(), TenantContext.currentTenant())
                        && Objects.equals(b.getCompanyId(), CompanyContext.currentCompany())
                        && warehouseId.equals(b.getWarehouseId())
                        && (item.storageLocationId() == null || item.storageLocationId().equals(b.getStorageLocationId())))
                    .orElseThrow(() -> new NotFoundException("Storage bin not found in this warehouse location"));
            }
        }
        WarehouseLayout layout = existing.orElseGet(() -> {
            var value = new WarehouseLayout();
            value.setWarehouseId(warehouseId);
            value.setTenantId(TenantContext.currentTenant());
            value.setCompanyId(CompanyContext.currentCompany());
            return value;
        });
        layout.setLayout(request.layout());
        layout.setUpdatedAt(LocalDateTime.now());
        var actor = SecurityContextHolder.getContext().getAuthentication();
        layout.setUpdatedBy(actor == null ? "system" : actor.getName());
        return response(layouts.saveAndFlush(layout));
    }

    static void validateGeometry(Layout layout) {
        Set<String> ids = new HashSet<>();
        if (!finite(layout.width(), layout.depth(), layout.height())) invalid();
        for (Item item : layout.items()) {
            boolean turned = item.rotation() == 90 || item.rotation() == 270;
            double width = turned ? item.depth() : item.width();
            double depth = turned ? item.width() : item.depth();
            if (!ids.add(item.id()) || item.rotation() % 90 != 0
                || !finite(item.x(), item.y(), item.width(), item.depth(), item.height())
                || item.x() + width > layout.width() + 0.00001
                || item.y() + depth > layout.depth() + 0.00001
                || item.height() > layout.height() + 0.00001) invalid();
        }
    }

    private static boolean finite(double... numbers) { return Arrays.stream(numbers).allMatch(Double::isFinite); }
    private static void invalid() {
        throw new com.enterprise.mm.error.BusinessException("Layout objects must have unique IDs, quarter-turn rotations and fit inside the warehouse");
    }
    private Warehouse warehouse(UUID id) {
        scope.assertWarehouseAccess(id);
        return warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Warehouse not found in your company"));
    }
    private Optional<WarehouseLayout> find(UUID id) {
        return layouts.findByTenantIdAndCompanyIdAndWarehouseId(TenantContext.currentTenant(), CompanyContext.currentCompany(), id);
    }
    private Response response(WarehouseLayout layout) {
        return new Response(layout.getWarehouseId(), layout.getVersion(), layout.getLayout(), layout.getUpdatedAt());
    }
}
