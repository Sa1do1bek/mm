package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierAccreditationDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierAccreditationDocumentRepository
    extends JpaRepository<SupplierAccreditationDocument, UUID>, JpaSpecificationExecutor<SupplierAccreditationDocument> {
    Optional<SupplierAccreditationDocument> findAnyById(UUID id);
    Optional<SupplierAccreditationDocument> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierAccreditationDocument> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    List<SupplierAccreditationDocument> findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(String tenantId, UUID companyId, UUID applicationId);

    @Query("""
        select document from SupplierAccreditationDocument document
        join document.application application
        where document.tenantId = :tenantId and document.companyId = :companyId
          and document.isDeleted = false and application.id = :applicationId
          and application.tenantId = :tenantId and application.companyId = :companyId
          and application.isDeleted = false
        order by document.createdAt asc
        """)
    List<SupplierAccreditationDocument> findApprovalEvidence(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("applicationId") UUID applicationId
    );

    @Query("""
        select document
        from SupplierAccreditationDocument document
        join SupplierAccreditationApplication application
          on application.id = document.applicationId
        where document.tenantId = :tenantId
          and document.companyId = :companyId
          and document.isDeleted = false
          and application.tenantId = :tenantId
          and application.companyId = :companyId
          and application.supplierId = :supplierId
          and application.isDeleted = false
          and upper(document.documentType) = upper(:documentType)
        order by document.createdAt desc
        """)
    List<SupplierAccreditationDocument> findReusableBySupplierAndDocumentType(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId,
        @Param("documentType") String documentType
    );
}
