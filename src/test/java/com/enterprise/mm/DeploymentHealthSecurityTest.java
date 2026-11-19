package com.enterprise.mm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.seed.enabled=false", "management.health.mail.enabled=false", "management.health.rabbit.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles({"prod", "test"})
@Import(DeploymentHealthSecurityTest.UnrelatedHealthConfiguration.class)
class DeploymentHealthSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @BeforeEach
    void ready() {
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void publicDeploymentProbeUsesDatabaseAndReadinessWithNoComponentDetails() throws Exception {
        // A failing unrelated indicator must affect aggregate health but not this group.
        mvc.perform(get("/actuator/health/deployment"))
            .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}", true));
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/readiness", "/actuator/health/db", "/actuator/health/deployment/db", "/actuator/info"})
    void otherActuatorHealthPathsStillRequireAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void deploymentProbeHasNoUnauthenticatedWriteRoute() throws Exception {
        mvc.perform(post("/actuator/health/deployment")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class UnrelatedHealthConfiguration {
        @Bean
        HealthIndicator unrelatedDeploymentProbeHealthIndicator() {
            return () -> Health.down().withDetail("private", "test-only diagnostic").build();
        }
    }
}
