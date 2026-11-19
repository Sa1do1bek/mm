package com.enterprise.mm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.payload.mxik.MXIKCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MultilingualNameAdviceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void combinesThreeInputValuesBeforeDtoDeserialization() throws Exception {
        MultilingualNameRequestAdvice advice = new MultilingualNameRequestAdvice(objectMapper);
        MockHttpInputMessage input = new MockHttpInputMessage(
            """
            {"name":{"ru":"Перчатки","en":"Gloves","uz":"Qo‘lqoplar"},"kod":"123","type":"PRODUCT"}
            """.getBytes(StandardCharsets.UTF_8)
        );

        JsonNode result = objectMapper.readTree(
            advice.beforeBodyRead(
                input,
                null,
                NamedRequest.class,
                MappingJackson2HttpMessageConverter.class
            ).getBody()
        );

        assertThat(result.get("name").textValue())
            .isEqualTo("ru: Перчатки\nen: Gloves\nuz: Qo‘lqoplar");
    }

    @Test
    void keepsMxikNameSingleLanguage() throws Exception {
        MultilingualNameRequestAdvice advice = new MultilingualNameRequestAdvice(objectMapper);
        MockHttpInputMessage input = new MockHttpInputMessage(
            """
            {"name":"MXIK mahsuloti","nameRu":"Старое имя","kod":"123","type":"PRODUCT"}
            """.getBytes(StandardCharsets.UTF_8)
        );

        JsonNode result = objectMapper.readTree(
            advice.beforeBodyRead(
                input,
                null,
                MXIKCreateRequest.class,
                MappingJackson2HttpMessageConverter.class
            ).getBody()
        );

        assertThat(result.get("name").textValue()).isEqualTo("MXIK mahsuloti");
        assertThat(result.has("nameRu")).isFalse();
    }

    @Test
    void returnsLocalizedNameUnlessEditingClientRequestsRawValue() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        LocalizedNameResponseAdvice advice = new LocalizedNameResponseAdvice(objectMapper);
        String stored = LocalizedText.format("Перчатки", "Gloves", "Qo‘lqoplar");
        Map<String, Object> body = Map.of("id", 1, "name", stored);

        Object localized = advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            request(null),
            response()
        );
        assertThat(((JsonNode) localized).get("name").textValue()).isEqualTo("Gloves");

        Object raw = advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            request("raw"),
            response()
        );
        assertThat(raw).isSameAs(body);
    }

    @Test
    void prefersAcceptLanguageHeaderOverLocaleContextForResponses() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        LocalizedNameResponseAdvice advice = new LocalizedNameResponseAdvice(objectMapper);
        String stored = LocalizedText.format("Russian title", "English title", "Uzbek title");
        Map<String, Object> body = Map.of("id", 1, "name", stored);

        Object localized = advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            request(null, "ru"),
            response()
        );

        assertThat(((JsonNode) localized).get("name").textValue()).isEqualTo("Russian title");
    }

    @Test
    void localizesCompanyNameAliasesInResponses() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("uz"));
        LocalizedNameResponseAdvice advice = new LocalizedNameResponseAdvice(objectMapper);
        String stored = LocalizedText.format(
            "Навоийазот аксиядорлик жамияти",
            "Navoiyazot joint-stock company",
            "Navoiyazot aksiyadorlik jamiyati"
        );
        Map<String, Object> body = Map.of(
            "companyName", stored,
            "fullName", "Sarvar Ruziboyev",
            "description", stored
        );

        Object localized = advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            request(null),
            response()
        );

        JsonNode tree = (JsonNode) localized;
        assertThat(tree.get("companyName").textValue()).isEqualTo("Navoiyazot aksiyadorlik jamiyati");
        assertThat(tree.get("fullName").textValue()).isEqualTo("Sarvar Ruziboyev");
        assertThat(tree.get("description").textValue()).isEqualTo("Navoiyazot aksiyadorlik jamiyati");
    }

    @Test
    void localizesTaggedTextEvenWhenFieldIsNotNamedName() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        LocalizedNameResponseAdvice advice = new LocalizedNameResponseAdvice(objectMapper);
        String stored = LocalizedText.format("Склад", "Warehouse", "Ombor");
        Map<String, Object> body = Map.of(
            "label", stored,
            "display", stored,
            "values", List.of(stored),
            "serviceDescription", stored,
            "defectDescription", stored,
            "localizedDescriptions", List.of(stored)
        );

        Object localized = advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            request(null),
            response()
        );

        JsonNode tree = (JsonNode) localized;
        assertThat(tree.get("label").textValue()).isEqualTo("Warehouse");
        assertThat(tree.get("display").textValue()).isEqualTo("Warehouse");
        assertThat(tree.get("values").get(0).textValue()).isEqualTo("Warehouse");
        assertThat(tree.get("serviceDescription").textValue()).isEqualTo("Warehouse");
        assertThat(tree.get("defectDescription").textValue()).isEqualTo("Warehouse");
        assertThat(tree.get("localizedDescriptions").get(0).textValue()).isEqualTo("Warehouse");
    }

    private ServletServerHttpRequest request(String rawHeader) {
        return request(rawHeader, null);
    }

    private ServletServerHttpRequest request(String rawHeader, String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (rawHeader != null) {
            request.addHeader(LocalizedNameResponseAdvice.RAW_HEADER, rawHeader);
        }
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        return new ServletServerHttpRequest(request);
    }

    private ServletServerHttpResponse response() {
        HttpServletResponse response = new MockHttpServletResponse();
        return new ServletServerHttpResponse(response);
    }

    private static class NamedRequest {
        @SuppressWarnings("unused")
        private String name;
    }
}
