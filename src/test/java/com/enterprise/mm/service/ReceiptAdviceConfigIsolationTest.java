package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Uses the actual production YAML, with synthetic overrides; never contacts a live AI service. */
class ReceiptAdviceConfigIsolationTest {
    @Configuration(proxyBeanMethods = false)
    @Import(HttpMmAiClient.class)
    static class Config {
        @Bean RestTemplateBuilder restTemplateBuilder() {
            return new RestTemplateBuilder().requestFactory(SimpleClientHttpRequestFactory::new);
        }
    }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("AI_BASE_URL=https://general.example", "AI_API_KEY=general-test-key",
                "AI_ANALYSIS_TIMEOUT=180s", "AI_CONNECT_TIMEOUT=5s")
            .withInitializer(context -> {
                try {
                    for (var source : new YamlPropertySourceLoader().load("prod-yaml", new ClassPathResource("application-prod.yml")))
                        context.getEnvironment().getPropertySources().addLast(source);
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
    }

    RestTemplate template(HttpMmAiClient client, String name) {
        return (RestTemplate) ReflectionTestUtils.getField(client, name);
    }

    void checkGeneralCall(HttpMmAiClient client) {
        var server = MockRestServiceServer.bindTo(template(client, "restTemplate")).build();
        server.expect(requestTo("https://general.example/v1/quotation-comparison/from-rfq"))
            .andExpect(header("X-API-Key", "general-test-key"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.compareRfqQuotations(UUID.randomUUID(), "uz");
        server.verify();
    }

    void checkReceiptCall(HttpMmAiClient client, String url, String key) {
        var server = MockRestServiceServer.bindTo(template(client, "receiptRestTemplate")).build();
        server.expect(requestTo(url + "/v1/receipt-advice/suggest"))
            .andExpect(header("X-API-Key", key))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.suggestReceiptAdvice(new ObjectMapper().createObjectNode(), "uz");
        server.verify();
    }

    @Test void absentReceiptOverridesInheritGeneralConfigWithoutChangingIt() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(HttpMmAiClient.class);
            assertThat(client.isConfigured()).isTrue();
            assertThat(client.isReceiptAdviceConfigured()).isTrue();
            checkGeneralCall(client);
            checkReceiptCall(client, "https://general.example", "general-test-key");
        });
    }

    @Test void receiptUrlKeyAndTimeoutOverrideCannotChangeOtherAiCalls() {
        runner().withPropertyValues("AI_RECEIPT_ADVICE_BASE_URL=https://receipt.example/",
            "AI_RECEIPT_ADVICE_API_KEY=receipt-test-key", "AI_RECEIPT_ADVICE_TIMEOUT=7s",
            "AI_RECEIPT_ADVICE_MAX_REQUEST_BYTES=4096").run(context -> {
                assertThat(context).hasNotFailed();
                var client = context.getBean(HttpMmAiClient.class);
                assertThat(ReflectionTestUtils.getField(template(client, "restTemplate").getRequestFactory(), "readTimeout"))
                    .isEqualTo(180000);
                assertThat(ReflectionTestUtils.getField(template(client, "receiptRestTemplate").getRequestFactory(), "readTimeout"))
                    .isEqualTo(7000);
                assertThat(context.getEnvironment().getProperty("mm.ai.receipt-advice.max-request-bytes", Integer.class))
                    .isEqualTo(4096);
                checkGeneralCall(client);
                checkReceiptCall(client, "https://receipt.example", "receipt-test-key");
            });
    }

    @Test void explicitlyEmptyReceiptKeyDisablesOnlyReceiptAdvice() {
        runner().withPropertyValues("AI_RECEIPT_ADVICE_API_KEY=").run(context -> {
            assertThat(context).hasNotFailed();
            var client = context.getBean(HttpMmAiClient.class);
            assertThat(client.isConfigured()).isTrue();
            assertThat(client.isReceiptAdviceConfigured()).isFalse();
            assertThatThrownBy(() -> client.suggestReceiptAdvice(new ObjectMapper().createObjectNode(), "uz"))
                .hasMessageContaining("not configured");
            checkGeneralCall(client);
        });
    }

    @Test void receiptCanUseItsOwnKeyWhenGeneralKeyIsEmpty() {
        runner().withPropertyValues("AI_API_KEY=", "AI_RECEIPT_ADVICE_API_KEY=receipt-test-key")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var client = context.getBean(HttpMmAiClient.class);
                assertThat(client.isConfigured()).isFalse();
                assertThat(client.isReceiptAdviceConfigured()).isTrue();
                checkReceiptCall(client, "https://general.example", "receipt-test-key");
            });
    }

    @Test void emptyReceiptUrlAndDisabledFlagDoNotDisableOtherAi() {
        runner().withPropertyValues("AI_RECEIPT_ADVICE_BASE_URL=", "AI_RECEIPT_ADVICE_ENABLED=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var client = context.getBean(HttpMmAiClient.class);
                assertThat(client.isReceiptAdviceConfigured()).isFalse();
                assertThat(client.isConfigured()).isTrue();
                assertThat(context.getEnvironment().getProperty("mm.ai.receipt-advice.enabled", Boolean.class)).isFalse();
                checkGeneralCall(client);
            });
    }
}
