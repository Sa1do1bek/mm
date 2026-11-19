package com.enterprise.mm.repository;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.enums.ApprovalRequestType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalFlowRepository extends JpaRepository<ApprovalFlow, UUID> {
    Optional<ApprovalFlow> findByIdAndIsDeletedFalse(UUID id);

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey
    );

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey,
        UUID departmentId
    );

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey,
        UUID departmentId,
        ApprovalRequestType requestType
    );

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey
    );

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey,
        ApprovalRequestType requestType
    );

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey,
        UUID departmentId
    );

    Optional<ApprovalFlow> findFirstByTenantIdAndCompanyIdAndModuleKeyAndDepartmentIdIsNullAndRequestTypeIsNullAndActiveTrueAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String moduleKey
    );

    List<ApprovalFlow> findAllByTenantIdAndCompanyIdAndModuleKeyAndActiveTrueAndIsDeletedFalse(String tenantId, UUID companyId, String moduleKey);

    @Query("""
        SELECT e
        FROM ApprovalFlow e
        WHERE e.tenantId = :tenantId
          AND (:companyId IS NULL OR e.companyId = :companyId)
          AND (:includeDeleted = true OR e.isDeleted = false)
          AND (:moduleKey IS NULL OR e.moduleKey = :moduleKey)
          AND (:departmentId IS NULL OR e.departmentId = :departmentId)
          AND (:requestTypeEmpty = true OR e.requestType IN :requestType)
          AND (:active IS NULL OR e.active = :active)
          AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
          AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
          AND (:hasSearch = false OR
               LOWER(COALESCE(e.code, '')) LIKE :searchPattern
               OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
        """)
    Page<ApprovalFlow> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("moduleKey") String moduleKey,
        @Param("departmentId") UUID departmentId,
        @Param("requestType") List<ApprovalRequestType> requestType,
        @Param("requestTypeEmpty") boolean requestTypeEmpty,
        @Param("active") Boolean active,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("hasSearch") boolean hasSearch,
        @Param("searchPattern") String searchPattern,
        Pageable pageable
    );
}
