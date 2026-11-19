package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.ExchangeRateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExchangeRateMapper {
    private final CurrencyMapper currencyMapper;

    public ExchangeRateResponse toResponse(ExchangeRate entity) {
        ExchangeRateResponse response = new ExchangeRateResponse();
        response.setId(entity.getId());
        response.setCurrency(currencyMapper.toResponse(entity.getCurrency()));
        response.setRateDate(entity.getRateDate());
        response.setRateToBase(entity.getRateToBase());
        response.setSource(entity.getSource());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    public void updateEntity(ExchangeRate entity, ExchangeRateRequest request, Currency currency, String source) {
        entity.setCurrency(currency);
        entity.setRateDate(request.getRateDate());
        entity.setRateToBase(normalizeRate(request.getRateToBase()));
        entity.setSource(source);
    }

    private BigDecimal normalizeRate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }
}
