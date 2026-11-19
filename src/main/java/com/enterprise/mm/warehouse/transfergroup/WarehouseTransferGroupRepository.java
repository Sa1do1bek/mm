package com.enterprise.mm.warehouse.transfergroup;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehouseTransferGroupRepository extends JpaRepository<WarehouseTransferGroup, UUID> {
    // Keep the data and count predicates identical; scope must be applied before pagination.
    String OPTION_FILTER = """
        FROM WarehouseTransferGroup g
        WHERE g.tenantId = :tenantId AND g.companyId = :companyId AND g.isDeleted = false
          AND (:readWarehouseId IS NULL OR g.sourceWarehouseId = :readWarehouseId)
          AND (:sourceWarehouseId IS NULL OR g.sourceWarehouseId = :sourceWarehouseId)
          AND (:pattern IS NULL OR LOWER(g.code) LIKE :pattern ESCAPE '!'
               OR LOWER(g.name) LIKE :pattern ESCAPE '!')
        """;

    @Query(value = "SELECT new com.enterprise.mm.warehouse.transfergroup.TransferGroupOptionResponse(g.id, g.code, g.name) "
        + OPTION_FILTER, countQuery = "SELECT COUNT(g) " + OPTION_FILTER)
    Page<TransferGroupOptionResponse> findOptions(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("readWarehouseId") UUID readWarehouseId,
        @Param("sourceWarehouseId") UUID sourceWarehouseId,
        @Param("pattern") String pattern,
        Pageable pageable
    );

    Optional<WarehouseTransferGroup> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID id
    );

    Optional<WarehouseTransferGroup> findByIdAndIsDeletedFalse(UUID id);
}
