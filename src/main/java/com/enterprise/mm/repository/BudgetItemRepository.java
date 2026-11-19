package com.enterprise.mm.repository;

import com.enterprise.mm.entity.BudgetItem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {
    List<BudgetItem> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    Optional<BudgetItem> findAnyById(UUID id);
    Optional<BudgetItem> findByIdAndIsDeletedFalse(UUID id);
    Optional<BudgetItem> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    Optional<BudgetItem> findByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String code
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BudgetItem e where e.id = :id and e.isDeleted = false")
    Optional<BudgetItem> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT e
        FROM BudgetItem e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
        """)
    Page<BudgetItem> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );
}
