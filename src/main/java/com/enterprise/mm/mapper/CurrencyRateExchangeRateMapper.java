package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.CurrencyRate;
import com.enterprise.mm.payload.ExchangeRateRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class CurrencyRateExchangeRateMapper {
    public ExchangeRateRequest toExchangeRateRequest(CurrencyRate rate, BigDecimal rateToBase) {
        ExchangeRateRequest request = new ExchangeRateRequest();
        request.setCurrencyId(rate.getCurrencyId());
        request.setRateDate(rate.getRateDate());
        request.setRateToBase(rateToBase);
        request.setSource(rate.getSource());
        return request;
    }
}
