package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialMarketPrice;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialMarketPriceRepository extends JpaRepository<MaterialMarketPrice, UUID> {
    Optional<MaterialMarketPrice> findAnyById(UUID id);
    Optional<MaterialMarketPrice> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        select case when count(p) > 0 then true else false end
        from MaterialMarketPrice p
        where p.isDeleted = false
          and p.tenantId = :tenantId
          and p.companyId = :companyId
          and p.status = 'APPROVED'
          and p.materialId = :materialId
          and ((:warehouseId is null and p.warehouseId is null) or p.warehouseId = :warehouseId)
          and upper(trim(p.currencyCode)) = upper(trim(:currencyCode))
          and p.effectiveDate = :effectiveDate
          and p.id <> :candidateId
        """)
    boolean existsApprovedForScopeAndEffectiveDate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("currencyCode") String currencyCode,
        @Param("effectiveDate") LocalDate effectiveDate,
        @Param("candidateId") UUID candidateId
    );

    @Query("""
        select price from MaterialMarketPrice price
        where price.isDeleted = false
          and price.tenantId = :tenantId
          and price.companyId = :companyId
          and price.materialId = :materialId
          and (:warehouseId is null or price.warehouseId = :warehouseId or price.warehouseId is null)
          and price.status = 'APPROVED'
          and price.effectiveDate <= :asOfDate
        order by price.effectiveDate desc, price.id desc
        """)
    List<MaterialMarketPrice> findMaterial360Evidence(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("asOfDate") LocalDate asOfDate,
        Pageable pageable
    );

    @Query("""
        select p from MaterialMarketPrice p
        where p.isDeleted = false
          and p.tenantId = :tenantId
          and p.companyId = :companyId
          and p.materialId = :materialId
          and (p.warehouseId = :warehouseId or p.warehouseId is null)
          and p.status = 'APPROVED'
          and p.effectiveDate <= :asOfDate
        order by case when p.warehouseId = :warehouseId then 0 else 1 end, p.effectiveDate desc, p.createdAt desc
        """)
    List<MaterialMarketPrice> findEffectiveApproved(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("asOfDate") LocalDate asOfDate
    );

    @Query("""
        SELECT e
        FROM MaterialMarketPrice e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.sourceReference, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<MaterialMarketPrice> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );
}
