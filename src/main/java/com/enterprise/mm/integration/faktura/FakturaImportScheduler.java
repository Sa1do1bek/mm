package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationSetting;
import com.enterprise.mm.repository.IntegrationSettingRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FakturaImportScheduler {
    private static final Logger log = LoggerFactory.getLogger(FakturaImportScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Tashkent");
    private final IntegrationSettingRepository settings;
    private final FakturaImportService importer;

    @Value("${FAKTURA_UZ_SCHEDULED_ENABLED:false}")
    private boolean enabled;

    @Scheduled(cron = "${app.integrations.faktura-incremental-cron:0 15 * * * *}", zone = "Asia/Tashkent")
    public void incremental() {
        if (enabled) run(2);
    }

    @Scheduled(cron = "${app.integrations.faktura-reconcile-cron:0 30 2 * * SAT}", zone = "Asia/Tashkent")
    public void reconcile() {
        if (enabled) run(31);
    }

    private void run(int lookbackDays) {
        LocalDate today = LocalDate.now(ZONE);
        for (IntegrationSetting setting : settings.findBySystemCodeAndEnabledTrueAndMockModeFalseAndIsDeletedFalse("FAKTURA_UZ")) {
            try {
                TenantContext.setTenant(setting.getTenantId());
                CompanyContext.setCompany(setting.getCompanyId());
                importer.importRange(today.minusDays(lookbackDays), today);
            } catch (Exception ex) {
                // Import runs contain safe status/counts; never log provider bodies or credential values.
                log.warn("Faktura scheduled import failed for company {}: {}", setting.getCompanyId(), ex.getClass().getSimpleName());
            } finally {
                TenantContext.clear();
                CompanyContext.clear();
            }
        }
    }
}
