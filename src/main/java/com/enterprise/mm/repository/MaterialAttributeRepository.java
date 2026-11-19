package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialAttribute;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialAttributeRepository extends JpaRepository<MaterialAttribute, UUID> {
    List<MaterialAttribute> findAllByMaterialIdAndIsDeletedFalseOrderBySortOrderAsc(UUID materialId);

    @Query("""
        select attribute
        from MaterialAttribute attribute
        where attribute.materialId = :materialId
          and attribute.isDeleted = false
        order by attribute.sortOrder asc, attribute.createdAt asc
        """)
    List<MaterialAttribute> findActiveByMaterialId(@Param("materialId") UUID materialId);

    @Query("""
        select attribute
        from MaterialAttribute attribute
        where attribute.materialId in :materialIds
          and attribute.isDeleted = false
        order by attribute.materialId asc, attribute.sortOrder asc, attribute.createdAt asc
        """)
    List<MaterialAttribute> findActiveByMaterialIds(@Param("materialIds") List<UUID> materialIds);
}
