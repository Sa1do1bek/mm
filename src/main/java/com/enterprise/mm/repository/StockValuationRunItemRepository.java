package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockValuationRunItem;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.enterprise.mm.payload.ValuationStatsProjection;

public interface StockValuationRunItemRepository extends JpaRepository<StockValuationRunItem, UUID> {
    long countByValuationRunId(UUID valuationRunId);

    @EntityGraph(attributePaths = {"material", "material.unitOfMeasure"})
    Page<StockValuationRunItem> findByValuationRunIdOrderBySequenceNoAsc(UUID valuationRunId, Pageable pageable);

    @EntityGraph(attributePaths = {"material", "material.unitOfMeasure"})
    @Query("""
        select item from StockValuationRunItem item
        where item.valuationRunId = :runId
          and exists (select run.id from StockValuationRun run
              where run.id = item.valuationRunId and run.tenantId = :tenantId
                and run.companyId = :companyId and run.isDeleted = false)
          and (:warehouseId is null or item.warehouseId = :warehouseId)
          and (:available is null or item.available = :available)
          and (:searchPattern is null
              or lower(coalesce(item.materialName, '')) like :searchPattern escape '!'
              or exists (select material.id from Material material
                  where material.id = item.materialId and material.tenantId = :tenantId
                    and material.companyId = :companyId
                    and lower(coalesce(material.code, '')) like :searchPattern escape '!'))
        order by item.sequenceNo asc
        """)
    Page<StockValuationRunItem> search(
        @Param("runId") UUID runId,
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("searchPattern") String searchPattern,
        @Param("warehouseId") UUID warehouseId,
        @Param("available") Boolean available,
        Pageable pageable
    );

    @Query(value = """
        WITH LatestItems AS (
            SELECT i.total_value, i.material_id,
                   ROW_NUMBER() OVER(PARTITION BY i.material_id, i.warehouse_id ORDER BY i.calculated_at DESC) as rn
            FROM stock_valuation_run_items i
            INNER JOIN stock_valuation_runs r ON r.id = i.valuation_run_id
            WHERE r.tenant_id = :tenantId AND r.company_id = :companyId 
              AND r.status = 'COMPLETED' AND r.is_delete = false
        )
        SELECT 
            COALESCE(SUM(total_value), 0) as totalValue, 
            COUNT(DISTINCT material_id) as materialsCount, 
            0 as totalImpairment
        FROM LatestItems 
        WHERE rn = 1
    """, nativeQuery = true)
    ValuationStatsProjection getGlobalStatisticsNative(
        @Param("tenantId") String tenantId, 
        @Param("companyId") UUID companyId
    );
}
