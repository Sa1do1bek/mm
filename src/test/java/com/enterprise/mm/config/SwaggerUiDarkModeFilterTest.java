package com.enterprise.mm.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SwaggerUiDarkModeFilterTest {
    private final SwaggerUiDarkModeFilter filter = new SwaggerUiDarkModeFilter();

    @Test
    void injectsInlineThemeToggleWithoutPublicStylesheetUrl() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            servletResponse.setContentType("text/html;charset=UTF-8");
            servletResponse.getWriter().write("<html><head></head><body><div class=\"swagger-ui\"></div></body></html>");
        };

        filter.doFilter(request, response, chain);

        String html = response.getContentAsString();
        assertThat(html).contains("swagger-theme-toggle");
        assertThat(html).contains("swagger-ui-theme");
        assertThat(html).contains("localStorage");
        assertThat(html).doesNotContain("swagger-ui-dark-mode.css");
    }
}
