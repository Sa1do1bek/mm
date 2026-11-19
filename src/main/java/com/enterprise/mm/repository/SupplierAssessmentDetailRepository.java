package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierAssessmentDetail;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierAssessmentDetailRepository extends JpaRepository<SupplierAssessmentDetail, UUID> {
    Optional<SupplierAssessmentDetail> findAnyById(UUID id);
    Optional<SupplierAssessmentDetail> findByIdAndIsDeletedFalse(UUID id);
    Optional<SupplierAssessmentDetail> findByAssessment_IdAndCriteria_IdAndIsDeletedFalse(UUID assessmentId, UUID criteriaId);
    List<SupplierAssessmentDetail> findByTenantIdAndAssessment_IdAndIsDeletedFalse(String tenantId, UUID assessmentId);
}
