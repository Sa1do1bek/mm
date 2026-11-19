package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.TenderEvaluationProtocol;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TenderEvaluationProtocolRepository extends JpaRepository<TenderEvaluationProtocol, UUID>, JpaSpecificationExecutor<TenderEvaluationProtocol> {
    Optional<TenderEvaluationProtocol> findAnyById(UUID id);
    Optional<TenderEvaluationProtocol> findByIdAndIsDeletedFalse(UUID id);
    List<TenderEvaluationProtocol> findByTenantIdAndCompanyIdAndRfq_IdAndIsDeletedFalseOrderByEvaluatedAtDesc(String tenantId, UUID companyId, UUID rfqId);

    @Query("""
        SELECT e
        FROM TenderEvaluationProtocol e
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
        """)
    Page<TenderEvaluationProtocol> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );
}
