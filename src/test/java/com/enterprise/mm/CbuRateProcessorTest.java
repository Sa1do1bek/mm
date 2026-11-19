package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.integration.CbuRateProcessor;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.integration.CbuCurrencyResponse;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CbuRateProcessorTest {
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private ExchangeRateService exchangeRateService;
    @InjectMocks
    private CbuRateProcessor processor;

    @Test
    void createsMissingCurrencyAndUpsertsRateUsingNominal() {
        CbuCurrencyResponse response = new CbuCurrencyResponse();
        response.setCode("840");
        response.setCcy("USD");
        response.setCcyNmEn("US Dollar");
        response.setCcyNmRu("Доллар США");
        response.setCcyNmUz("AQSh dollari");
        response.setCcyNmUzc("АҚШ доллари");
        response.setNominal("10");
        response.setRate("126500.50");
        response.setDate("18.06.2026");

        UUID currencyId = UUID.randomUUID();
        when(currencyRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), "USD")).thenReturn(Optional.empty());
        when(currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(TenantContext.currentTenant(), "USD")).thenReturn(Optional.empty());
        when(currencyRepository.save(any(Currency.class))).thenAnswer(invocation -> {
            Currency currency = invocation.getArgument(0);
            currency.setId(currencyId);
            return currency;
        });

        processor.processRate(response);

        ArgumentCaptor<Currency> currencyCaptor = ArgumentCaptor.forClass(Currency.class);
        verify(currencyRepository).save(currencyCaptor.capture());
        Currency savedCurrency = currencyCaptor.getValue();
        assertThat(savedCurrency.getCode()).isEqualTo("USD");
        assertThat(savedCurrency.getIsoCode()).isEqualTo("USD");
        assertThat(savedCurrency.getNumericCode()).isEqualTo("840");
        assertThat(savedCurrency.getName()).contains("en: US Dollar");

        ArgumentCaptor<ExchangeRateRequest> rateCaptor = ArgumentCaptor.forClass(ExchangeRateRequest.class);
        verify(exchangeRateService).upsert(rateCaptor.capture());
        ExchangeRateRequest request = rateCaptor.getValue();
        assertThat(request.getCurrencyId()).isEqualTo(currencyId);
        assertThat(request.getRateDate()).isEqualTo(LocalDate.of(2026, 6, 18));
        assertThat(request.getRateToBase()).isEqualByComparingTo(new BigDecimal("12650.050000"));
        assertThat(request.getSource()).isEqualTo("CBU");
    }
}
