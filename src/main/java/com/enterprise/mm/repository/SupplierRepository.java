package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Supplier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {
    @Query(value = """
        select s.id as id, s.code as code, s.name as name, s.status as status from Supplier s
        where s.tenantId = :tenantId and s.companyId = :companyId and s.isDeleted = false
          and (:allIds = true or s.id = :supplierId)
          and (lower(s.name) like lower(concat('%', :search, '%'))
               or lower(coalesce(s.code, '')) like lower(concat('%', :search, '%')))
        """, countQuery = """
        select count(s) from Supplier s
        where s.tenantId = :tenantId and s.companyId = :companyId and s.isDeleted = false
          and (:allIds = true or s.id = :supplierId)
          and (lower(s.name) like lower(concat('%', :search, '%'))
               or lower(coalesce(s.code, '')) like lower(concat('%', :search, '%')))
        """)
    Page<WarehouseReferenceOption> searchWarehouseOptions(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("allIds") boolean allIds, @Param("supplierId") UUID supplierId,
        @Param("search") String search, Pageable pageable);

    Optional<Supplier> findAnyById(UUID id);
    Optional<Supplier> findByIdAndIsDeletedFalse(UUID id);
    Optional<Supplier> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<Supplier> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    List<Supplier> findByTenantIdAndIsDeletedFalse(String tenantId);

    List<Supplier> findByTenantIdAndIsDeletedFalse(String tenantId, Pageable pageable);

    List<Supplier> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    List<Supplier> findByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, String status);

    List<Supplier> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);

    long countByTenantIdAndIsDeletedFalse(String tenantId);

    long countByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId);

    long countByTenantIdAndStatusAndIsDeletedFalse(String tenantId, String status);

    long countByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(String tenantId, UUID companyId, String status);

    @Query("""
        select s
        from Supplier s
        where s.tenantId = :tenantId
          and s.companyId = :companyId
          and s.isDeleted = false
          and s.status in ('ACTIVE', 'LIMITED')
          and (
            lower(s.code) like lower(concat('%', :search, '%'))
            or lower(s.name) like lower(concat('%', :search, '%'))
            or lower(s.email) like lower(concat('%', :search, '%'))
          )
        order by lower(coalesce(s.name, s.code)) asc
        """)
    List<Supplier> findActiveRfqSupplierCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("search") String search
    );


    @Query("""
        select s
        from Supplier s
        where s.tenantId = :tenantId
          and s.companyId = :companyId
          and s.isDeleted = false
          and s.status in ('ACTIVE', 'LIMITED')
        order by lower(coalesce(s.name, s.code)) asc
        """)
    List<Supplier> findActiveRfqSupplierCandidates(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId
    );
    long countByCompanyIdAndIsDeletedFalse(UUID companyId);

    long countByIsDeletedFalse();

    long countByCompanyIdAndBlockedReasonIsNotNullAndIsDeletedFalse(UUID companyId);

    long countByBlockedReasonIsNotNullAndIsDeletedFalse();

    long countByCompanyIdAndBankAccountIsNullAndIsDeletedFalse(UUID companyId);

    long countByBankAccountIsNullAndIsDeletedFalse();

    long countByCompanyIdAndTaxIdIsNullAndIsDeletedFalse(UUID companyId);

    long countByTaxIdIsNullAndIsDeletedFalse();

    long countByCompanyIdAndParentSupplierIdIsNullAndIsDeletedFalse(UUID companyId);

    long countByParentSupplierIdIsNullAndIsDeletedFalse();

    long countByCompanyIdAndCreatedAtAfterAndIsDeletedFalse(UUID companyId, LocalDateTime since);

    long countByCreatedAtAfterAndIsDeletedFalse(LocalDateTime since);

    // ---- Breakdown / group-by queries (used for the pipeline-style widgets) ----

    @Query("""
            select s.category as label, count(s) as total
            from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            group by s.category
            order by count(s) desc
            """)
    List<LabelCount> countGroupedByCategory(@Param("companyId") UUID companyId);

    @Query("""
            select s.status as label, count(s) as total
            from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            group by s.status
            order by count(s) desc
            """)
    List<LabelCount> countGroupedByStatus(@Param("companyId") UUID companyId);

    @Query("""
            select s.relationshipType as label, count(s) as total
            from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            group by s.relationshipType
            order by count(s) desc
            """)
    List<LabelCount> countGroupedByRelationshipType(@Param("companyId") UUID companyId);

    @Query("""
            select s.supplierType as label, count(s) as total
            from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            group by s.supplierType
            order by count(s) desc
            """)
    List<LabelCount> countGroupedBySupplierType(@Param("companyId") UUID companyId);

    @Query("""
            select s.countryCode as label, count(s) as total
            from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            group by s.countryCode
            order by count(s) desc
            """)
    List<LabelCount> countGroupedByCountry(@Param("companyId") UUID companyId);

    @Query("""
            select avg(s.rating)
            from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            """)
    java.math.BigDecimal averageRating(@Param("companyId") UUID companyId);

    // ---- Lists for the "open first" / "recently added" / "top rated" widgets ----

    @Query("""
            select s from Supplier s
            where s.isDeleted = false and s.blockedReason is not null
              and (:companyId is null or s.companyId = :companyId)
            order by s.updatedAt desc
            """)
    List<Supplier> findBlocked(@Param("companyId") UUID companyId, Pageable pageable);

    @Query("""
            select s from Supplier s
            where s.isDeleted = false
              and (:companyId is null or s.companyId = :companyId)
            order by s.createdAt desc
            """)
    List<Supplier> findRecent(@Param("companyId") UUID companyId, Pageable pageable);

    @Query("""
    select s from Supplier s
    where s.isDeleted = false
      and s.rating is not null
      and (:companyId is null or s.companyId = :companyId)
    order by s.rating desc
    """)
    List<Supplier> findTopRated(@Param("companyId") UUID companyId, Pageable pageable);
}
