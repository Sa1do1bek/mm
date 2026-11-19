package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Manufacturer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, UUID> {
    Optional<Manufacturer> findAnyById(UUID id);
    Optional<Manufacturer> findByIdAndIsDeletedFalse(UUID id);
    Optional<Manufacturer> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID id
    );
    Optional<Manufacturer> findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(String tenantId, UUID companyId, String code);

    Optional<Manufacturer> findByTenantIdAndCompanyIdAndNameIgnoreCaseAndIsDeletedFalse(String tenantId, UUID companyId, String name);

    @Query("""
        SELECT e
        FROM Manufacturer e
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
    Page<Manufacturer> search(
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
