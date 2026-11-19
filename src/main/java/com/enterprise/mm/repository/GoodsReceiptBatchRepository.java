package com.enterprise.mm.repository;

import com.enterprise.mm.entity.GoodsReceiptBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptBatchRepository extends JpaRepository<GoodsReceiptBatch, UUID> {
    List<GoodsReceiptBatch> findByTenantIdAndCompanyIdAndStockBatchIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID stockBatchId);
    List<GoodsReceiptBatch> findByTenantIdAndCompanyIdAndGoodsReceiptLineIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID goodsReceiptLineId
    );
}
