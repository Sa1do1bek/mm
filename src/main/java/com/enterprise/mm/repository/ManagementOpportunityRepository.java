package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityStatus;
import com.enterprise.mm.enums.ManagementOpportunityType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagementOpportunityRepository extends JpaRepository<ManagementOpportunity, UUID> {
    interface GroupAmountProjection {
        String getGroupKey();
        java.math.BigDecimal getNormalizedAmount();
        long getCaseCount();
    }

    interface EntityAmountProjection {
        UUID getEntityId();
        java.math.BigDecimal getNormalizedAmount();
        long getCaseCount();
    }

    interface MaterialGroupMappingProjection {
        UUID getMaterialId();
        UUID getMaterialGroupId();
    }

    Optional<ManagementOpportunity> findByIdAndIsDeletedFalse(UUID id);

    List<ManagementOpportunity> findByTenantIdAndCalculationRunIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID calculationRunId);

    Page<ManagementOpportunity> findByTenantIdAndCompanyIdAndCalculationRunIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID calculationRunId,
        Pageable pageable
    );

    @Query("""
        select e
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.opportunityType = :type
          and e.sourceAggregateType = :sourceAggregateType
          and e.sourceAggregateId = :sourceAggregateId
          and (:sourceLineId is null or e.sourceLineId = :sourceLineId)
          and e.asOfDate = :asOfDate
          and e.status in :statuses
        """)
    List<ManagementOpportunity> findActiveDuplicates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("sourceAggregateType") String sourceAggregateType,
        @Param("sourceAggregateId") UUID sourceAggregateId,
        @Param("sourceLineId") UUID sourceLineId,
        @Param("asOfDate") LocalDate asOfDate,
        @Param("statuses") List<String> statuses
    );

    @Query("""
        select e
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and (:includeArchived = true or e.status <> 'ARCHIVED')
          and (:type is null or e.opportunityType = :type)
          and (:status is null or e.status = :status)
          and (:completeness is null or e.dataCompleteness = :completeness)
          and (:sourceAggregateType is null or e.sourceAggregateType = :sourceAggregateType)
          and (:sourceAggregateId is null or e.sourceAggregateId = :sourceAggregateId)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (:assignedRole is null or e.assignedRole = :assignedRole)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        """)
    Page<ManagementOpportunity> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeArchived") boolean includeArchived,
        @Param("type") ManagementOpportunityType type,
        @Param("status") String status,
        @Param("completeness") ManagementOpportunityDataCompleteness completeness,
        @Param("sourceAggregateType") String sourceAggregateType,
        @Param("sourceAggregateId") UUID sourceAggregateId,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("assignedRole") String assignedRole,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo,
        Pageable pageable
    );

    @Query("""
        select material.id as materialId, material.materialGroupId as materialGroupId
        from Material material
        where material.tenantId = :tenantId
          and material.companyId = :companyId
          and material.isDeleted = false
          and material.id in :materialIds
        """)
    List<MaterialGroupMappingProjection> findMaterialGroups(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialIds") List<UUID> materialIds
    );

    long countByTenantIdAndCompanyIdAndStatusAndDataCompletenessAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String status,
        ManagementOpportunityDataCompleteness dataCompleteness
    );

    @Query("""
        select coalesce(sum(e.normalizedAmount), 0)
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = :status
          and e.dataCompleteness = :completeness
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        """)
    java.math.BigDecimal sumApprovedNormalizedAmount(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("status") String status,
        @Param("completeness") ManagementOpportunityDataCompleteness completeness,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select count(e)
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = :status
          and e.dataCompleteness = :completeness
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        """)
    long countReportCases(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("status") String status,
        @Param("completeness") ManagementOpportunityDataCompleteness completeness,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select cast(e.opportunityType as string) as groupKey,
               coalesce(sum(e.normalizedAmount), 0) as normalizedAmount,
               count(e) as caseCount
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        group by e.opportunityType
        """)
    List<GroupAmountProjection> approvedAmountByType(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select cast(e.confidence as string) as groupKey,
               coalesce(sum(e.normalizedAmount), 0) as normalizedAmount,
               count(e) as caseCount
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        group by e.confidence
        """)
    List<GroupAmountProjection> approvedAmountByConfidence(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select e.supplierId as entityId,
               coalesce(sum(e.normalizedAmount), 0) as normalizedAmount,
               count(e) as caseCount
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and e.supplierId is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        group by e.supplierId
        """)
    List<EntityAmountProjection> approvedAmountBySupplier(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select e.warehouseId as entityId,
               coalesce(sum(e.normalizedAmount), 0) as normalizedAmount,
               count(e) as caseCount
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and e.warehouseId is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        group by e.warehouseId
        """)
    List<EntityAmountProjection> approvedAmountByWarehouse(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select e.materialId as entityId,
               coalesce(sum(e.normalizedAmount), 0) as normalizedAmount,
               count(e) as caseCount
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and e.materialId is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        group by e.materialId
        """)
    List<EntityAmountProjection> approvedAmountByMaterial(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select m.materialGroupId as entityId,
               coalesce(sum(e.normalizedAmount), 0) as normalizedAmount,
               count(e) as caseCount
        from ManagementOpportunity e
        join Material m on m.id = e.materialId
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and m.tenantId = :tenantId
          and m.companyId = :companyId
          and m.isDeleted = false
          and m.materialGroupId is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        group by m.materialGroupId
        """)
    List<EntityAmountProjection> approvedAmountByMaterialGroup(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select count(e)
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.DATA_INCOMPLETE
          and e.normalizedAmount is null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        """)
    long countCurrencyConversionMissing(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo
    );

    @Query("""
        select e
        from ManagementOpportunity e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.status = 'APPROVED'
          and e.dataCompleteness = com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE
          and e.normalizedAmount is not null
          and e.normalizedCurrencyCode is not null
          and (:type is null or e.opportunityType = :type)
          and (:supplierId is null or e.supplierId = :supplierId)
          and (:materialId is null or e.materialId = :materialId)
          and (:warehouseId is null or e.warehouseId = :warehouseId)
          and (:confidence is null or e.confidence = :confidence)
          and (cast(:asOfDateFrom as date) is null or e.asOfDate >= :asOfDateFrom)
          and (cast(:asOfDateTo as date) is null or e.asOfDate <= :asOfDateTo)
        order by e.asOfDate desc, e.createdAt desc
        """)
    List<ManagementOpportunity> approvedDrilldown(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("type") ManagementOpportunityType type,
        @Param("supplierId") UUID supplierId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        @Param("confidence") ManagementOpportunityConfidence confidence,
        @Param("asOfDateFrom") LocalDate asOfDateFrom,
        @Param("asOfDateTo") LocalDate asOfDateTo,
        Pageable pageable
    );
}
