package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.MobileWarehouseOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MobileWarehouseOperationRepository extends JpaRepository<MobileWarehouseOperation, UUID> {
    Optional<MobileWarehouseOperation> findAnyById(UUID id);
    Optional<MobileWarehouseOperation> findByIdAndIsDeletedFalse(UUID id);
    List<MobileWarehouseOperation> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
}

