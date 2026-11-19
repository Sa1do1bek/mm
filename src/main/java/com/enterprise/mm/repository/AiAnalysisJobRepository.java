package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAnalysisJobRepository extends JpaRepository<AiAnalysisJob, UUID> {
    boolean existsByTenantIdAndCompanyIdAndAnalysisTypeAndEntityTypeAndEntityIdAndStatusIn(
        String tenantId, UUID companyId, AiAnalysisType analysisType, String entityType, UUID entityId,
        Collection<AiAnalysisJobStatus> statuses);
    @Query("""
        select job from AiAnalysisJob job
        where job.tenantId = :tenantId and job.companyId = :companyId
          and job.analysisType = :analysisType and job.entityType = 'MATERIAL'
          and job.entityId in :materialIds
          and job.requestedAt = (
              select max(newer.requestedAt) from AiAnalysisJob newer
              where newer.tenantId = job.tenantId and newer.companyId = job.companyId
                and newer.analysisType = job.analysisType and newer.entityType = job.entityType
                and newer.entityId = job.entityId
          )
        order by job.requestedAt desc
        """)
    List<AiAnalysisJob> findForMaterials(
        @Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("analysisType") AiAnalysisType analysisType,
        @Param("materialIds") Collection<UUID> materialIds
    );
    @Query("""
        select job from AiAnalysisJob job
        where job.id = :id and job.tenantId = :tenantId and job.companyId = :companyId
        """)
    Optional<AiAnalysisJob> findScoped(
        @Param("id") UUID id,
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AiAnalysisJob job where job.id = :id")
    Optional<AiAnalysisJob> findForUpdate(@Param("id") UUID id);

    @Query("""
        select job from AiAnalysisJob job
        where job.tenantId = :tenantId
          and job.companyId = :companyId
          and job.analysisType = :analysisType
          and job.entityType = :entityType
          and ((:entityId is null and job.entityId is null) or (:entityId is not null and job.entityId = :entityId))
          and job.requestHash = :requestHash
          and job.requestedLanguage = :requestedLanguage
          and job.status in :statuses
        order by job.requestedAt desc
        """)
    Optional<AiAnalysisJob> findActiveEquivalent(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("analysisType") AiAnalysisType analysisType,
        @Param("entityType") String entityType,
        @Param("entityId") UUID entityId,
        @Param("requestHash") String requestHash,
        @Param("requestedLanguage") String requestedLanguage,
        @Param("statuses") Collection<AiAnalysisJobStatus> statuses
    );

    @Query("""
        select job from AiAnalysisJob job
        where job.tenantId = :tenantId and job.companyId = :companyId
          and job.analysisType = :analysisType and job.entityType = :entityType
          and job.entityId = :entityId and job.requestHash = :requestHash
          and job.requestedLanguage = :requestedLanguage
        order by job.requestedAt desc
        """)
    List<AiAnalysisJob> findEquivalentInventoryJobs(
        @Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("analysisType") AiAnalysisType analysisType, @Param("entityType") String entityType,
        @Param("entityId") UUID entityId, @Param("requestHash") String requestHash,
        @Param("requestedLanguage") String requestedLanguage, Pageable pageable
    );

    @Query("""
        select job from AiAnalysisJob job
        where job.tenantId = :tenantId
          and job.companyId = :companyId
          and job.analysisType = :analysisType
          and job.entityType = :entityType
          and ((:entityId is null and job.entityId is null) or (:entityId is not null and job.entityId = :entityId))
        order by job.requestedAt desc
        """)
    Page<AiAnalysisJob> findLatestForEntity(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("analysisType") AiAnalysisType analysisType,
        @Param("entityType") String entityType,
        @Param("entityId") UUID entityId,
        Pageable pageable
    );

    @Query("""
        select job from AiAnalysisJob job
        where job.status in :statuses and job.requestedAt < :startedAt
        order by job.requestedAt asc
        """)
    List<AiAnalysisJob> findAbandoned(
        @Param("statuses") Collection<AiAnalysisJobStatus> statuses,
        @Param("startedAt") LocalDateTime startedAt
    );
}
