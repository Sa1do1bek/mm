package com.enterprise.mm.warehouse.reports.export;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseExportJobRepository extends JpaRepository<WarehouseExportJob, UUID> {
    Optional<WarehouseExportJob> findByIdAndTenantIdAndCompanyId(UUID id, String tenantId, UUID companyId);
    List<WarehouseExportJob> findByTenantIdAndCompanyIdOrderByCreatedAtDesc(String tenantId, UUID companyId);
}
