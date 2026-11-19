package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierAccreditationDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

public interface SupplierAccreditationDecisionRepository
    extends JpaRepository<SupplierAccreditationDecision, UUID>, JpaSpecificationExecutor<SupplierAccreditationDecision> {
    Optional<SupplierAccreditationDecision> findAnyById(UUID id);
    Optional<SupplierAccreditationDecision> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierAccreditationDecision> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    List<SupplierAccreditationDecision> findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID applicationId);
    Optional<SupplierAccreditationDecision> findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID applicationId, String status);

    @Query(value = """
        select decision.* from supplier_accreditation_decisions decision
        join supplier_accreditation_applications application on application.id = decision.application_id
        where decision.is_delete = false and application.is_delete = false
          and decision.tenant_id = :tenantId and application.tenant_id = :tenantId
          and decision.company_id = :companyId and application.company_id = :companyId
          and application.supplier_id in (:supplierIds)
          and upper(decision.decision) = 'APPROVED'
          and (decision.valid_from is null or decision.valid_from <= :asOfDate)
          and (decision.valid_to is null or decision.valid_to >= :asOfDate)
          and concat(',', coalesce(decision.allowed_material_group_ids, ''), ',')
              like concat('%,', cast(:materialGroupId as varchar), ',%')
        order by decision.created_at desc
        """, nativeQuery = true)
    List<SupplierAccreditationDecision> findMaterial360CurrentApproved(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierIds") java.util.Set<UUID> supplierIds,
        @Param("materialGroupId") UUID materialGroupId,
        @Param("asOfDate") LocalDate asOfDate
    );
}
