package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierActivationRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierActivationRecordRepository
    extends JpaRepository<SupplierActivationRecord, UUID> {

    boolean existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID supplierId,
        String status
    );

    List<SupplierActivationRecord>
    findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByActivatedAtDesc(
        String tenantId,
        UUID companyId,
        UUID supplierId
    );
}
