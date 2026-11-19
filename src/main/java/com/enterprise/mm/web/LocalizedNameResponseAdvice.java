package com.enterprise.mm.web;

import com.enterprise.mm.common.LocalizedText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Localizes multilingual name-like API values at the HTTP boundary.
 *
 * <p>Persistence and internal service calls continue to use the complete tagged
 * value. Editing clients can explicitly request that storage representation via
 * {@value #RAW_HEADER}; normal consumers receive one localized string for both
 * name and description fields.</p>
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class LocalizedNameResponseAdvice implements ResponseBodyAdvice<Object> {
    public static final String RAW_HEADER = "X-Multilingual-Names";
    public static final String RAW_VALUE = "raw";

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getContainingClass() != com.enterprise.mm.controller.CopilotController.class
            && AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(
        Object body,
        MethodParameter returnType,
        MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        if (body == null
            || isMxikRequest(request)
            || RAW_VALUE.equalsIgnoreCase(request.getHeaders().getFirst(RAW_HEADER))) {
            return body;
        }

        JsonNode tree = objectMapper.valueToTree(body);
        localize(tree, null, requestedLocale(request));
        return tree;
    }

    private Locale requestedLocale(ServerHttpRequest request) {
        List<Locale> acceptedLocales = request.getHeaders().getAcceptLanguageAsLocales();
        return acceptedLocales.isEmpty() ? LocaleContextHolder.getLocale() : acceptedLocales.get(0);
    }

    private boolean isMxikRequest(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return path.equals("/api/mxik") || path.startsWith("/api/mxik/");
    }

    private void localize(JsonNode node, String fieldName, Locale locale) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isTextual() && shouldLocalize(field.getKey(), value.textValue())) {
                    objectNode.put(field.getKey(), LocalizedText.translate(value.textValue(), locale));
                } else {
                    localize(value, field.getKey(), locale);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode value = arrayNode.get(i);
                if (value.isTextual() && shouldLocalize(fieldName, value.textValue())) {
                    arrayNode.set(i, TextNode.valueOf(LocalizedText.translate(value.textValue(), locale)));
                } else {
                    localize(value, fieldName, locale);
                }
            }
        }
    }

    private boolean isNameField(String fieldName) {
        return fieldName != null && ("name".equals(fieldName) || fieldName.endsWith("Name"));
    }

    private boolean isDescriptionField(String fieldName) {
        return fieldName != null
            && fieldName.toLowerCase(java.util.Locale.ROOT).contains("description");
    }

    private boolean shouldLocalize(String fieldName, String value) {
        return fieldName != null
            && (isNameField(fieldName) || isDescriptionField(fieldName) || LocalizedText.isMultilingual(value));
    }
}
