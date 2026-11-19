package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrudSearchEndpointSmokeTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void discoversBroadCrudSearchSurface() {
        assertThat(searchEndpointPaths()).hasSizeGreaterThan(100);
    }

    @Test
    void includesNonMovingStockCasesSearchEndpoint() {
        assertThat(searchEndpointPaths()).contains("/api/non-moving-stock-cases");
    }

    @TestFactory
    Stream<DynamicTest> searchEndpointsReturnPageEnvelope() {
        return searchEndpointPaths().stream()
            .map(path -> DynamicTest.dynamicTest("GET " + path, () ->
                mockMvc.perform(get(path)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Tenant-Id", "default")
                        .param("page", "0")
                        .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(1))
            ));
    }

    private List<String> searchEndpointPaths() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
            .filter(entry -> entry.getValue().getMethod().getName().equals("search"))
            .filter(entry -> entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET))
            .flatMap(entry -> patternValues(entry.getKey()).stream())
            .filter(path -> path.startsWith("/api/"))
            .filter(path -> !path.contains("{"))
            .sorted()
            .distinct()
            .toList();
    }

    private List<String> patternValues(RequestMappingInfo mapping) {
        if (mapping.getPathPatternsCondition() != null) {
            return mapping.getPathPatternsCondition().getPatternValues().stream().toList();
        }
        return mapping.getPatternsCondition().getPatterns().stream().toList();
    }
}
