package com.enterprise.mm.integration;

import com.enterprise.mm.error.CbuIntegrationException;
import com.enterprise.mm.payload.integration.CbuCurrencyResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class CbuCurrencyClient {
    private final RestTemplate restTemplate;

    @Value("${mm.integrations.cbu.url:https://cbu.uz/uz/arkhiv-kursov-valyut/json/}")
    private String cbuUrl;

    public List<CbuCurrencyResponse> fetchRates() {
        try {
            CbuCurrencyResponse[] responses = restTemplate.getForObject(cbuUrl, CbuCurrencyResponse[].class);
            if (responses == null || responses.length == 0) {
                log.warn("CBU API returned null or empty response");
                return Collections.emptyList();
            }
            return Arrays.asList(responses);
        } catch (Exception e) {
            throw new CbuIntegrationException("Failed to fetch currency rates from CBU", e);
        }
    }
}
