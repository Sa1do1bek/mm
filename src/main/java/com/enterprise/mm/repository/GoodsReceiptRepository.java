package com.enterprise.mm.repository;

import com.enterprise.mm.entity.GoodsReceipt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {
    List<GoodsReceipt> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID purchaseOrderId);

    Optional<GoodsReceipt> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID purchaseOrderId, String idempotencyKey);
}
