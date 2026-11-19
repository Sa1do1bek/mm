package com.enterprise.mm.warehouse.transfergroup;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseTransferGroupItemRepository extends JpaRepository<WarehouseTransferGroupItem, UUID> {
    List<WarehouseTransferGroupItem> findByTenantIdAndCompanyIdAndGroupIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID groupId
    );

    List<WarehouseTransferGroupItem> findByGroupIdAndIsDeletedFalse(UUID groupId);

    List<WarehouseTransferGroupItem> findByChildTransferIdAndIsDeletedFalse(UUID childTransferId);
}
