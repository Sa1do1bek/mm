package com.enterprise.mm.config;

import lombok.RequiredArgsConstructor;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(PrometheusMeterRegistry.class)
@ConditionalOnMissingBean(PrometheusScrapeEndpoint.class)
@RequiredArgsConstructor
public class PrometheusScrapeFallbackController {
    private static final MediaType PROMETHEUS_TEXT =
        MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8");

    private final PrometheusMeterRegistry registry;

    
    @GetMapping("/actuator/prometheus")
    public ResponseEntity<String> scrape() {
        return ResponseEntity.ok()
            .contentType(PROMETHEUS_TEXT)
            .body(registry.scrape());
    }
}

