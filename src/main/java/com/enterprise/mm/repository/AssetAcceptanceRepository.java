package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetAcceptanceRepository extends JpaRepository<AssetAcceptance, UUID> {
    Page<AssetAcceptance> findByTenantIdAndCompanyIdAndWarehouseIdAndPostingStatusAndIsDeletedFalse(
        String tenantId, UUID companyId, UUID warehouseId,
        com.enterprise.mm.enums.AssetAcceptancePostingStatus postingStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"purchaseOrder", "supplier", "warehouse"})
    Optional<AssetAcceptance> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);
    @EntityGraph(attributePaths = {"purchaseOrder", "supplier", "warehouse"})
    Page<AssetAcceptance> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);
    List<AssetAcceptance> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndApprovalStatusAndPostingStatusAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID purchaseOrderId,
        AssetAcceptanceApprovalStatus approvalStatus,
        AssetAcceptancePostingStatus postingStatus
    );
    
    @EntityGraph(attributePaths = {"purchaseOrder", "supplier", "warehouse", "lines", "lines.units"})
    List<AssetAcceptance> findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        UUID purchaseOrderId
    );
}
