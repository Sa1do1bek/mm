package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierCorrectiveActionRepository extends JpaRepository<SupplierCorrectiveAction, UUID> {
    Optional<SupplierCorrectiveAction> findAnyById(UUID id);
    Optional<SupplierCorrectiveAction> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierCorrectiveAction> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId);

    @Query("""
        SELECT e
        FROM SupplierCorrectiveAction e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:searchPattern IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE :searchPattern
                 OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:sourceType IS NULL OR UPPER(FUNCTION('replace', TRIM(e.sourceType), '-', '_')) = :sourceType)
            AND (:sourceId IS NULL OR e.sourceId = :sourceId)
            AND (:issueTitlePattern IS NULL OR LOWER(COALESCE(e.issueTitle, '')) LIKE :issueTitlePattern)
            AND (:ownerEmployeeId IS NULL OR e.ownerEmployeeId = :ownerEmployeeId)
            AND (:supplierResponsibleNamePattern IS NULL OR LOWER(COALESCE(e.supplierResponsibleName, '')) LIKE :supplierResponsibleNamePattern)
            AND (:severity IS NULL OR UPPER(FUNCTION('replace', TRIM(e.severity), '-', '_')) = :severity)
            AND (CAST(:dueDateFrom AS date) IS NULL OR e.dueDate >= :dueDateFrom)
            AND (CAST(:dueDateTo AS date) IS NULL OR e.dueDate <= :dueDateTo)
            AND (CAST(:submittedFrom AS timestamp) IS NULL OR e.submittedAt >= :submittedFrom)
            AND (CAST(:submittedTo AS timestamp) IS NULL OR e.submittedAt <= :submittedTo)
            AND (CAST(:verifiedFrom AS timestamp) IS NULL OR e.verifiedAt >= :verifiedFrom)
            AND (CAST(:verifiedTo AS timestamp) IS NULL OR e.verifiedAt <= :verifiedTo)
            AND (CAST(:closedFrom AS timestamp) IS NULL OR e.closedAt >= :closedFrom)
            AND (CAST(:closedTo AS timestamp) IS NULL OR e.closedAt <= :closedTo)
            AND (:verificationResultPattern IS NULL OR LOWER(COALESCE(e.verificationResult, '')) LIKE :verificationResultPattern)
            AND (:recurrencePrevented IS NULL OR e.recurrencePrevented = :recurrencePrevented)
        """)
    Page<SupplierCorrectiveAction> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("sourceType") String sourceType,
        @Param("sourceId") UUID sourceId,
        @Param("issueTitlePattern") String issueTitlePattern,
        @Param("ownerEmployeeId") UUID ownerEmployeeId,
        @Param("supplierResponsibleNamePattern") String supplierResponsibleNamePattern,
        @Param("severity") String severity,
        @Param("dueDateFrom") java.time.LocalDate dueDateFrom,
        @Param("dueDateTo") java.time.LocalDate dueDateTo,
        @Param("submittedFrom") LocalDateTime submittedFrom,
        @Param("submittedTo") LocalDateTime submittedTo,
        @Param("verifiedFrom") LocalDateTime verifiedFrom,
        @Param("verifiedTo") LocalDateTime verifiedTo,
        @Param("closedFrom") LocalDateTime closedFrom,
        @Param("closedTo") LocalDateTime closedTo,
        @Param("verificationResultPattern") String verificationResultPattern,
        @Param("recurrencePrevented") Boolean recurrencePrevented,
        Pageable pageable
    );
}
