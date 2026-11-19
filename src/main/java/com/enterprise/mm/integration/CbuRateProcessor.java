package com.enterprise.mm.integration;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.integration.CbuCurrencyResponse;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.service.ExchangeRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CbuRateProcessor {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CurrencyRepository currencyRepository;
    private final ExchangeRateService exchangeRateService;

    @Transactional
    public boolean processRate(CbuCurrencyResponse rateDto) {
        String code = normalizeCode(rateDto == null ? null : rateDto.getCcy());
        if (code == null) {
            return false;
        }
        BigDecimal rate = parsePositiveDecimal(rateDto.getRate(), "rate", code);
        BigDecimal nominal = parsePositiveDecimal(rateDto.getNominal(), "nominal", code);
        LocalDate rateDate = parseDate(rateDto.getDate(), code);
        if (rate == null || nominal == null || rateDate == null) {
            return false;
        }

        Currency currency = upsertCurrency(code, rateDto);
        ExchangeRateRequest request = new ExchangeRateRequest();
        request.setCurrencyId(currency.getId());
        request.setRateDate(rateDate);
        request.setRateToBase(rate.divide(nominal, 6, RoundingMode.HALF_UP));
        request.setSource("CBU");
        exchangeRateService.upsert(request);
        return true;
    }

    private Currency upsertCurrency(String code, CbuCurrencyResponse dto) {
        String tenantId = TenantContext.currentTenant();
        Currency currency = currencyRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, code)
            .or(() -> currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(tenantId, code))
            .orElseGet(Currency::new);
        currency.setCode(code);
        currency.setIsoCode(code);
        currency.setNumericCode(blankToNull(dto.getCode()));
        String fallbackName = firstNonBlank(
            dto.getCcyNmRu(),
            dto.getCcyNmEn(),
            dto.getCcyNmUz(),
            currency.getName(),
            "Currency"
        );
        currency.setName(LocalizedText.format(
            firstNonBlank(dto.getCcyNmRu(), fallbackName),
            firstNonBlank(dto.getCcyNmEn(), fallbackName),
            firstNonBlank(dto.getCcyNmUz(), dto.getCcyNmUzc(), fallbackName)
        ));
        currency.setDescription(firstNonBlank(dto.getCcyNmUz(), dto.getCcyNmRu(), dto.getCcyNmUzc(), currency.getDescription()));
        if (currency.getMinorUnit() == null) {
            currency.setMinorUnit(2);
        }
        if (currency.getStatus() == null || currency.getStatus().isBlank()) {
            currency.setStatus("ACTIVE");
        }
        return currencyRepository.save(currency);
    }

    private BigDecimal parsePositiveDecimal(String raw, String field, String code) {
        try {
            BigDecimal value = new BigDecimal(Objects.requireNonNullElse(raw, "").trim());
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("CBU {} for {} is not positive: {}", field, code, raw);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("CBU {} for {} is invalid: {}", field, code, raw);
            return null;
        }
    }

    private LocalDate parseDate(String raw, String code) {
        try {
            return LocalDate.parse(raw, DATE_FORMATTER);
        } catch (DateTimeParseException | NullPointerException e) {
            log.warn("CBU date for {} is invalid: {}", code, raw);
            return null;
        }
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
