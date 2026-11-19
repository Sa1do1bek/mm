package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ReceivingMode;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ReceivingModeResponse;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.security.AccessScope;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderReceivingModeService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository itemRepository;

    @Transactional(readOnly = true)
    public ReceivingModeResponse receivingMode(UUID purchaseOrderId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .filter(entity -> AccessScope.canAccessCompany(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + purchaseOrderId));
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
            .stream()
            .filter(item -> Objects.equals(item.getCompanyId(), po.getCompanyId()))
            .toList();
        return toResponse(determineReceivingMode(items));
    }

    public ReceivingMode determineReceivingMode(List<PurchaseOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return ReceivingMode.NONE;
        }
        EnumSet<ReceivingMode> modes = EnumSet.noneOf(ReceivingMode.class);
        for (PurchaseOrderItem item : items) {
            if (isServiceLine(item)) {
                modes.add(ReceivingMode.SERVICE_ACCEPTANCE);
            } else if (item.getMaterialTypeSnapshot() == MaterialType.TMB) {
                modes.add(ReceivingMode.GOODS_RECEIPT);
            } else if (item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET) {
                modes.add(ReceivingMode.ASSET_ACCEPTANCE);
            }
        }
        if (modes.isEmpty()) {
            return ReceivingMode.NONE;
        }
        return modes.size() == 1 ? modes.iterator().next() : ReceivingMode.LEGACY_MIXED;
    }

    public void requireSingleReceivingMode(String documentName, List<PurchaseOrderItem> items) {
        ReceivingMode mode = determineReceivingMode(items);
        if (mode == ReceivingMode.LEGACY_MIXED) {
            throw new com.enterprise.mm.error.BusinessException(
                "PROCUREMENT_RECEIVING_TYPE_MIXED: " + documentName + " cannot mix TMB, fixed asset, and service receiving lines",
                "procurement.receivingType.mixed_document",
                documentName
            );
        }
    }

    private ReceivingModeResponse toResponse(ReceivingMode mode) {
        return switch (mode) {
            case GOODS_RECEIPT -> new ReceivingModeResponse(mode.name(), "goods-receipt", null, "Use Goods Receipt for TMB stock lines");
            case ASSET_ACCEPTANCE -> new ReceivingModeResponse(mode.name(), "asset-acceptances", null, "Use Asset Acceptance for fixed asset lines");
            case SERVICE_ACCEPTANCE -> new ReceivingModeResponse(mode.name(), "service-acceptance-acts", null, "Use Service Acceptance for service lines");
            case LEGACY_MIXED -> new ReceivingModeResponse(mode.name(), null, "PROCUREMENT_RECEIVING_TYPE_MIXED", "This legacy purchase order mixes receiving categories and cannot be routed automatically");
            case NONE -> new ReceivingModeResponse(mode.name(), null, "NO_RECEIVABLE_LINES", "This purchase order has no receivable lines");
        };
    }

    private boolean isServiceLine(PurchaseOrderItem item) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.SERVICE || item.getMaterialTypeSnapshot() == MaterialType.SERVICES;
    }
}
