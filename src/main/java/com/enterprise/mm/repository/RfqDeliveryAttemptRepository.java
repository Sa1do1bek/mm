package com.enterprise.mm.repository;

import com.enterprise.mm.entity.RfqDeliveryAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqDeliveryAttemptRepository extends JpaRepository<RfqDeliveryAttempt, UUID> {
    List<RfqDeliveryAttempt> findByTenantIdAndCompanyIdAndDeliveryIdAndIsDeletedFalseOrderByAttemptNumberAsc(
        String tenantId,
        UUID companyId,
        UUID deliveryId
    );

    Optional<RfqDeliveryAttempt> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String idempotencyKey
    );
}
