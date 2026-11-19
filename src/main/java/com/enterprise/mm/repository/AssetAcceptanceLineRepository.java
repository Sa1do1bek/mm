package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetAcceptanceLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetAcceptanceLineRepository extends JpaRepository<AssetAcceptanceLine, UUID> {
    @EntityGraph(attributePaths = {"material"})
    List<AssetAcceptanceLine> findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID assetAcceptanceId);

    @Query("""
        select coalesce(sum(line.deliveredQuantity), 0)
        from AssetAcceptanceLine line
        join AssetAcceptance acceptance on acceptance.id = line.assetAcceptanceId
        where line.tenantId = :tenantId
          and line.companyId = :companyId
          and line.purchaseOrderItemId = :purchaseOrderItemId
          and line.isDeleted = false
          and acceptance.isDeleted = false
          and acceptance.postingStatus <> com.enterprise.mm.enums.AssetAcceptancePostingStatus.CANCELLED
        """)
    BigDecimal sumDeliveredQuantityByPurchaseOrderItem(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("purchaseOrderItemId") UUID purchaseOrderItemId
    );

    @Query("""
        select coalesce(sum(line.deliveredQuantity), 0)
        from AssetAcceptanceLine line
        join AssetAcceptance acceptance on acceptance.id = line.assetAcceptanceId
        where line.tenantId = :tenantId
          and line.companyId = :companyId
          and acceptance.asnId = :asnId
          and line.purchaseOrderItemId = :purchaseOrderItemId
          and line.isDeleted = false
          and acceptance.isDeleted = false
          and acceptance.postingStatus <> com.enterprise.mm.enums.AssetAcceptancePostingStatus.CANCELLED
        """)
    BigDecimal sumDeliveredQuantityByAsnAndPurchaseOrderItem(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("asnId") UUID asnId,
        @Param("purchaseOrderItemId") UUID purchaseOrderItemId
    );
}
