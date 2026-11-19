package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ServiceExecution;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ServiceExecutionRepository extends JpaRepository<ServiceExecution, UUID>, JpaSpecificationExecutor<ServiceExecution> {
    Optional<ServiceExecution> findAnyById(UUID id);
    Optional<ServiceExecution> findByIdAndIsDeletedFalse(UUID id);
    List<ServiceExecution> findByTenantIdAndServiceRequestIdAndIsDeletedFalse(String tenantId, UUID serviceRequestId);
    List<ServiceExecution> findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId);
    List<ServiceExecution> findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, UUID serviceRequestId);
    List<ServiceExecution> findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID serviceOrderId);
    List<ServiceExecution> findByTenantIdAndCompanyIdAndServiceOrderIdInAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId, UUID companyId, List<UUID> serviceOrderIds);
    List<ServiceExecution> findByTenantIdAndServiceRequestIdAndStatusInAndIsDeletedFalse(String tenantId, UUID serviceRequestId, List<String> statuses);
    List<ServiceExecution> findTop100ByIsDeletedFalseAndPlannedEndDateBeforeAndStatusInOrderByPlannedEndDateAsc(
        LocalDate plannedEndDate,
        List<String> statuses
    );
    List<ServiceExecution> findTop100ByTenantIdAndCompanyIdAndIsDeletedFalseAndPlannedEndDateBeforeAndStatusInOrderByPlannedEndDateAsc(
        String tenantId,
        UUID companyId,
        LocalDate plannedEndDate,
        List<String> statuses
    );
}
