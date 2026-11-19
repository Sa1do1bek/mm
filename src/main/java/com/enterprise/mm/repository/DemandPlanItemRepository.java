package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.DemandPlanItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DemandPlanItemRepository extends JpaRepository<DemandPlanItem, UUID>, JpaSpecificationExecutor<DemandPlanItem> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DemandPlanItem e where e.id = :id and e.tenantId = :tenant and e.companyId = :company and e.isDeleted = false")
    Optional<DemandPlanItem> lockSource(@Param("id") UUID id, @Param("tenant") String tenant, @Param("company") UUID company);
    Optional<DemandPlanItem> findAnyById(UUID id);
    Optional<DemandPlanItem> findByIdAndIsDeletedFalse(UUID id);
    @EntityGraph(attributePaths = {"material", "material.unitOfMeasure"})
    List<DemandPlanItem> findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(String tenantId, UUID demandPlanId);
    List<DemandPlanItem> findByTenantIdAndDemandPlan_IdInAndIsDeletedFalse(String tenantId, List<UUID> demandPlanIds);
    List<DemandPlanItem> findByTenantIdAndBudgetItemIdAndIsDeletedFalse(String tenantId, UUID budgetItemId);

    List<DemandPlanItem> findByTenantIdAndConsolidatedDemand_IdAndIsDeletedFalse(String tenantId, UUID consolidatedDemandId);

    List<DemandPlanItem> findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        Collection<UUID> purchaseRequestItemIds
    );

    @Query("""
        SELECT e
        FROM DemandPlanItem e
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
    Page<DemandPlanItem> search(
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

    @Query("""
        SELECT e
        FROM DemandPlanItem e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND ((:type = 'PROJECT' AND e.demandType IN ('PROJECT', 'CAPEX'))
            OR (:type = 'REPAIR_ORDER' AND e.repairOrderCode IS NOT NULL AND TRIM(e.repairOrderCode) <> ''))
          AND (CAST(:search AS string) IS NULL OR
               LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
               OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
               OR LOWER(COALESCE(e.projectCode, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
               OR LOWER(COALESCE(e.repairOrderCode, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
        """)
    Page<DemandPlanItem> searchWarehouseCostObjects(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") String type,
        @Param("search") String search,
        Pageable pageable
    );
}
