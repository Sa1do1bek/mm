package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.SupplierKycCheck;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierKycCheckRepository extends JpaRepository<SupplierKycCheck, UUID> {
    Optional<SupplierKycCheck> findAnyById(UUID id);
    Optional<SupplierKycCheck> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierKycCheck> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId);

    long countByTenantIdAndCompanyIdAndSupplier_IdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId, String status);

    @Query("""
        SELECT e
        FROM SupplierKycCheck e
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
            AND (:checkType IS NULL OR LOWER(COALESCE(e.checkType, '')) = :checkType)
            AND (:riskLevel IS NULL OR LOWER(COALESCE(e.riskLevel, '')) = :riskLevel)
            AND (:remediationRequired IS NULL OR e.remediationRequired = :remediationRequired)
            AND (:scoreMin IS NULL OR e.score >= :scoreMin)
            AND (:scoreMax IS NULL OR e.score <= :scoreMax)
            AND (:providerPattern IS NULL OR LOWER(COALESCE(e.provider, '')) LIKE :providerPattern)
            AND (:externalCheckIdPattern IS NULL OR LOWER(COALESCE(e.externalCheckId, '')) LIKE :externalCheckIdPattern)
            AND (CAST(:checkedFrom AS timestamp) IS NULL OR e.checkedAt >= :checkedFrom)
            AND (CAST(:checkedTo AS timestamp) IS NULL OR e.checkedAt <= :checkedTo)
            AND (CAST(:validUntilFrom AS date) IS NULL OR e.validUntil >= :validUntilFrom)
            AND (CAST(:validUntilTo AS date) IS NULL OR e.validUntil <= :validUntilTo)
            AND (CAST(:nextReviewDateFrom AS date) IS NULL OR e.nextReviewDate >= :nextReviewDateFrom)
            AND (CAST(:nextReviewDateTo AS date) IS NULL OR e.nextReviewDate <= :nextReviewDateTo)
            AND (:evidenceAttachmentId IS NULL OR e.evidenceAttachmentId = :evidenceAttachmentId)
            AND (:findingsPattern IS NULL OR LOWER(COALESCE(e.findings, '')) LIKE :findingsPattern)
        """)
    Page<SupplierKycCheck> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("checkType") String checkType,
        @Param("riskLevel") String riskLevel,
        @Param("remediationRequired") Boolean remediationRequired,
        @Param("scoreMin") java.math.BigDecimal scoreMin,
        @Param("scoreMax") java.math.BigDecimal scoreMax,
        @Param("providerPattern") String providerPattern,
        @Param("externalCheckIdPattern") String externalCheckIdPattern,
        @Param("checkedFrom") LocalDateTime checkedFrom,
        @Param("checkedTo") LocalDateTime checkedTo,
        @Param("validUntilFrom") java.time.LocalDate validUntilFrom,
        @Param("validUntilTo") java.time.LocalDate validUntilTo,
        @Param("nextReviewDateFrom") java.time.LocalDate nextReviewDateFrom,
        @Param("nextReviewDateTo") java.time.LocalDate nextReviewDateTo,
        @Param("evidenceAttachmentId") UUID evidenceAttachmentId,
        @Param("findingsPattern") String findingsPattern,
        Pageable pageable
    );
}
