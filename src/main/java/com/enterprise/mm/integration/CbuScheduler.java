package com.enterprise.mm.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "mm.integrations.cbu", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CbuScheduler {
    private final CbuIntegrationService cbuIntegrationService;

    @Scheduled(cron = "${mm.integrations.cbu.morning-cron:0 0 9 * * *}", zone = "${mm.integrations.cbu.zone:Asia/Tashkent}")
    @Scheduled(cron = "${mm.integrations.cbu.afternoon-cron:0 0 16 * * *}", zone = "${mm.integrations.cbu.zone:Asia/Tashkent}")
    public void scheduleUpdate() {
        log.info("Scheduled CBU exchange-rate update triggered");
        cbuIntegrationService.integrate();
    }
}
