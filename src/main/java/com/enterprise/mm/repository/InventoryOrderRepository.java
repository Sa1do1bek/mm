package com.enterprise.mm.repository;

import com.enterprise.mm.entity.InventoryOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface InventoryOrderRepository extends JpaRepository<InventoryOrder, UUID> {
    Optional<InventoryOrder> findByIdAndTenantIdAndCompanyId(UUID id, String tenantId, UUID companyId);
    Optional<InventoryOrder> findBySourceDraftIdAndTenantIdAndCompanyId(UUID draftId, String tenantId, UUID companyId);
    List<InventoryOrder> findTop50ByTenantIdAndCompanyIdOrderByCreatedAtDesc(String tenantId, UUID companyId);
    @Query(value = """
        SELECT o.* FROM inventory_orders o
        WHERE o.tenant_id = :tenant AND o.company_id = :company
          AND (:ownerOnly = false OR o.owner_username = :owner)
          AND (:v2Only = false OR jsonb_extract_path_text(CAST(o.analysis_payload AS jsonb), 'orderAnalysis', 'schemaVersion') = '2')
          AND (:search = '' OR LOWER(COALESCE(jsonb_extract_path_text(CAST(o.analysis_payload AS jsonb), 'orderAnalysis', 'header', 'orderNumber', 'value'), '')) LIKE :searchPattern
            OR LOWER(COALESCE(jsonb_extract_path_text(CAST(o.analysis_payload AS jsonb), 'orderAnalysis', 'header', 'orderTitle', 'value'), '')) LIKE :searchPattern)
        ORDER BY o.created_at DESC, o.id DESC
        """, countQuery = """
        SELECT COUNT(*) FROM inventory_orders o
        WHERE o.tenant_id = :tenant AND o.company_id = :company
          AND (:ownerOnly = false OR o.owner_username = :owner)
          AND (:v2Only = false OR jsonb_extract_path_text(CAST(o.analysis_payload AS jsonb), 'orderAnalysis', 'schemaVersion') = '2')
          AND (:search = '' OR LOWER(COALESCE(jsonb_extract_path_text(CAST(o.analysis_payload AS jsonb), 'orderAnalysis', 'header', 'orderNumber', 'value'), '')) LIKE :searchPattern
            OR LOWER(COALESCE(jsonb_extract_path_text(CAST(o.analysis_payload AS jsonb), 'orderAnalysis', 'header', 'orderTitle', 'value'), '')) LIKE :searchPattern)
        """, nativeQuery = true)
    Page<InventoryOrder> history(@Param("tenant") String tenant, @Param("company") UUID company,
        @Param("ownerOnly") boolean ownerOnly, @Param("owner") String owner, @Param("v2Only") boolean v2Only,
        @Param("search") String search, @Param("searchPattern") String searchPattern, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from InventoryOrder o where o.id = :id and o.tenantId = :tenant and o.companyId = :company")
    Optional<InventoryOrder> lockScoped(@Param("id") UUID id, @Param("tenant") String tenant, @Param("company") UUID company);
}
