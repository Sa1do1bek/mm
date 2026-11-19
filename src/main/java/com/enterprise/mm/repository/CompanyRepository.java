package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Company;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    Optional<Company> findAnyById(UUID id);
    Optional<Company> findByIdAndIsDeletedFalse(UUID id);
    Optional<Company> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);
    Optional<Company> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);
    List<Company> findByCodeIgnoreCaseAndIsDeletedFalse(String code);
    List<Company> findByTenantIdAndIsDeletedFalseOrderByNameAsc(String tenantId);

    @Query("""
        select c.name
        from Company c
        where c.tenantId = :tenantId
          and c.id = :id
          and c.isDeleted = false
        """)
    Optional<String> findNameByTenantIdAndIdAndIsDeletedFalse(@Param("tenantId") String tenantId, @Param("id") UUID id);
    @Query("""
        SELECT e
        FROM Company e
        WHERE
            e.tenantId = :tenantId
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:statusEmpty = true OR e.status IN :status)
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:entityId IS NULL OR e.id = :entityId)
        """)
    Page<Company> search(
        @Param("tenantId") String tenantId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("entityId") UUID entityId,
        Pageable pageable
    );

}
