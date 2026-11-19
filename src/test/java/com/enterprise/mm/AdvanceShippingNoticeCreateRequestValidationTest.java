package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.AdvanceShippingNoticeCreateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class AdvanceShippingNoticeCreateRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsLongLocalizedNamesGeneratedFromPurchaseOrderContext() {
        AdvanceShippingNoticeCreateRequest request = new AdvanceShippingNoticeCreateRequest();
        request.setName("""
            uz: %s
            ru: %s
            en: %s
            """.formatted("U".repeat(1000), "R".repeat(1000), "E".repeat(1000)));

        assertThat(request.getName().length()).isGreaterThan(3000);
        assertThat(validator.validate(request)).isEmpty();
    }
}
