package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.security.AccessScope;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MaterialCommandViewScopeGuard {
    private final MaterialRepository materialRepository;
    private final RoleDataScopeService roleDataScopeService;

    public MaterialScope requireGoods(UUID materialId, UUID requestedWarehouseId) {
        UUID warehouseId = resolveWarehouse(requestedWarehouseId);
        MaterialScope scope = requireGoods(materialId);
        return new MaterialScope(
            scope.tenantId(), scope.companyId(), scope.materialId(), scope.material(), warehouseId);
    }

    public MaterialScope requireGoods(UUID materialId) {
        String tenantId = TenantContext.currentTenant();
        Material material = materialRepository.findMaterial360ByTenantAndId(tenantId, materialId)
            .filter(candidate -> AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException(
                "Material not found: " + materialId,
                "error.entity.not_found",
                "Material",
                materialId));

        if (material.getMxik() != null && "SERVICE".equalsIgnoreCase(material.getMxik().getType())) {
            throw new BusinessException(
                "Material 360 is available only for goods materials",
                "material360.goods_only");
        }

        return new MaterialScope(tenantId, material.getCompanyId(), material.getId(), material);
    }

    public UUID resolveWarehouse(UUID requestedWarehouseId) {
        // Material detail sections share the explicitly selected warehouse in the URL.
        // Read it only here; it must never change global document/command scope.
        if (requestedWarehouseId == null && org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            String value = attributes.getRequest().getParameter("warehouseId");
            if (value != null && !value.isBlank()) {
                try { requestedWarehouseId = UUID.fromString(value); }
                catch (IllegalArgumentException ex) { throw new AccessDeniedException("material360.domain.denied"); }
            }
        }
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        if (!scope.warehouseScoped()) {
            return requestedWarehouseId;
        }
        UUID assignedWarehouseId = requestedWarehouseId == null ? scope.warehouseId() : requestedWarehouseId;
        if (assignedWarehouseId == null || !scope.warehouseIds().contains(assignedWarehouseId)) {
            throw new AccessDeniedException("material360.domain.denied");
        }
        return assignedWarehouseId;
    }

    public record MaterialScope(
        String tenantId,
        UUID companyId,
        UUID materialId,
        Material material,
        UUID warehouseId
    ) {
        public MaterialScope(String tenantId, UUID companyId, UUID materialId, Material material) {
            this(tenantId, companyId, materialId, material, null);
        }
    }
}
