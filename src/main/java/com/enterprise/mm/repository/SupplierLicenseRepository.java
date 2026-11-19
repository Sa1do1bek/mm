package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierLicense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierLicenseRepository extends JpaRepository<SupplierLicense, UUID> {
    Optional<SupplierLicense> findAnyById(UUID id);
    Optional<SupplierLicense> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierLicense> findByTenantIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID supplierId);
    List<SupplierLicense> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID supplierId);
    Optional<SupplierLicense> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    Optional<SupplierLicense> findByTenantIdAndSupplier_IdAndLicenseNumberAndIsDeletedFalse(String tenantId, UUID supplierId, String licenseNumber);

    @Query("""
        SELECT e
        FROM SupplierLicense e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.supplierId = :supplierId
          AND e.isDeleted = false
          AND LOWER(TRIM(e.licenseNumber)) = LOWER(TRIM(:licenseNumber))
        """)
    Optional<SupplierLicense> findActiveByNormalizedLicenseNumber(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId,
        @Param("licenseNumber") String licenseNumber
    );

    @Query("""
        SELECT e
        FROM SupplierLicense e
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
            AND (:licenseType IS NULL OR LOWER(COALESCE(e.licenseType, '')) = :licenseType)
            AND (:renewalRequired IS NULL OR e.renewalRequired = :renewalRequired)
            AND (CAST(:validFrom AS date) IS NULL OR e.validFrom >= :validFrom)
            AND (CAST(:validTo AS date) IS NULL OR e.validTo <= :validTo)
        """)
    Page<SupplierLicense> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("licenseType") String licenseType,
        @Param("renewalRequired") Boolean renewalRequired,
        @Param("validFrom") LocalDate validFrom,
        @Param("validTo") LocalDate validTo,
        Pageable pageable
    );
}
