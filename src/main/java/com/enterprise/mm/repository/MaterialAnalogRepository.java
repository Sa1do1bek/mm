package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.MaterialAnalog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialAnalogRepository extends JpaRepository<MaterialAnalog, UUID> {
    Optional<MaterialAnalog> findAnyById(UUID id);
    Optional<MaterialAnalog> findByIdAndIsDeletedFalse(UUID id);
    List<MaterialAnalog> findByTenantIdAndMaterialIdAndIsDeletedFalse(String tenantId, UUID materialId);

    @Query("""
        select (count(e) > 0)
        from MaterialAnalog e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.materialId = :materialId
          and e.analogMaterialId = :analogMaterialId
          and e.isDeleted = false
          and (:excludeId is null or e.id <> :excludeId)
        """)
    boolean existsActivePair(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("analogMaterialId") UUID analogMaterialId,
        @Param("excludeId") UUID excludeId
    );

    @Query("""
        select e
        from MaterialAnalog e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.materialId = :materialId
          and e.isDeleted = false
          and upper(e.status) = 'ACTIVE'
        """)
    List<MaterialAnalog> findActiveOutgoing(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId
    );

    @Query("""
        SELECT e
        FROM MaterialAnalog e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<MaterialAnalog> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );
}
