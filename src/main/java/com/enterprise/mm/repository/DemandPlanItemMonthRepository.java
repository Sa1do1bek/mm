package com.enterprise.mm.repository;

import com.enterprise.mm.entity.DemandPlanItemMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DemandPlanItemMonthRepository extends JpaRepository<DemandPlanItemMonth, UUID>, JpaSpecificationExecutor<DemandPlanItemMonth> {
    Optional<DemandPlanItemMonth> findAnyById(UUID id);
    Optional<DemandPlanItemMonth> findByIdAndIsDeletedFalse(UUID id);

    List<DemandPlanItemMonth> findByTenantIdAndDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(String tenantId, UUID demandPlanItemId);
    List<DemandPlanItemMonth> findByDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(UUID demandPlanItemId);
    List<DemandPlanItemMonth> findByTenantIdAndDemandPlanIdAndIsDeletedFalse(String tenantId, UUID demandPlanId);
    List<DemandPlanItemMonth> findByTenantIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByMonthAsc(String tenantId, Collection<UUID> demandPlanItemIds);
    List<DemandPlanItemMonth> findByDemandPlanItemIdInAndIsDeletedFalseOrderByMonthAsc(Collection<UUID> demandPlanItemIds);

    @Modifying
    @Query("UPDATE DemandPlanItemMonth m SET m.isDeleted = true WHERE m.demandPlanItemId = :demandPlanItemId AND m.isDeleted = false")
    void softDeleteByDemandPlanItemId(@Param("demandPlanItemId") UUID demandPlanItemId);
}
