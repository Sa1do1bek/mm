package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialReturnLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialReturnLineRepository extends JpaRepository<MaterialReturnLine, UUID> {
    List<MaterialReturnLine> findByTenantIdAndReturnDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID returnDocumentId
    );

    @Query("""
        select coalesce(sum(line.acceptedQuantity), 0)
        from MaterialReturnLine line
        where line.isDeleted = false
          and line.tenantId = :tenantId
          and line.companyId = :companyId
          and line.issueRequestLineId = :issueRequestLineId
          and line.returnDocument.status in ('ACCEPTED', 'PARTIALLY_ACCEPTED', 'POSTED')
        """)
    BigDecimal sumAcceptedQuantity(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("issueRequestLineId") UUID issueRequestLineId
    );

    @Query("""
        select coalesce(sum(line.returnQuantity), 0)
        from MaterialReturnLine line
        where line.isDeleted = false
          and line.tenantId = :tenantId
          and line.companyId = :companyId
          and line.issueRequestLineId = :issueRequestLineId
          and line.returnDocument.status not in ('REJECTED', 'CANCELLED')
        """)
    BigDecimal sumCommittedQuantity(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("issueRequestLineId") UUID issueRequestLineId
    );
}
