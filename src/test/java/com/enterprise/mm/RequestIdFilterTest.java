package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.web.MaterialCommandViewRequestIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    private final MaterialCommandViewRequestIdFilter filter = new MaterialCommandViewRequestIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void apiRoutesExposeSafeRequestIdHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/api/rfq/11111111-1111-1111-1111-111111111111/ai/quotation-comparison/jobs"
        );
        request.addHeader(MaterialCommandViewRequestIdFilter.REQUEST_ID_HEADER, "rfq-ai-req-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
            assertThat(MDC.get("requestId")).isEqualTo("rfq-ai-req-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(MaterialCommandViewRequestIdFilter.REQUEST_ID_HEADER))
            .isEqualTo("rfq-ai-req-1");
        assertThat(MDC.get("requestId")).isNull();
    }
}
