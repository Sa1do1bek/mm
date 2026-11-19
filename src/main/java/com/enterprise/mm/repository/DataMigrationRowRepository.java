package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.DataMigrationRow;
import java.util.List;
 import java.util.Optional;
 import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataMigrationRowRepository extends JpaRepository<DataMigrationRow, UUID> {
    Optional<DataMigrationRow> findAnyById(UUID id);
    Optional<DataMigrationRow> findByIdAndIsDeletedFalse(UUID id);
    List<DataMigrationRow> findByTenantIdAndCompanyIdAndRunIdAndIsDeletedFalseOrderByRowNumber(String tenantId, UUID companyId, UUID runId);
}

