package com.enterprise.mm.service.rfq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RfqMetrics {
    private final MeterRegistry registry;



    public void publication() {
        Counter.builder("mm_rfq_publication_total")
            .description("Successfully published RFQ versions")
            .register(registry)
            .increment();
    }

    public void delivery(String outcome) {
        Counter.builder("mm_rfq_delivery_total")
            .description("RFQ email delivery state transitions")
            .tag("outcome", tag(outcome))
            .register(registry)
            .increment();
    }

    public void magicLink(String outcome) {
        Counter.builder("mm_supplier_magic_link_total")
            .description("Supplier magic-link outcomes")
            .tag("outcome", tag(outcome))
            .register(registry)
            .increment();
    }

    public void portalOpen() {
        Counter.builder("mm_supplier_portal_rfq_open_total")
            .description("First supplier portal opens of an RFQ delivery")
            .register(registry)
            .increment();
    }

    public void proposal(String source, LocalDateTime publishedAt, LocalDateTime receivedAt) {
        Counter.builder("mm_rfq_proposal_total")
            .description("Current RFQ proposal revisions by source")
            .tag("source", tag(source))
            .register(registry)
            .increment();
        recordLatency("mm_rfq_response_latency", publishedAt, receivedAt);
    }

    public void deliveryLatency(LocalDateTime sentAt, LocalDateTime deliveredAt) {
        recordLatency("mm_rfq_delivery_latency", sentAt, deliveredAt);
    }

    private void recordLatency(String name, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return;
        }
        Timer.builder(name)
            .description("RFQ elapsed time")
            .register(registry)
            .record(Duration.between(start, end));
    }

    private String tag(String value) {
        return value == null || value.isBlank()
            ? "UNKNOWN"
            : value.trim().toUpperCase(Locale.ROOT);
    }
}
