package com.enterprise.mm.repository;

import com.enterprise.mm.entity.AdvanceShippingNoticeSerial;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvanceShippingNoticeSerialRepository extends JpaRepository<AdvanceShippingNoticeSerial, UUID> {
    List<AdvanceShippingNoticeSerial> findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID asnItemId
    );
}
