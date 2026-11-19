package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ReferenceMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReferenceMappingRepository extends JpaRepository<ReferenceMapping, UUID> {
    Optional<ReferenceMapping> findAnyById(UUID id);
    Optional<ReferenceMapping> findByIdAndIsDeletedFalse(UUID id);
    List<ReferenceMapping> findByTenantIdAndIsDeletedFalseOrderByReferenceEntityTypeAscExternalSystemAscAccountingAreaAscPriorityAsc(
        String tenantId
    );

    List<ReferenceMapping> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByReferenceEntityTypeAscExternalSystemAscAccountingAreaAscPriorityAsc(
        String tenantId,
        UUID companyId
    );

    Optional<ReferenceMapping> findByTenantIdAndCompanyIdAndReferenceEntityTypeAndExternalSystemAndExternalCodeAndAccountingAreaAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String referenceEntityType,
        String externalSystem,
        String externalCode,
        String accountingArea
    );

    List<ReferenceMapping> findByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndIsDeletedFalseOrderByPriorityAsc(
        String tenantId,
        UUID companyId,
        String referenceEntityType,
        UUID referenceId
    );

    Optional<ReferenceMapping> findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
        String tenantId,
        UUID companyId,
        String referenceEntityType,
        UUID referenceId,
        String externalSystem,
        String accountingArea
    );

    List<ReferenceMapping> findByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceCodeAndIsDeletedFalseOrderByPriorityAsc(
        String tenantId,
        UUID companyId,
        String referenceEntityType,
        String referenceCode
    );

    Optional<ReferenceMapping> findFirstByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceCodeAndExternalSystemAndAccountingAreaAndIsDeletedFalseOrderByPriorityAsc(
        String tenantId,
        UUID companyId,
        String referenceEntityType,
        String referenceCode,
        String externalSystem,
        String accountingArea
    );

    @Query("""
        SELECT e
        FROM ReferenceMapping e
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
            AND (:referenceId IS NULL OR e.referenceId = :referenceId)
            AND (:referenceEntityType IS NULL OR UPPER(FUNCTION('replace', TRIM(e.referenceEntityType), '-', '_')) = :referenceEntityType)
            AND (:externalSystem IS NULL OR UPPER(FUNCTION('replace', TRIM(e.externalSystem), '-', '_')) = :externalSystem)
            AND (:accountingArea IS NULL OR UPPER(FUNCTION('replace', TRIM(e.accountingArea), '-', '_')) = :accountingArea)
        """)
    Page<ReferenceMapping> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("referenceId") UUID referenceId,
        @Param("referenceEntityType") String referenceEntityType,
        @Param("externalSystem") String externalSystem,
        @Param("accountingArea") String accountingArea,
        Pageable pageable
    );
}
