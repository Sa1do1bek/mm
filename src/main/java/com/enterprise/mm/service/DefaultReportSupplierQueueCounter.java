package com.enterprise.mm.service;

import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.repository.SupplierGovernanceQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultReportSupplierQueueCounter implements ReportSupplierQueueCounter {
    private final SupplierGovernanceQueryRepository queryRepository;

    @Override
    public long blacklistUnblockCountForCurrentCompany() {
        return queryRepository.countForCompany(new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.BLACKLIST_UNBLOCK,
            null,
            30,
            null
        ));
    }
}
