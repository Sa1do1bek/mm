package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.overview.repository.ManagementOverviewQueryRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class ManagementOverviewQueryRepositoryTest {
    private static final UUID COMPANY_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @Autowired
    private ManagementOverviewQueryRepository repository;

    @Test
    void currentOpenExposureQueriesKeepExplicitCompanyAndCompileAgainstTheSchema() {
        assertThat(repository.openPurchaseOrders("tenant-a", COMPANY_ID, "UZS")).isEmpty();
        assertThat(repository.remainingValueByOrder(
            "tenant-a", COMPANY_ID, List.of(UUID.fromString("60000000-0000-0000-0000-000000000006"))))
            .isEmpty();
    }
}
