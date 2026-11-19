package com.enterprise.mm.repository;

import com.enterprise.mm.entity.UnitOfMeasure;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, UUID> {
    java.util.List<UnitOfMeasure> findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(
        String tenantId, UUID companyId, java.util.Collection<String> codes);
    @Query(value = """
        select exists (
            select 1 from stock_movements sm left join materials m on m.id = sm.material_id
            where sm.tenant_id = :tenantId and sm.status = 'POSTED'
              and (sm.uom_id_snapshot = :unitId or m.unit_of_measure_id = :unitId)
        ) or exists (
            select 1 from stock_balances b join materials m on m.id = b.material_id
            where b.tenant_id = :tenantId and m.unit_of_measure_id = :unitId
              and (b.quantity <> 0 or b.available_quantity <> 0 or b.reserved_quantity <> 0 or b.quarantine_quantity <> 0)
        )
        """, nativeQuery = true)
    boolean hasStockUsage(@Param("tenantId") String tenantId, @Param("unitId") UUID unitId);

    Optional<UnitOfMeasure> findAnyById(UUID id);
    Optional<UnitOfMeasure> findByIdAndIsDeletedFalse(UUID id);
    Optional<UnitOfMeasure> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    Optional<UnitOfMeasure> findFirstByCodeIgnoreCaseAndIsDeletedFalse(String code);
    Optional<UnitOfMeasure> findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalse(
        String tenantId, UUID companyId, String code);

    @Query("""
        select count(e) > 0 from UnitOfMeasure e
        where e.tenantId = :tenantId and e.companyId = :companyId and e.isDeleted = false
          and (
            (e.code is not null and lower(e.code) = lower(:lookup))
            or (e.measurementNameShort is not null and lower(e.measurementNameShort) = lower(:lookup))
            or (e.measurementNameLong is not null and lower(e.measurementNameLong) = lower(:lookup))
          )
        """)
    boolean existsByScopedLookup(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("lookup") String lookup);

    @Query("""
        select count(e) > 0 from UnitOfMeasure e
        where e.tenantId = :tenantId and e.companyId = :companyId and e.isDeleted = false
          and e.id <> :excludedId
          and (
            (e.code is not null and lower(e.code) = lower(:lookup))
            or (e.measurementNameShort is not null and lower(e.measurementNameShort) = lower(:lookup))
            or (e.measurementNameLong is not null and lower(e.measurementNameLong) = lower(:lookup))
          )
        """)
    boolean existsByScopedLookupExcludingId(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("lookup") String lookup,
        @Param("excludedId") UUID excludedId);

    @Query("""
        select count(e) > 0 from UnitOfMeasure e
        where e.isDeleted = false
          and (
            (e.code is not null and lower(e.code) = lower(:lookup))
            or (e.measurementNameShort is not null and lower(e.measurementNameShort) = lower(:lookup))
            or (e.measurementNameLong is not null and lower(e.measurementNameLong) = lower(:lookup))
          )
        """)
    boolean existsByLookup(@Param("lookup") String lookup);

    @Query("""
        select count(e) > 0 from UnitOfMeasure e
        where e.isDeleted = false
          and e.id <> :excludedId
          and (
            (e.code is not null and lower(e.code) = lower(:lookup))
            or (e.measurementNameShort is not null and lower(e.measurementNameShort) = lower(:lookup))
            or (e.measurementNameLong is not null and lower(e.measurementNameLong) = lower(:lookup))
          )
        """)
    boolean existsByLookupExcludingId(@Param("lookup") String lookup, @Param("excludedId") UUID excludedId);

    @Query("""
        SELECT e
        FROM UnitOfMeasure e
        WHERE
            e.tenantId = :tenantId
            AND e.companyId = :companyId
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 (e.code IS NOT NULL AND LOWER(e.code) LIKE LOWER(CONCAT('%', :search, '%')))
                 OR (e.measurementNameShort IS NOT NULL AND LOWER(e.measurementNameShort) LIKE LOWER(CONCAT('%', :search, '%')))
                 OR (e.measurementNameLong IS NOT NULL AND LOWER(e.measurementNameLong) LIKE LOWER(CONCAT('%', :search, '%')))
                 OR (e.measurementNameShortRu IS NOT NULL AND LOWER(e.measurementNameShortRu) LIKE LOWER(CONCAT('%', :search, '%')))
                 OR (e.measurementNameLongRu IS NOT NULL AND LOWER(e.measurementNameLongRu) LIKE LOWER(CONCAT('%', :search, '%')))
                 OR (e.measurementNameShortUz IS NOT NULL AND LOWER(e.measurementNameShortUz) LIKE LOWER(CONCAT('%', :search, '%')))
                 OR (e.measurementNameLongUz IS NOT NULL AND LOWER(e.measurementNameLongUz) LIKE LOWER(CONCAT('%', :search, '%'))))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
        """)
    Page<UnitOfMeasure> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );

}
