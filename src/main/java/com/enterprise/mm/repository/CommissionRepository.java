package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.enums.CommissionType;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionRepository extends JpaRepository<Commission, UUID> {
    List<Commission> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);
    @Query(value = """
        select c.id as id, c.code as code, c.name as name, c.status as status from Commission c
        where c.tenantId = :tenantId and c.companyId = :companyId and c.isDeleted = false
          and c.type = com.enterprise.mm.enums.CommissionType.INVENTORY and c.active = true
          and (c.validFrom is null or c.validFrom <= :today)
          and (c.validTo is null or c.validTo >= :today)
          and (:allIds = true or c.id = :commissionId)
          and (lower(c.name) like lower(concat('%', :search, '%'))
               or lower(coalesce(c.code, '')) like lower(concat('%', :search, '%')))
          and exists (select m.id from CommissionMember m where m.commissionId = c.id
              and m.tenantId = c.tenantId and m.companyId = c.companyId and m.userId = :userId
              and m.active = true and m.isDeleted = false)
        """, countQuery = """
        select count(c) from Commission c
        where c.tenantId = :tenantId and c.companyId = :companyId and c.isDeleted = false
          and c.type = com.enterprise.mm.enums.CommissionType.INVENTORY and c.active = true
          and (c.validFrom is null or c.validFrom <= :today)
          and (c.validTo is null or c.validTo >= :today)
          and (:allIds = true or c.id = :commissionId)
          and (lower(c.name) like lower(concat('%', :search, '%'))
               or lower(coalesce(c.code, '')) like lower(concat('%', :search, '%')))
          and exists (select m.id from CommissionMember m where m.commissionId = c.id
              and m.tenantId = c.tenantId and m.companyId = c.companyId and m.userId = :userId
              and m.active = true and m.isDeleted = false)
        """)
    Page<WarehouseReferenceOption> searchMemberInventoryOptions(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("userId") UUID userId,
        @Param("allIds") boolean allIds, @Param("commissionId") UUID commissionId, @Param("search") String search,
        @Param("today") LocalDate today, Pageable pageable);

    Optional<Commission> findAnyById(UUID id);

    Optional<Commission> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        SELECT e
        FROM Commission e
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
            AND (:type IS NULL OR e.type = :type)
            AND (:active IS NULL OR e.active = :active)
        """)
    Page<Commission> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("type") CommissionType type,
        @Param("active") Boolean active,
        Pageable pageable
    );
}
