package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialUnitOperation;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialUnitOperationRepository extends JpaRepository<MaterialUnitOperation, UUID> {
    @Query("""
        select operation.materialUnitId from MaterialUnitOperation operation
        where operation.tenantId = :tenantId and operation.companyId = :companyId
          and operation.referenceType = :referenceType and operation.referenceId = :referenceId
          and operation.referenceLineId = :lineId
          and operation.operationType = com.enterprise.mm.enums.MaterialUnitOperationType.TRANSFER
          and operation.toStatus = 'IN_TRANSIT' and operation.isDeleted = false
        """)
    List<UUID> findTransferDispatchUnitIds(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("referenceType") String referenceType, @Param("referenceId") UUID referenceId, @Param("lineId") UUID lineId);

    List<MaterialUnitOperation> findByTenantIdAndCompanyIdAndReferenceTypeAndReferenceIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        String referenceType,
        UUID referenceId
    );

    @Query("""
        select operation.materialUnitId
        from MaterialUnitOperation operation
        where operation.tenantId = :tenantId
          and operation.companyId = :companyId
          and operation.referenceType = :referenceType
          and operation.referenceId = :referenceId
          and operation.operationType = :operationType
          and operation.isDeleted = false
        order by operation.createdAt asc, operation.id asc
        """)
    List<UUID> findUnitIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("referenceType") String referenceType,
        @Param("referenceId") UUID referenceId,
        @Param("operationType") MaterialUnitOperationType operationType
    );
}
