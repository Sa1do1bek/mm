package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.repository.SupplierGovernanceQueryRepository;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultReportSupplierQueueCounterTest {

    @Test
    void countUsesTheExactCompanyScopedBlacklistUnblockQueuePredicate() {
        AtomicReference<SupplierGovernanceFilter> captured = new AtomicReference<>();
        SupplierGovernanceQueryRepository repository = new SupplierGovernanceQueryRepository(null) {
            @Override
            public long countForCompany(SupplierGovernanceFilter filter) {
                captured.set(filter);
                return 9L;
            }
        };

        long count = new DefaultReportSupplierQueueCounter(repository)
            .blacklistUnblockCountForCurrentCompany();

        assertThat(count).isEqualTo(9L);
        assertThat(captured.get().queue()).isEqualTo(SupplierGovernanceQueueKey.BLACKLIST_UNBLOCK);
        assertThat(captured.get().search()).isNull();
        assertThat(captured.get().purchaseOrderId()).isNull();
    }
}
