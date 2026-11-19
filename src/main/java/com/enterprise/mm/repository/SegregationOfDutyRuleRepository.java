package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SegregationOfDutyRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SegregationOfDutyRuleRepository extends JpaRepository<SegregationOfDutyRule, UUID> {
    Optional<SegregationOfDutyRule> findAnyById(UUID id);
    Optional<SegregationOfDutyRule> findByIdAndIsDeletedFalse(UUID id);
    List<SegregationOfDutyRule> findByTenantIdAndCompanyIdAndEntityTypeAndActionCodeAndActiveFlagTrueAndIsDeletedFalse(String tenantId, UUID companyId, String entityType, String actionCode);

    Optional<SegregationOfDutyRule> findByTenantIdAndCompanyIdAndEntityTypeAndActionCodeAndRuleTypeAndIsDeletedFalse(String tenantId, UUID companyId, String entityType, String actionCode, String ruleType);

    @Query("""
        SELECT e
        FROM SegregationOfDutyRule e
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
    Page<SegregationOfDutyRule> search(
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
