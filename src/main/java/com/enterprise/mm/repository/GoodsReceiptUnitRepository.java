package com.enterprise.mm.repository;

import com.enterprise.mm.entity.GoodsReceiptUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptUnitRepository extends JpaRepository<GoodsReceiptUnit, UUID> {
    List<GoodsReceiptUnit> findByTenantIdAndCompanyIdAndGoodsReceiptLineIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID goodsReceiptLineId
    );
}
