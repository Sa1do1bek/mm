package com.enterprise.mm.repository;

import com.enterprise.mm.entity.StockValuationHistory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockValuationHistoryRepository extends JpaRepository<StockValuationHistory, UUID> {
    @Query("""
        select history from StockValuationHistory history
        where history.tenantId = :tenantId
          and history.companyId = :companyId
          and history.materialId = :materialId
          and (:warehouseId is null or history.warehouseId = :warehouseId)
        order by history.calculatedAt desc, history.id desc
        limit 1
        """)
    java.util.Optional<StockValuationHistory> findMaterial360Latest(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );

    @Query(
        value = """
            select history from StockValuationHistory history
            where history.tenantId = :tenantId
              and history.companyId = :companyId
              and history.materialId = :materialId
              and (:warehouseId is null or history.warehouseId = :warehouseId)
              and (cast(:dateFrom as timestamp) is null or history.calculatedAt >= :dateFrom)
              and (cast(:dateToExclusive as timestamp) is null or history.calculatedAt < :dateToExclusive)
            order by history.calculatedAt desc, history.id desc
            """,
        countQuery = """
            select count(history) from StockValuationHistory history
            where history.tenantId = :tenantId
              and history.companyId = :companyId
              and history.materialId = :materialId
              and (:warehouseId is null or history.warehouseId = :warehouseId)
              and (cast(:dateFrom as timestamp) is null or history.calculatedAt >= :dateFrom)
              and (cast(:dateToExclusive as timestamp) is null or history.calculatedAt < :dateToExclusive)
            """
    )
    Page<StockValuationHistory> findMaterial360History(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateToExclusive") LocalDateTime dateToExclusive,
        Pageable pageable
    );

    @Query("""
        select h from StockValuationHistory h
        where h.tenantId = :tenantId
          and h.companyId = :companyId
          and (:warehouseId is null or h.warehouseId = :warehouseId)
          and (:materialIdsEmpty = true or h.materialId in :materialIds)
          and (:valuationMethod is null or h.valuationMethod = :valuationMethod)
          and (cast(:dateFrom as timestamp) is null or h.calculatedAt >= :dateFrom)
          and (cast(:dateTo as timestamp) is null or h.calculatedAt <= :dateTo)
        """)
    Page<StockValuationHistory> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("valuationMethod") com.enterprise.mm.enums.ValuationMethod valuationMethod,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );
}
