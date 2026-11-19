package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.MaterialConsumptionNorm;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialConsumptionNormRepository extends JpaRepository<MaterialConsumptionNorm, UUID> {
    Optional<MaterialConsumptionNorm> findAnyById(UUID id);
    Optional<MaterialConsumptionNorm> findByIdAndIsDeletedFalse(UUID id);
    List<MaterialConsumptionNorm> findByTenantIdAndMaterialIdAndIsDeletedFalse(String tenantId, UUID materialId);

    List<MaterialConsumptionNorm> findByTenantIdAndEquipmentIdAndIsDeletedFalse(String tenantId, UUID equipmentId);

    @Query("""
        select (count(e) > 0)
        from MaterialConsumptionNorm e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.materialId = :materialId
          and ((:equipmentId is null and e.equipmentId is null) or e.equipmentId = :equipmentId)
          and ((:departmentId is null and e.departmentId is null) or e.departmentId = :departmentId)
          and ((:productCode is null and e.productCode is null) or e.productCode = :productCode)
          and ((:operationCode is null and e.operationCode is null) or e.operationCode = :operationCode)
          and e.normType = :normType
          and e.isDeleted = false
          and upper(e.status) = 'ACTIVE'
          and (:excludeId is null or e.id <> :excludeId)
          and (:effectiveTo is null or e.effectiveFrom <= :effectiveTo)
          and (e.effectiveTo is null or e.effectiveTo >= :effectiveFrom)
        """)
    boolean existsOverlappingActiveNorm(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("equipmentId") UUID equipmentId,
        @Param("departmentId") UUID departmentId,
        @Param("productCode") String productCode,
        @Param("operationCode") String operationCode,
        @Param("normType") String normType,
        @Param("effectiveFrom") LocalDate effectiveFrom,
        @Param("effectiveTo") LocalDate effectiveTo,
        @Param("excludeId") UUID excludeId
    );

    @Query("""
        SELECT e
        FROM MaterialConsumptionNorm e
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
    Page<MaterialConsumptionNorm> search(
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
