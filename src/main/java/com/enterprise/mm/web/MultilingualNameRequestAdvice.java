package com.enterprise.mm.web;

import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.payload.mxik.MXIKCreateRequest;
import com.enterprise.mm.payload.mxik.MXIKUpdateRequest;
import com.enterprise.mm.util.BusinessNameGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Lets every existing DTO keep its single String name field while accepting
 * the frontend contract {@code name: {ru, en, uz}}.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class MultilingualNameRequestAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(
        MethodParameter methodParameter,
        Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return methodParameter.getContainingClass() != com.enterprise.mm.controller.CopilotController.class
            && AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
        HttpInputMessage inputMessage,
        MethodParameter parameter,
        Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) throws IOException {
        if (targetType == MXIKCreateRequest.class || targetType == MXIKUpdateRequest.class) {
            return normalizeMxikBody(inputMessage);
        }
        JsonNode tree = objectMapper.readTree(inputMessage.getBody());
        combineNames(tree);
        supplyMissingRootName(tree, targetType);
        byte[] body = objectMapper.writeValueAsBytes(tree);
        return inputMessage(body, inputMessage.getHeaders());
    }

    private HttpInputMessage normalizeMxikBody(HttpInputMessage source) throws IOException {
        JsonNode tree = objectMapper.readTree(source.getBody());
        if (tree instanceof ObjectNode objectNode) {
            JsonNode current = objectNode.get("name");
            if (current instanceof ObjectNode translations) {
                objectNode.put(
                    "name",
                    nodeText(
                        translations.get("ru"),
                        nodeText(translations.get("en"), nodeText(translations.get("uz"), null))
                    )
                );
            }
            JsonNode legacyRu = objectNode.remove("nameRu");
            JsonNode legacyEn = objectNode.remove("nameEn");
            JsonNode legacyUz = objectNode.remove("nameUz");
            JsonNode legacyUzLatn = objectNode.remove("nameUzLatn");
            JsonNode name = objectNode.get("name");
            if (name == null || name.isNull() || name.asText().isBlank()) {
                objectNode.put(
                    "name",
                    nodeText(
                        legacyRu,
                        nodeText(legacyEn, nodeText(legacyUz, nodeText(legacyUzLatn, null)))
                    )
                );
            }
        }
        return inputMessage(objectMapper.writeValueAsBytes(tree), source.getHeaders());
    }

    private HttpInputMessage inputMessage(byte[] body, HttpHeaders headers) {
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private void combineNames(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            combineLegacyNameFields(objectNode);
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if ("name".equals(field.getKey()) && value instanceof ObjectNode translations) {
                    objectNode.put(
                        "name",
                        LocalizedText.ensureComplete(
                            LocalizedText.format(
                                text(translations, "ru"),
                                text(translations, "en"),
                                text(translations, "uz")
                            )
                        )
                    );
                } else {
                    combineNames(value);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::combineNames);
        }
    }

    private void combineLegacyNameFields(ObjectNode objectNode) {
        JsonNode legacyRu = objectNode.remove("nameRu");
        JsonNode legacyEn = objectNode.remove("nameEn");
        JsonNode legacyUz = objectNode.remove("nameUz");
        JsonNode legacyUzLatn = objectNode.remove("nameUzLatn");
        if (legacyRu == null && legacyEn == null && legacyUz == null && legacyUzLatn == null) {
            return;
        }

        JsonNode current = objectNode.get("name");
        String fallback = current == null || current.isNull() ? null : current.asText();
        objectNode.put(
            "name",
            LocalizedText.ensureComplete(
                LocalizedText.format(
                    nodeText(legacyRu, fallback),
                    nodeText(legacyEn, fallback),
                    nodeText(legacyUz != null ? legacyUz : legacyUzLatn, fallback)
                )
            )
        );
    }

    private String text(ObjectNode translations, String language) {
        JsonNode value = translations.get(language);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String nodeText(JsonNode value, String fallback) {
        return value == null || value.isNull() || value.asText().isBlank()
            ? fallback
            : value.asText();
    }

    private void supplyMissingRootName(JsonNode tree, Type targetType) {
        if (!(tree instanceof ObjectNode objectNode) || !(targetType instanceof Class<?> targetClass)) {
            return;
        }
        if (!hasField(targetClass, "name")) {
            return;
        }
        JsonNode name = objectNode.get("name");
        if (name == null || name.isNull() || (name.isTextual() && name.textValue().isBlank())) {
            objectNode.put("name", BusinessNameGenerator.defaultName(entityType(targetClass)));
        }
    }

    private boolean hasField(Class<?> type, String fieldName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                current.getDeclaredField(fieldName);
                return true;
            } catch (NoSuchFieldException ignored) {
                // Continue through the DTO hierarchy.
            }
        }
        return false;
    }

    private String entityType(Class<?> requestType) {
        return requestType.getSimpleName()
            .replaceAll("(Create|Update|Save|Upsert)?Request$", "")
            .replaceAll("Dto$", "");
    }
}
