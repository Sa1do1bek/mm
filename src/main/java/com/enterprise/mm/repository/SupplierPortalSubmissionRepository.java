package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.SupplierPortalSubmission;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierPortalSubmissionRepository extends JpaRepository<SupplierPortalSubmission, UUID> {
    Optional<SupplierPortalSubmission> findAnyById(UUID id);
    Optional<SupplierPortalSubmission> findByIdAndIsDeletedFalse(UUID id);
    List<SupplierPortalSubmission> findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID supplierId);

    List<SupplierPortalSubmission> findByTenantIdAndCompanyIdAndPortalAccount_IdAndIsDeletedFalse(String tenantId, UUID companyId, UUID portalAccountId);

    @Query(
            value = """
        SELECT e.*
        FROM supplier_portal_submissions e
        WHERE e.tenant_id = :tenantId
          AND (CAST(:companyId AS uuid) IS NULL OR e.company_id = :companyId)
          AND (:includeDeleted = true OR e.is_delete = false)
          AND (
              CAST(:searchPattern AS text) IS NULL
              OR LOWER(COALESCE(e.code, '')) LIKE :searchPattern
              OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern
          )
          AND (CAST(:dateFrom AS timestamp) IS NULL OR e.created_at >= :dateFrom)
          AND (CAST(:dateTo AS timestamp) IS NULL OR e.created_at <= :dateTo)
          AND (:statusEmpty = true OR e.status IN (:status))
          AND (CAST(:supplierId AS uuid) IS NULL OR e.supplier_id = :supplierId)
          AND (CAST(:portalAccountId AS uuid) IS NULL OR e.portal_account_id = :portalAccountId)
          AND (CAST(:referenceId AS uuid) IS NULL OR e.reference_id = :referenceId)
          AND (
              CAST(:referenceEntityType AS text) IS NULL
              OR UPPER(REPLACE(TRIM(e.reference_entity_type), '-', '_'))
                 = CAST(:referenceEntityType AS text)
          )
          AND (
              CAST(:submissionType AS text) IS NULL
              OR UPPER(REPLACE(TRIM(e.submission_type), '-', '_'))
                 = CAST(:submissionType AS text)
          )
          AND (
              CAST(:payloadJson AS text) IS NULL
              OR CAST(e.payload_json AS text)
                 ILIKE CONCAT('%', CAST(:payloadJson AS text), '%')
          )
          AND (CAST(:attachmentId AS uuid) IS NULL OR e.attachment_id = :attachmentId)
          AND (CAST(:submittedFrom AS timestamp) IS NULL OR e.submitted_at >= :submittedFrom)
          AND (CAST(:submittedTo AS timestamp) IS NULL OR e.submitted_at <= :submittedTo)
          AND (CAST(:reviewedFrom AS timestamp) IS NULL OR e.reviewed_at >= :reviewedFrom)
          AND (CAST(:reviewedTo AS timestamp) IS NULL OR e.reviewed_at <= :reviewedTo)
          AND (
              CAST(:reviewedBy AS text) IS NULL
              OR e.reviewed_by ILIKE CONCAT('%', CAST(:reviewedBy AS text), '%')
          )
          AND (
              CAST(:reviewComment AS text) IS NULL
              OR e.review_comment ILIKE CONCAT('%', CAST(:reviewComment AS text), '%')
          )
        """,
            countQuery = """
        SELECT COUNT(*)
        FROM supplier_portal_submissions e
        WHERE e.tenant_id = :tenantId
          AND (CAST(:companyId AS uuid) IS NULL OR e.company_id = :companyId)
          AND (:includeDeleted = true OR e.is_delete = false)
          AND (
              CAST(:searchPattern AS text) IS NULL
              OR LOWER(COALESCE(e.code, '')) LIKE :searchPattern
              OR LOWER(COALESCE(e.name, '')) LIKE :searchPattern
          )
          AND (CAST(:dateFrom AS timestamp) IS NULL OR e.created_at >= :dateFrom)
          AND (CAST(:dateTo AS timestamp) IS NULL OR e.created_at <= :dateTo)
          AND (:statusEmpty = true OR e.status IN (:status))
          AND (CAST(:supplierId AS uuid) IS NULL OR e.supplier_id = :supplierId)
          AND (CAST(:portalAccountId AS uuid) IS NULL OR e.portal_account_id = :portalAccountId)
          AND (CAST(:referenceId AS uuid) IS NULL OR e.reference_id = :referenceId)
          AND (
              CAST(:referenceEntityType AS text) IS NULL
              OR UPPER(REPLACE(TRIM(e.reference_entity_type), '-', '_'))
                 = CAST(:referenceEntityType AS text)
          )
          AND (
              CAST(:submissionType AS text) IS NULL
              OR UPPER(REPLACE(TRIM(e.submission_type), '-', '_'))
                 = CAST(:submissionType AS text)
          )
          AND (
              CAST(:payloadJson AS text) IS NULL
              OR CAST(e.payload_json AS text)
                 ILIKE CONCAT('%', CAST(:payloadJson AS text), '%')
          )
          AND (CAST(:attachmentId AS uuid) IS NULL OR e.attachment_id = :attachmentId)
          AND (CAST(:submittedFrom AS timestamp) IS NULL OR e.submitted_at >= :submittedFrom)
          AND (CAST(:submittedTo AS timestamp) IS NULL OR e.submitted_at <= :submittedTo)
          AND (CAST(:reviewedFrom AS timestamp) IS NULL OR e.reviewed_at >= :reviewedFrom)
          AND (CAST(:reviewedTo AS timestamp) IS NULL OR e.reviewed_at <= :reviewedTo)
          AND (
              CAST(:reviewedBy AS text) IS NULL
              OR e.reviewed_by ILIKE CONCAT('%', CAST(:reviewedBy AS text), '%')
          )
          AND (
              CAST(:reviewComment AS text) IS NULL
              OR e.review_comment ILIKE CONCAT('%', CAST(:reviewComment AS text), '%')
          )
        """,
            nativeQuery = true
    )
    Page<SupplierPortalSubmission> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("searchPattern") String searchPattern,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("portalAccountId") UUID portalAccountId,
        @Param("referenceId") UUID referenceId,
        @Param("referenceEntityType") String referenceEntityType,
        @Param("submissionType") String submissionType,
        @Param("payloadJson") String payloadJson,
        @Param("attachmentId") UUID attachmentId,
        @Param("submittedFrom") LocalDateTime submittedFrom,
        @Param("submittedTo") LocalDateTime submittedTo,
        @Param("reviewedFrom") LocalDateTime reviewedFrom,
        @Param("reviewedTo") LocalDateTime reviewedTo,
        @Param("reviewedBy") String reviewedBy,
        @Param("reviewComment") String reviewComment,
        Pageable pageable
    );
}
