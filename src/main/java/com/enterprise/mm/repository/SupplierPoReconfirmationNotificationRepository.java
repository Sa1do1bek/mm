package com.enterprise.mm.repository;

import com.enterprise.mm.entity.SupplierPoReconfirmationNotification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierPoReconfirmationNotificationRepository
    extends JpaRepository<SupplierPoReconfirmationNotification, UUID>, JpaSpecificationExecutor<SupplierPoReconfirmationNotification> {

    Optional<SupplierPoReconfirmationNotification> findByIdAndIsDeletedFalse(UUID id);

    List<SupplierPoReconfirmationNotification> findByTenantIdAndCompanyIdAndReconfirmationResponseIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID reconfirmationResponseId
    );

    List<SupplierPoReconfirmationNotification> findByTenantIdAndCompanyIdAndCommitmentIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID commitmentId
    );

    Optional<SupplierPoReconfirmationNotification> findByTenantIdAndCompanyIdAndReconfirmationResponseIdAndEventTypeAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID reconfirmationResponseId,
        String eventType
    );

    @Query("""
        select n
        from SupplierPoReconfirmationNotification n
        where n.isDeleted = false
          and n.status in :statuses
          and (n.nextRetryAt is null or n.nextRetryAt <= :now)
        order by n.queuedAt asc, n.createdAt asc
        """)
    List<SupplierPoReconfirmationNotification> findDue(
        @Param("statuses") List<String> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable
    );
}
