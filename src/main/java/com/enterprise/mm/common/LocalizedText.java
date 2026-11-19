package com.enterprise.mm.common;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Handles the line-based multilingual text format already used by descriptions:
 * {@code ru: ...\nen: ...\nuz: ...}.
 */
public final class LocalizedText {
    public static final String DEFAULT_LANGUAGE = "ru";

    private static final Pattern LANGUAGE_MARKER =
        Pattern.compile("(?im)(?:^|\\R)[ \\t]*(ru|en|uz)[ \\t]*:");
    private static final Pattern TRANSLATION =
        Pattern.compile(
            "(?is)(?:^|\\R)[ \\t]*(ru|en|uz)[ \\t]*:[ \\t]*(.*?)(?=\\R[ \\t]*(?:ru|en|uz)[ \\t]*:|\\z)"
        );

    private LocalizedText() {
    }

    public static String format(String ru, String en, String uz) {
        return "ru: " + text(ru) + "\nen: " + text(en) + "\nuz: " + text(uz);
    }

    public static String forCurrentLocale(String storedValue) {
        return translate(storedValue, LocaleContextHolder.getLocale());
    }

    public static boolean isMultilingual(String value) {
        return value != null && LANGUAGE_MARKER.matcher(value).find();
    }

    /**
     * Converts legacy/plain or incomplete multilingual values to the canonical
     * three-language storage representation.
     */
    public static String ensureComplete(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!LANGUAGE_MARKER.matcher(value).find()) {
            String normalized = value.trim();
            return format(normalized, normalized, normalized);
        }

        Map<String, String> translations = parse(value);
        String fallback = firstNonBlank(
            translations.get("en"),
            translations.get(DEFAULT_LANGUAGE),
            translations.get("uz")
        );
        if (fallback == null) {
            return null;
        }
        return format(
            firstNonBlank(translations.get("ru"), fallback),
            firstNonBlank(translations.get("en"), fallback),
            firstNonBlank(translations.get("uz"), fallback)
        );
    }

    public static String translate(String storedValue, Locale locale) {
        if (storedValue == null || storedValue.isBlank()) {
            return storedValue;
        }
        if (!LANGUAGE_MARKER.matcher(storedValue).find()) {
            return storedValue;
        }

        Map<String, String> translations = parse(storedValue);
        String requestedLanguage = language(locale);
        String preferred = firstNonBlank(
            translations.get(requestedLanguage),
            translations.get("en"),
            translations.get("ru"),
            translations.get("uz")
        );
        if (preferred != null) {
            return preferred;
        }
        return translations.values().stream()
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    public static Map<String, String> parse(String storedValue) {
        Map<String, String> translations = new LinkedHashMap<>();
        Matcher matcher = TRANSLATION.matcher(storedValue == null ? "" : storedValue);
        while (matcher.find()) {
            translations.put(
                matcher.group(1).toLowerCase(Locale.ROOT),
                matcher.group(2).trim()
            );
        }
        return translations;
    }

    private static String language(Locale locale) {
        String language = locale == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        return switch (language) {
            case "ru", "en", "uz" -> language;
            default -> language;
        };
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
