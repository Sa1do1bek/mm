package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ReturnOriginLink;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReturnOriginLinkRepository extends JpaRepository<ReturnOriginLink, UUID> {

    List<ReturnOriginLink> findByTenantIdAndCompanyIdAndOriginReferenceAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String originReference
    );

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r
        FROM ReturnOriginLink r
        WHERE r.tenantId = :tenantId
          AND r.companyId = :companyId
          AND r.originReference = :originReference
          AND r.materialId = :materialId
          AND r.isDeleted = false
    """)
    List<ReturnOriginLink> findAndLockByOriginReference(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("originReference") String originReference,
        @Param("materialId") UUID materialId
    );

    @Query("""
        SELECT COALESCE(SUM(r.returnedQuantity), 0)
        FROM ReturnOriginLink r
        WHERE r.tenantId = :tenantId
          AND r.companyId = :companyId
          AND r.originReference = :originReference
          AND r.materialId = :materialId
          AND r.isDeleted = false
    """)
    BigDecimal sumReturnedQuantity(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("originReference") String originReference,
        @Param("materialId") UUID materialId
    );

    List<ReturnOriginLink> findByReturnDocumentIdAndIsDeletedFalse(UUID returnDocumentId);
}
