package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.QualityNonconformanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QualityControlGuard {
    private final MaterialCatalogGuard materialCatalogGuard;
    private final RoleDataScopeService roleDataScopeService;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final WarehouseRepository warehouseRepository;
    private final QualityInspectionRepository inspectionRepository;
    private final QualityNonconformanceRepository nonconformanceRepository;

    public Material material(UUID id) {
        return materialCatalogGuard.requireActiveMaterial(id, "materialId");
    }
    public Supplier supplier(UUID id) {
        if (id == null) {
            return null;
        }
        return supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
    }
    public PurchaseOrder purchaseOrder(UUID id) {
        if (id == null) {
            return null;
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase order not found"));
    }
    public PurchaseOrderItem purchaseOrderItem(UUID id) {
        if (id == null) {
            return null;
        }
        return purchaseOrderItemRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase order item not found"));
    }
    public StockMovement stockMovement(UUID id) {
        if (id == null) {
            return null;
        }
        return stockMovementRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Stock movement not found"));
    }
    public Warehouse warehouse(UUID id) {
        roleDataScopeService.assertWarehouseAccess(id);
        if (id == null) {
            throw new BusinessException("warehouseId is required");
        }
        return warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Warehouse not found"));
    }
    public QualityInspection inspection(UUID id) {
        if (id == null) {
            throw new BusinessException("qualityInspectionId is required");
        }
        return inspectionRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .filter(item -> roleDataScopeService.canAccessWarehouse(item.getWarehouseId()))
            .orElseThrow(() -> new BusinessException("Quality inspection not found"));
    }
    public QualityNonconformance nonconformance(UUID id) {
        if (id == null) {
            throw new BusinessException("qualityNonconformanceId is required");
        }
        return nonconformanceRepository.findByIdAndIsDeletedFalse(id)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .filter(item -> inspection(item.getQualityInspectionId()) != null)
            .orElseThrow(() -> new BusinessException("Quality nonconformance not found"));
    }
}

