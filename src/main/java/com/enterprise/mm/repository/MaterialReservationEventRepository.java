package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialReservationEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialReservationEventRepository extends JpaRepository<MaterialReservationEvent, UUID> {
    @Query("""
        select coalesce(max(event.sequenceNo), 0)
        from MaterialReservationEvent event
        where event.tenantId = :tenantId
          and event.companyId = :companyId
          and event.reservationId = :reservationId
        """)
    long maxSequence(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("reservationId") UUID reservationId
    );

    List<MaterialReservationEvent> findByTenantIdAndCompanyIdAndReservationIdOrderBySequenceNoAsc(
        String tenantId,
        UUID companyId,
        UUID reservationId
    );
}
