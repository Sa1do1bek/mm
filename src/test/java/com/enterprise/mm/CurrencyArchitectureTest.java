package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.payload.CurrencyResponse;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.ExchangeRateResponse;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrencyArchitectureTest {
    @Test
    void currencyAndExchangeRateUseBusinessEntityIdentityWithCompanyScope() {
        assertThat(BusinessEntity.class).isAssignableFrom(Currency.class);
        assertThat(BusinessEntity.class).isAssignableFrom(ExchangeRate.class);

        assertThat(hasMethod(Currency.class, "getCompanyId")).isTrue();
        assertThat(hasMethod(Currency.class, "setCompanyId")).isTrue();
        assertThat(hasMethod(Currency.class, "getTenantId")).isTrue();
        assertThat(hasMethod(ExchangeRate.class, "getCompanyId")).isTrue();
        assertThat(hasMethod(ExchangeRate.class, "setCompanyId")).isTrue();
        assertThat(hasMethod(ExchangeRate.class, "getTenantId")).isTrue();
    }

    @Test
    void currencyAndExchangeRatePayloadsUseUuidIds() throws Exception {
        assertThat(CurrencyResponse.class.getMethod("getId").getReturnType()).isEqualTo(UUID.class);
        assertThat(ExchangeRateRequest.class.getDeclaredMethod("getCurrencyId").getReturnType()).isEqualTo(UUID.class);
        assertThat(ExchangeRateResponse.class.getMethod("getId").getReturnType()).isEqualTo(UUID.class);
    }

    private boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
