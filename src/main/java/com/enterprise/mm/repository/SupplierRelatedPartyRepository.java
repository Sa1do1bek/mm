package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.SupplierRelatedParty;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRelatedPartyRepository extends JpaRepository<SupplierRelatedParty, UUID> {
    Optional<SupplierRelatedParty> findAnyById(UUID id);
    Optional<SupplierRelatedParty> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierRelatedParty> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId);

    @Query("""
        SELECT e
        FROM SupplierRelatedParty e
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
            AND (:partyType IS NULL OR e.partyType = :partyType)
            AND (:riskLevel IS NULL OR e.riskLevel = :riskLevel)
            AND (CAST(:fullName AS string) IS NULL OR LOWER(COALESCE(e.fullName, '')) LIKE LOWER(CONCAT('%', CAST(:fullName AS string), '%')))
            AND (CAST(:legalName AS string) IS NULL OR LOWER(COALESCE(e.legalName, '')) LIKE LOWER(CONCAT('%', CAST(:legalName AS string), '%')))
            AND (CAST(:countryCode AS string) IS NULL OR LOWER(COALESCE(e.countryCode, '')) = LOWER(CAST(:countryCode AS string)))
            AND (CAST(:positionTitle AS string) IS NULL OR LOWER(COALESCE(e.positionTitle, '')) LIKE LOWER(CONCAT('%', CAST(:positionTitle AS string), '%')))
            AND (CAST(:phone AS string) IS NULL OR LOWER(COALESCE(e.phone, '')) LIKE LOWER(CONCAT('%', CAST(:phone AS string), '%')))
            AND (CAST(:email AS string) IS NULL OR LOWER(COALESCE(e.email, '')) LIKE LOWER(CONCAT('%', CAST(:email AS string), '%')))
            AND (:pepFlag IS NULL OR e.pepFlag = :pepFlag)
            AND (:sanctionsMatch IS NULL OR e.sanctionsMatch = :sanctionsMatch)
            AND (:conflictOfInterestFlag IS NULL OR e.conflictOfInterestFlag = :conflictOfInterestFlag)
            AND (:ownershipPercentMin IS NULL OR e.ownershipPercent >= :ownershipPercentMin)
            AND (:ownershipPercentMax IS NULL OR e.ownershipPercent <= :ownershipPercentMax)
            AND (CAST(:validFrom AS date) IS NULL OR e.validFrom >= :validFrom)
            AND (CAST(:validTo AS date) IS NULL OR e.validTo <= :validTo)
        """)
    Page<SupplierRelatedParty> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("partyType") String partyType,
        @Param("riskLevel") String riskLevel,
        @Param("fullName") String fullName,
        @Param("legalName") String legalName,
        @Param("countryCode") String countryCode,
        @Param("positionTitle") String positionTitle,
        @Param("phone") String phone,
        @Param("email") String email,
        @Param("pepFlag") Boolean pepFlag,
        @Param("sanctionsMatch") Boolean sanctionsMatch,
        @Param("conflictOfInterestFlag") Boolean conflictOfInterestFlag,
        @Param("ownershipPercentMin") BigDecimal ownershipPercentMin,
        @Param("ownershipPercentMax") BigDecimal ownershipPercentMax,
        @Param("validFrom") LocalDate validFrom,
        @Param("validTo") LocalDate validTo,
        Pageable pageable
    );
}
