package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierQuotationThread;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierQuotationThreadRepository extends JpaRepository<SupplierQuotationThread, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select thread
        from SupplierQuotationThread thread
        where thread.tenantId = :tenantId
          and thread.companyId = :companyId
          and thread.rfqId = :rfqId
          and thread.supplierId = :supplierId
          and thread.isDeleted = false
        """)
    Optional<SupplierQuotationThread> lockByRfqIdAndSupplierId(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("rfqId") UUID rfqId,
        @Param("supplierId") UUID supplierId
    );

    Optional<SupplierQuotationThread> findByTenantIdAndCompanyIdAndRfqIdAndSupplierIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID rfqId,
        UUID supplierId
    );
}
