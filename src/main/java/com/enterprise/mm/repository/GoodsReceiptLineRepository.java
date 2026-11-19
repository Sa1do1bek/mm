package com.enterprise.mm.repository;

import com.enterprise.mm.entity.GoodsReceiptLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, UUID> {
    List<GoodsReceiptLine> findByTenantIdAndCompanyIdAndGoodsReceiptIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID goodsReceiptId);
    List<GoodsReceiptLine> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID purchaseOrderId);
    List<GoodsReceiptLine> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, List<UUID> purchaseOrderItemIds);
    List<GoodsReceiptLine> findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID stockMovementId);
    List<GoodsReceiptLine> findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID purchaseOrderItemId);
}
