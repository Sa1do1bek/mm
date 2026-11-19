package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierPoResponseLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPoResponseLineRepository
    extends JpaRepository<SupplierPoResponseLine, UUID>, JpaSpecificationExecutor<SupplierPoResponseLine> {

    Optional<SupplierPoResponseLine> findByIdAndIsDeletedFalse(UUID id);

    List<SupplierPoResponseLine> findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID responseId
    );

    Optional<SupplierPoResponseLine> findByTenantIdAndCompanyIdAndResponseIdAndPurchaseOrderItemIdAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID responseId, UUID purchaseOrderItemId
    );
}
