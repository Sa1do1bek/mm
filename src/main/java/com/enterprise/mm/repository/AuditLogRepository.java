package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Optional<AuditLog> findAnyById(UUID id);
    Optional<AuditLog> findByIdAndIsDeletedFalse(UUID id);
    boolean existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndActionAndUsernameAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String entityType,
        UUID entityId,
        String action,
        String username
    );
    Page<AuditLog> findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtDesc(
        String tenantId, UUID companyId, String entityType, UUID entityId, Pageable pageable);

    List<AuditLog> findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
        String tenantId, UUID companyId, String entityType, UUID entityId
    );

    List<AuditLog> findByTenantIdAndCompanyIdAndEntityIdInAndIsDeletedFalseOrderByOccurredAtAsc(
        String tenantId, UUID companyId, List<UUID> entityIds
    );

    @Query("""
        SELECT e
        FROM AuditLog e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.entityType, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.action, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.username, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.details, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:actionEmpty = true OR e.action IN :action)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.occurredAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.occurredAt <= :dateTo)
        """)
    Page<AuditLog> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("action") List<String> action,
        @Param("actionEmpty") boolean actionEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );

}
