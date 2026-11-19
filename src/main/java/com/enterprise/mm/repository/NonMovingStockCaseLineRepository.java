package com.enterprise.mm.repository;
import org.springframework.data.domain.Page;

import com.enterprise.mm.entity.NonMovingStockCaseLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NonMovingStockCaseLineRepository extends JpaRepository<NonMovingStockCaseLine, UUID> {
    interface Material360CaseProjection {
        UUID getId();
        UUID getCaseId();
        String getCaseNo();
        String getCaseName();
        String getCaseStatus();
        LocalDateTime getDetectedAt();
    }

    interface FrozenMoneyProjection {
        UUID getWarehouseId();
        UUID getMaterialId();
        BigDecimal getFrozenMoney();
    }

    List<NonMovingStockCaseLine> findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID caseId);
    List<NonMovingStockCaseLine> findByTenantIdAndNonMovingStockIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID nonMovingStockId);

    @Query("""
        select line.id as id,
               stockCase.id as caseId,
               stockCase.caseNo as caseNo,
               stockCase.name as caseName,
               stockCase.status as caseStatus,
               stockCase.detectedAt as detectedAt
        from NonMovingStockCaseLine line, NonMovingStockCase stockCase
        where line.isDeleted = false
          and stockCase.isDeleted = false
          and line.tenantId = :tenantId
          and stockCase.tenantId = :tenantId
          and line.companyId = :companyId
          and stockCase.companyId = :companyId
          and line.caseId = stockCase.id
          and line.materialId = :materialId
          and (:warehouseId is null or line.warehouseId = :warehouseId)
          and stockCase.status not in ('CLOSED', 'CANCELLED')
        order by stockCase.detectedAt desc, line.id desc
        """)
    List<Material360CaseProjection> findMaterial360OpenCaseEvidence(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId,
        Pageable pageable
    );

    @Query("""
        select count(line)
        from NonMovingStockCaseLine line, NonMovingStockCase stockCase
        where line.isDeleted = false
          and stockCase.isDeleted = false
          and line.tenantId = :tenantId
          and stockCase.tenantId = :tenantId
          and line.companyId = :companyId
          and stockCase.companyId = :companyId
          and line.caseId = stockCase.id
          and line.materialId = :materialId
          and (:warehouseId is null or line.warehouseId = :warehouseId)
          and stockCase.status not in ('CLOSED', 'CANCELLED')
        """)
    long countMaterial360OpenCases(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("warehouseId") UUID warehouseId
    );

    @Query("""
        select l
        from NonMovingStockCaseLine l, NonMovingStockCase c
        where l.isDeleted = false
          and c.isDeleted = false
          and l.tenantId = :tenantId
          and c.tenantId = :tenantId
          and l.companyId = :companyId
          and c.companyId = :companyId
          and l.caseId = c.id
          and c.status not in ('CLOSED', 'CANCELLED')
          and coalesce(l.bookValue, 0) > 0
        """)
    List<NonMovingStockCaseLine> findManagementOpportunityCarryingCostCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    @Query("""
        select l.warehouseId as warehouseId,
               l.materialId as materialId,
               coalesce(sum(l.bookValue), 0) as frozenMoney
        from NonMovingStockCaseLine l, NonMovingStockCase c
        where l.isDeleted = false
          and c.isDeleted = false
          and l.tenantId = :tenantId
          and c.tenantId = :tenantId
          and l.companyId = :companyId
          and c.companyId = :companyId
          and l.caseId = c.id
          and c.status not in ('CLOSED', 'CANCELLED')
          and (:warehouseId is null or l.warehouseId = :warehouseId)
          and (:materialId is null or l.materialId = :materialId)
        group by l.warehouseId, l.materialId
        """)
    List<FrozenMoneyProjection> aggregateFrozenMoney(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("materialId") UUID materialId
    );

    interface Warehouse360NonMovingProjection {
        UUID getId();
        UUID getCaseId();
        String getCaseNo();
        String getCaseName();
        String getCaseStatus();
        UUID getMaterialId();
        String getMaterialCode();
        String getMaterialName();
        LocalDateTime getDetectedAt();
    }

    @Query(
        value = """
            select line.id as id,
                   stockCase.id as caseId,
                   stockCase.caseNo as caseNo,
                   stockCase.name as caseName,
                   stockCase.status as caseStatus,
                   line.materialId as materialId,
                   coalesce(material.code, material.article) as materialCode,
                   coalesce(material.name, material.code, material.article) as materialName,
                   stockCase.detectedAt as detectedAt
            from NonMovingStockCaseLine line, NonMovingStockCase stockCase, Material material
            where line.isDeleted = false
              and stockCase.isDeleted = false
              and material.isDeleted = false
              and line.tenantId = :tenantId
              and stockCase.tenantId = :tenantId
              and line.companyId = :companyId
              and stockCase.companyId = :companyId
              and line.caseId = stockCase.id
              and material.id = line.materialId
              and line.warehouseId = :warehouseId
              and stockCase.status not in ('CLOSED', 'CANCELLED')
            order by stockCase.detectedAt desc, line.id desc
            """,
        countQuery = """
            select count(line)
            from NonMovingStockCaseLine line, NonMovingStockCase stockCase
            where line.isDeleted = false
              and stockCase.isDeleted = false
              and line.tenantId = :tenantId
              and stockCase.tenantId = :tenantId
              and line.companyId = :companyId
              and stockCase.companyId = :companyId
              and line.caseId = stockCase.id
              and line.warehouseId = :warehouseId
              and stockCase.status not in ('CLOSED', 'CANCELLED')
            """
    )
    Page<Warehouse360NonMovingProjection> findWarehouse360OpenCases(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        Pageable pageable
    );

    @Query("""
        select count(line)
        from NonMovingStockCaseLine line, NonMovingStockCase stockCase
        where line.isDeleted = false
          and stockCase.isDeleted = false
          and line.tenantId = :tenantId
          and stockCase.tenantId = :tenantId
          and line.companyId = :companyId
          and stockCase.companyId = :companyId
          and line.caseId = stockCase.id
          and line.warehouseId = :warehouseId
          and stockCase.status not in ('CLOSED', 'CANCELLED')
        """)
    long countWarehouse360OpenCases(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId
    );

}
