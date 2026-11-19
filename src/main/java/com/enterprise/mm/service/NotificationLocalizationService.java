package com.enterprise.mm.service;

import com.enterprise.mm.entity.Notification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationLocalizationService {
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "ru", "uz");
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public RenderedNotification render(Notification notification, Locale requestedLocale) {
        Locale locale = normalizeLocale(requestedLocale);
        Map<String, Object> args = args(notification);
        String title = renderPart(notification == null ? null : notification.getTitleKey(),
                args,
                notification == null ? null : notification.getTitle(),
                locale);
        String message = renderPart(notification == null ? null : notification.getMessageKey(),
                args,
                notification == null ? null : notification.getMessage(),
                locale);
        return new RenderedNotification(title, message, language(locale), args);
    }

    public String renderTitle(String titleKey, String fallback, Map<String, Object> args, Locale requestedLocale) {
        return renderPart(titleKey, safeArgs(args), fallback, normalizeLocale(requestedLocale));
    }

    public String renderMessage(String messageKey, String fallback, Map<String, Object> args, Locale requestedLocale) {
        return renderPart(messageKey, safeArgs(args), fallback, normalizeLocale(requestedLocale));
    }

    public String serializeArgs(Map<String, Object> args) {
        Map<String, Object> safeArgs = safeArgs(args);
        if (safeArgs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(safeArgs);
        } catch (JsonProcessingException ex) {
            log.warn("Cannot serialize notification template args: {}", ex.getMessage());
            return null;
        }
    }

    public Map<String, Object> deserializeArgs(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> args = objectMapper.readValue(json, MAP_TYPE);
            return safeArgs(args);
        } catch (JsonProcessingException ex) {
            log.warn("Cannot deserialize notification template args: {}", ex.getMessage());
            return Map.of();
        }
    }

    public Locale normalizeLocale(Locale locale) {
        if (locale == null || !StringUtils.hasText(locale.getLanguage())) {
            return DEFAULT_LOCALE;
        }
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        return SUPPORTED_LANGUAGES.contains(language) ? Locale.forLanguageTag(language) : DEFAULT_LOCALE;
    }

    public String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return language(DEFAULT_LOCALE);
        }
        String normalized = Locale.forLanguageTag(language.trim()).getLanguage().toLowerCase(Locale.ROOT);
        return SUPPORTED_LANGUAGES.contains(normalized) ? normalized : language(DEFAULT_LOCALE);
    }

    public String language(Locale locale) {
        return normalizeLocale(locale).getLanguage();
    }

    public Map<String, Object> args(Notification notification) {
        return notification == null ? Map.of() : deserializeArgs(notification.getTemplateArgsJson());
    }

    private String renderPart(String key, Map<String, Object> args, String fallback, Locale locale) {
        if (!StringUtils.hasText(key)) {
            return value(fallback);
        }
        String pattern = messagePattern(key, locale);
        if (pattern == null && !DEFAULT_LOCALE.getLanguage().equals(locale.getLanguage())) {
            pattern = messagePattern(key, DEFAULT_LOCALE);
        }
        if (pattern == null) {
            return value(fallback);
        }
        return replaceNamedArgs(pattern, args);
    }

    private String messagePattern(String key, Locale locale) {
        try {
            return messageSource.getMessage(key, null, locale);
        } catch (NoSuchMessageException ex) {
            return null;
        }
    }

    private String replaceNamedArgs(String pattern, Map<String, Object> args) {
        String result = pattern;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", value(entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> safeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if (StringUtils.hasText(key)) {
                safe.put(key.trim(), value == null ? "" : value);
            }
        });
        return safe;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record RenderedNotification(String title, String message, String language, Map<String, Object> args) {
    }
}
