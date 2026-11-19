package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialAttributeTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MaterialAttributeTemplateRepository extends JpaRepository<MaterialAttributeTemplate, UUID> {
    Optional<MaterialAttributeTemplate> findAnyById(UUID id);
    Optional<MaterialAttributeTemplate> findByIdAndIsDeletedFalse(UUID id);

    @Query("""
        select template
        from MaterialAttributeTemplate template
        where template.tenantId = :tenantId
          and template.companyId = :companyId
          and template.materialGroupId = :materialGroupId
          and template.isDeleted = false
          and upper(template.status) = 'ACTIVE'
        order by template.attributeName
        """)
    List<MaterialAttributeTemplate> findActiveByScopeAndMaterialGroup(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("materialGroupId") UUID materialGroupId
    );

    @Query("""
        SELECT e
        FROM MaterialAttributeTemplate e
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
    Page<MaterialAttributeTemplate> search(
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
