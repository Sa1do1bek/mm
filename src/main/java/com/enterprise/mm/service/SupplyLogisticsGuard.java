package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.Incoterm;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.IncotermRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupplyLogisticsGuard {
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final IncotermRepository incotermRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final ImportDeliveryRepository importDeliveryRepository;
    private final WarehouseRepository warehouseRepository;

    public Supplier supplier(UUID supplierId) {
        if (supplierId == null) {
            throw new BusinessException("supplierId is required");
        }
        return supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .filter(supplier -> com.enterprise.mm.security.AccessScope.canAccessCompany(supplier.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
    }
    public PurchaseOrder purchaseOrder(UUID purchaseOrderId) {
        if (purchaseOrderId == null) {
            return null;
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(po -> !po.isDeleted() && TenantContext.currentTenant().equals(po.getTenantId()))
            .filter(po -> com.enterprise.mm.security.AccessScope.canAccessCompany(po.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase order not found"));
    }
    public Incoterm incoterm(UUID incotermId) {
        if (incotermId == null) {
            return null;
        }
        return incotermRepository.findByIdAndIsDeletedFalse(incotermId)
            .filter(incoterm -> !incoterm.isDeleted() && TenantContext.currentTenant().equals(incoterm.getTenantId()))
            .filter(incoterm -> com.enterprise.mm.security.AccessScope.canAccessCompany(incoterm.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Incoterm not found"));
    }
    public AdvanceShippingNotice asn(UUID asnId) {
        if (asnId == null) {
            return null;
        }
        return asnRepository.findByIdAndIsDeletedFalse(asnId)
            .filter(asn -> !asn.isDeleted() && TenantContext.currentTenant().equals(asn.getTenantId()))
            .filter(asn -> com.enterprise.mm.security.AccessScope.canAccessCompany(asn.getCompanyId()))
            .orElseThrow(() -> new BusinessException("ASN not found"));
    }
    public ImportDelivery importDelivery(UUID importDeliveryId) {
        if (importDeliveryId == null) {
            throw new BusinessException("importDeliveryId is required");
        }
        return importDeliveryRepository.findByIdAndIsDeletedFalse(importDeliveryId)
            .filter(delivery -> !delivery.isDeleted() && TenantContext.currentTenant().equals(delivery.getTenantId()))
            .filter(delivery -> com.enterprise.mm.security.AccessScope.canAccessCompany(delivery.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Import delivery not found"));
    }
    public Warehouse warehouse(UUID warehouseId) {
        if (warehouseId == null) {
            return null;
        }
        return warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)
            .filter(warehouse -> !warehouse.isDeleted() && TenantContext.currentTenant().equals(warehouse.getTenantId()))
            .filter(warehouse -> com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Warehouse not found"));
    }
}

