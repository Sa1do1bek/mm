package com.enterprise.mm.repository;

import com.enterprise.mm.entity.GoodsReceiptEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptEvidenceRepository extends JpaRepository<GoodsReceiptEvidence, UUID> {
    List<GoodsReceiptEvidence> findByTenantIdAndCompanyIdAndGoodsReceiptIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, UUID goodsReceiptId);
}
