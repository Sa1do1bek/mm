package com.enterprise.mm.integration;

import com.enterprise.mm.payload.integration.CbuCurrencyResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CbuIntegrationService {
    private final CbuCurrencyClient cbuCurrencyClient;
    private final CbuRateProcessor cbuRateProcessor;

    public String integrate() {
        List<CbuCurrencyResponse> rates = cbuCurrencyClient.fetchRates();
        if (rates.isEmpty()) {
            return "Skipped: no CBU rates available";
        }
        int processed = 0;
        int saved = 0;
        for (CbuCurrencyResponse rate : rates) {
            processed++;
            if (cbuRateProcessor.processRate(rate)) {
                saved++;
            }
        }
        String summary = "CBU sync complete: processed=%d, saved=%d".formatted(processed, saved);
        log.info(summary);
        return summary;
    }
}
