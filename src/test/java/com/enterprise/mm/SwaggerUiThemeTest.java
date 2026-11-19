package com.enterprise.mm;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=true",
    "springdoc.swagger-ui.enabled=true"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SwaggerUiThemeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void swaggerUiIndexEnablesBundledDarkMode() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<html lang=\"en\" class=\"dark-mode\">")));
    }

    @Test
    void swaggerUiCssIncludesBundledDarkModeRules() throws Exception {
        mockMvc.perform(get("/swagger-ui/swagger-ui.css"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("html.dark-mode .swagger-ui")));
    }
}
