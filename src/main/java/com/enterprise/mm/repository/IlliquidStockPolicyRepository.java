package com.enterprise.mm.repository;

import com.enterprise.mm.entity.IlliquidStockPolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IlliquidStockPolicyRepository extends JpaRepository<IlliquidStockPolicy, UUID> {
    Optional<IlliquidStockPolicy> findAnyById(UUID id);
    Optional<IlliquidStockPolicy> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        select e
        from IlliquidStockPolicy e
        where e.tenantId = :tenantId
          and (:companyId is null or e.companyId = :companyId)
          and (:includeDeleted = true or e.isDeleted = false)
          and (:active is null or e.active = :active)
          and (:search is null or lower(coalesce(e.code, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(e.name, '')) like lower(concat('%', :search, '%')))
          and (:statusEmpty = true or e.status in :status)
        """)
    Page<IlliquidStockPolicy> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("active") Boolean active,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );

    @Query("""
        select e
        from IlliquidStockPolicy e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.active = true
          and e.effectiveFrom <= :asOfDate
          and (e.effectiveTo is null or e.effectiveTo >= :asOfDate)
        """)
    List<IlliquidStockPolicy> findActivePoliciesForResolution(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("asOfDate") LocalDate asOfDate
    );

    @Query("""
        select e
        from IlliquidStockPolicy e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and e.isDeleted = false
          and e.active = true
          and (:excludeId is null or e.id <> :excludeId)
        """)
    List<IlliquidStockPolicy> findActivePolicies(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("excludeId") UUID excludeId
    );
}
