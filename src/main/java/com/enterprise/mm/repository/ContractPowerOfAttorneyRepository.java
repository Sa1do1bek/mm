package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ContractPowerOfAttorney;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ContractPowerOfAttorneyRepository extends JpaRepository<ContractPowerOfAttorney, UUID>, JpaSpecificationExecutor<ContractPowerOfAttorney> {
    Optional<ContractPowerOfAttorney> findAnyById(UUID id);
    Optional<ContractPowerOfAttorney> findByIdAndIsDeletedFalse(UUID id);
    List<ContractPowerOfAttorney> findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID contractId);
    Optional<ContractPowerOfAttorney> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    @Query("""
        SELECT e
        FROM ContractPowerOfAttorney e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
            AND (:contractId IS NULL OR e.contractId = :contractId)
        """)
    Page<ContractPowerOfAttorney> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        @Param("contractId") UUID contractId,
        Pageable pageable
    );
}
