package com.enterprise.mm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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

@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RequiredArgsConstructor
public class UserIdentityDisplayResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper objectMapper;
    private final UserIdentityDisplayEnricher userIdentityDisplayEnricher;

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
        if (body == null) {
            return null;
        }
        JsonNode tree = body instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(body);
        userIdentityDisplayEnricher.enrich(tree);
        return tree;
    }
}
