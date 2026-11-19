package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MaterialCommandViewObservation {
    public static final String METRIC_NAME = "material360.command_view.duration";
    private static final Logger log = LoggerFactory.getLogger(MaterialCommandViewObservation.class);

    private final MeterRegistry meterRegistry;

    public <T> T observe(TabKey tab, UUID materialId, Supplier<T> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "SUCCESS";
        try {
            return operation.get();
        } catch (RuntimeException | Error ex) {
            outcome = "ERROR";
            throw ex;
        } finally {
            Timer timer = Timer.builder(METRIC_NAME)
                .description("Material 360 command-view service duration")
                .tag("tab", tab.name())
                .tag("outcome", outcome)
                .register(meterRegistry);
            long elapsedNanos = sample.stop(timer);
            log.info(
                "material360 command view materialId={} companyId={} tab={} requestId={} elapsedMs={} outcome={}",
                materialId,
                CompanyContext.currentCompany(),
                tab.name(),
                MDC.get("requestId"),
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                outcome);
        }
    }
}
