package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.*;
import com.enterprise.mm.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class WarehouseReceiptProfileService {
    private final RoleDataScopeService scope;
    private final WarehouseRepository warehouses;
    private final WarehouseReceiptProfileRepository profiles;
    private final MaterialGroupRepository groups;
    private final InventoryScopeLockService inventoryLocks;

    @Transactional(readOnly = true)
    public Profile get(UUID warehouseId) {
        checkWarehouse(warehouseId);
        return find(warehouseId).map(this::response)
            .orElseGet(() -> new Profile(warehouseId, false, null, null, List.of()));
    }

    @Transactional
    public Profile update(UUID warehouseId, UpdateRequest request) {
        checkWarehouse(warehouseId);
        // Same lock and order as manual receipt, including first profile creation.
        inventoryLocks.lock(CompanyContext.currentCompany(), List.of(warehouseId));
        var existing = find(warehouseId);
        if (!Objects.equals(existing.map(WarehouseReceiptProfile::getVersion).orElse(null), request.version()))
            throw new ConflictException("Warehouse receipt profile changed; reload before saving");
        Set<UUID> seen = new HashSet<>();
        if (request.rules() == null || request.rules().size() > 500)
            throw new BusinessException("At most 500 material group rules are allowed");
        for (Rule rule : request.rules()) {
            if (rule == null || rule.materialGroupId() == null || rule.effect() == null || !seen.add(rule.materialGroupId()))
                throw new BusinessException("Material group rules must be complete and unique");
            groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(),
                CompanyContext.currentCompany(), rule.materialGroupId()).filter(g -> "ACTIVE".equals(g.getStatus()))
                .orElseThrow(() -> new NotFoundException("Active material group not found in your company"));
        }
        WarehouseReceiptProfile profile = existing.orElseGet(() -> {
            var p = new WarehouseReceiptProfile(); p.setWarehouseId(warehouseId);
            p.setTenantId(TenantContext.currentTenant()); p.setCompanyId(CompanyContext.currentCompany());
            p.setCreatedAt(LocalDateTime.now()); p.setCreatedBy(actor()); return p;
        });
        profile.setPurpose(request.purpose());
        // Bump the owner version even when only collection contents change.
        profile.setUpdatedAt(LocalDateTime.now()); profile.setUpdatedBy(actor());
        profile.getRules().clear();
        request.rules().forEach(r -> profile.getRules().add(new WarehouseReceiptProfile.GroupRule(
            r.materialGroupId(), r.effect(), r.includeDescendants())));
        return response(profiles.saveAndFlush(profile));
    }

    private Optional<WarehouseReceiptProfile> find(UUID warehouseId) {
        return profiles.findByTenantIdAndCompanyIdAndWarehouseId(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId);
    }
    private void checkWarehouse(UUID id) {
        scope.assertWarehouseAccess(id);
        warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException("Warehouse not found in your company"));
    }
    private Profile response(WarehouseReceiptProfile p) {
        return new Profile(p.getWarehouseId(), true, p.getVersion(), p.getPurpose(), p.getRules().stream().sorted(Comparator.comparing(r -> r.getMaterialGroupId().toString()))
            .map(r -> new Rule(r.getMaterialGroupId(), r.getEffect(), r.isIncludeDescendants())).toList());
    }
    private String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }
}
