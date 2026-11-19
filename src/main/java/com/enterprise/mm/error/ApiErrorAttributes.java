package com.enterprise.mm.error;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> base = super.getErrorAttributes(
            webRequest,
            options.including(
                ErrorAttributeOptions.Include.MESSAGE,
                ErrorAttributeOptions.Include.EXCEPTION
            )
        );

        Throwable error = getError(webRequest);
        String message = error == null ? fallbackMessage(base) : safeMessage(error);
        String errorCode = error == null ? fallbackErrorCode(base) : error.getClass().getSimpleName();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", null);
        response.put("message", message);
        response.put("errorCode", errorCode);
        response.put("timestamp", LocalDateTime.now());
        return response;
    }

    private String fallbackMessage(Map<String, Object> attributes) {
        Object rawMessage = attributes.get("message");
        String message = rawMessage == null ? "" : String.valueOf(rawMessage).trim();
        if (!message.isEmpty() && !"null".equalsIgnoreCase(message)) {
            return message;
        }
        return "No exception message available";
    }

    private String fallbackErrorCode(Map<String, Object> attributes) {
        Object exception = attributes.get("exception");
        if (exception == null) {
            return "InternalServerError";
        }
        String value = String.valueOf(exception).trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return "InternalServerError";
        }
        int lastDot = value.lastIndexOf('.');
        return lastDot >= 0 ? value.substring(lastDot + 1) : value;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "No exception message available";
        }
        return throwable.getMessage();
    }
}