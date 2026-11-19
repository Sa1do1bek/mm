package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialCostObservation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialCostObservationRepository extends JpaRepository<MaterialCostObservation, UUID> {
    Optional<MaterialCostObservation> findByIdAndIsDeletedFalse(UUID id);

    Optional<MaterialCostObservation> findByTenantIdAndCompanyIdAndSourceTypeAndSourceLineIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String sourceType,
        UUID sourceLineId
    );

    @Query(
        value = """
            select observation
            from MaterialCostObservation observation
            left join fetch observation.material material
            where observation.tenantId = :tenantId
              and observation.companyId = :companyId
              and observation.isDeleted = false
              and (:mxikCode is null or observation.mxikCode = :mxikCode)
              and (:materialId is null or observation.materialId = :materialId)
              and (:currencyCode is null or observation.currencyCode = :currencyCode)
            order by observation.observedAt desc, observation.updatedAt desc, observation.id desc
            """,
        countQuery = """
            select count(observation)
            from MaterialCostObservation observation
            where observation.tenantId = :tenantId
              and observation.companyId = :companyId
              and observation.isDeleted = false
              and (:mxikCode is null or observation.mxikCode = :mxikCode)
              and (:materialId is null or observation.materialId = :materialId)
              and (:currencyCode is null or observation.currencyCode = :currencyCode)
            """
    )
    Page<MaterialCostObservation> findCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("mxikCode") String mxikCode,
        @Param("materialId") UUID materialId,
        @Param("currencyCode") String currencyCode,
        Pageable pageable
    );
}
