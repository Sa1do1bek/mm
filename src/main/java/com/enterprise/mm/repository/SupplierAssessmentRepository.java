package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierAssessment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierAssessmentRepository extends JpaRepository<SupplierAssessment, UUID> {
    Optional<SupplierAssessment> findAnyById(UUID id);
    Optional<SupplierAssessment> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierAssessment> findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(String tenantId, UUID companyId, String code);

    @EntityGraph(attributePaths = {"supplier", "details", "details.criteria"})
    Optional<SupplierAssessment> findWithDetailsByIdAndIsDeletedFalse(UUID id);

    List<SupplierAssessment> findByTenantIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID supplierId);

    @Query(
        value = """
        SELECT e
        FROM SupplierAssessment e
        WHERE e.tenantId = :tenantId
          AND (:companyId IS NULL OR e.companyId = :companyId)
          AND e.isDeleted = false
          AND (:supplierId IS NULL OR e.supplier.id = :supplierId)
          AND (CAST(:search AS string) IS NULL OR LOWER(CONCAT(COALESCE(e.code, ''), ' ', COALESCE(e.name, ''), ' ', COALESCE(e.description, ''), ' ', COALESCE(e.status, ''), ' ', COALESCE(e.supplier.name, ''), ' ', COALESCE(e.supplier.legalName, ''), ' ', COALESCE(e.supplier.shortName, ''))) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
          AND (:statusEmpty = true OR e.status IN :statuses)
          AND (:dateFrom IS NULL OR e.assessmentDate >= :dateFrom)
          AND (:dateTo IS NULL OR e.assessmentDate <= :dateTo)
        ORDER BY
          CASE WHEN :scoreDesc = false THEN e.totalWeightedScore END ASC,
          CASE WHEN :scoreDesc = true THEN e.totalWeightedScore END DESC,
          e.assessmentDate DESC,
          e.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(e)
        FROM SupplierAssessment e
        WHERE e.tenantId = :tenantId
          AND (:companyId IS NULL OR e.companyId = :companyId)
          AND e.isDeleted = false
          AND (:supplierId IS NULL OR e.supplier.id = :supplierId)
          AND (CAST(:search AS string) IS NULL OR LOWER(CONCAT(COALESCE(e.code, ''), ' ', COALESCE(e.name, ''), ' ', COALESCE(e.description, ''), ' ', COALESCE(e.status, ''), ' ', COALESCE(e.supplier.name, ''), ' ', COALESCE(e.supplier.legalName, ''), ' ', COALESCE(e.supplier.shortName, ''))) LIKE CONCAT('%', LOWER(CAST(:search AS string)), '%'))
          AND (:statusEmpty = true OR e.status IN :statuses)
          AND (:dateFrom IS NULL OR e.assessmentDate >= :dateFrom)
          AND (:dateTo IS NULL OR e.assessmentDate <= :dateTo)
        """
    )
    Page<SupplierAssessment> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId,
        @Param("search") String search,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("statuses") List<String> statuses,
        @Param("dateFrom") LocalDate dateFrom,
        @Param("dateTo") LocalDate dateTo,
        @Param("scoreDesc") boolean scoreDesc,
        Pageable pageable
    );
}
