package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ExactOpeningImport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExactOpeningImportRepository extends JpaRepository<ExactOpeningImport, UUID> {
    Optional<ExactOpeningImport> findByTenantIdAndCompanyId(String tenantId, UUID companyId);
}
