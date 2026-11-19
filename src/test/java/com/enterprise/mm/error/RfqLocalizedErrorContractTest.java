package com.enterprise.mm.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RfqLocalizedErrorContractTest {
    private static final List<String> KEYS = List.of(
        "rfq.publish.not_draft",
        "rfq.publish.supplier_email_invalid",
        "rfq.portal.link_invalid",
        "rfq.proposal.original_required",
        "rfq.proposal.revision_conflict"
    );

    @Autowired MessageSource messageSource;

    @Test
    void stableRfqErrorsHaveEnglishRussianAndUzbekMessages() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"), Locale.forLanguageTag("uz"))) {
            for (String key : KEYS) {
                assertThat(messageSource.getMessage(key, null, key, locale)).isNotEqualTo(key);
            }
        }
    }

    @Test
    void globalHandlerReturnsTheStableRfqKeyAsErrorCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messageSource);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ru");

        var response = handler.business(new BusinessException(
            "Only a draft RFQ can be published", "rfq.publish.not_draft"
        ), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("rfq.publish.not_draft");
        assertThat(response.getBody().message()).isNotEqualTo("Only a draft RFQ can be published");
    }
}
