package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialDuplicateCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MaterialDuplicateCandidateRepository extends JpaRepository<MaterialDuplicateCandidate, UUID> {
    Optional<MaterialDuplicateCandidate> findAnyById(UUID id);
    Optional<MaterialDuplicateCandidate> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        SELECT e
        FROM MaterialDuplicateCandidate e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND e.materialId = :materialId
          AND e.candidateMaterialId = :candidateMaterialId
        """)
    Optional<MaterialDuplicateCandidate> findActiveByCanonicalPair(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialId") UUID materialId,
        @Param("candidateMaterialId") UUID candidateMaterialId
    );

    @Query("""
        SELECT e
        FROM MaterialDuplicateCandidate e
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
    Page<MaterialDuplicateCandidate> search(
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
