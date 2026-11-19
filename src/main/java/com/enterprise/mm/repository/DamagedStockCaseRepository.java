package com.enterprise.mm.repository;

import com.enterprise.mm.entity.DamagedStockCase;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DamagedStockCaseRepository extends JpaRepository<DamagedStockCase, UUID> {
    Optional<DamagedStockCase> findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
        UUID id, String tenantId, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c from DamagedStockCase c where c.id = :id and c.tenantId = :tenantId
            and c.companyId = :companyId and c.isDeleted = false
        """)
    Optional<DamagedStockCase> lock(UUID id, String tenantId, UUID companyId);

    @Query("""
        select c from DamagedStockCase c join c.balance b join b.material m
        where c.tenantId = :tenantId and c.companyId = :companyId and c.isDeleted = false
          and (:allWarehouses = true or c.warehouseId in :warehouseIds)
          and (:status is null or c.status = :status)
          and (:search is null or lower(c.code) like :search or lower(m.code) like :search or lower(m.name) like :search)
        order by c.createdAt desc, c.id desc
        """)
    Page<DamagedStockCase> search(String tenantId, UUID companyId, boolean allWarehouses,
        Collection<UUID> warehouseIds, String status, String search, Pageable pageable);
}
