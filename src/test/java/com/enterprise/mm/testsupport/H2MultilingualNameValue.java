package com.enterprise.mm.testsupport;

import com.enterprise.mm.common.LocalizedText;
import java.util.Locale;

public final class H2MultilingualNameValue {
    private H2MultilingualNameValue() {
    }

    public static String resolve(String storedName, String language) {
        if (storedName == null) {
            return null;
        }
        String requestedLanguage = language == null || language.isBlank()
            ? LocalizedText.DEFAULT_LANGUAGE
            : language.trim().toLowerCase(Locale.ROOT);
        return LocalizedText.translate(storedName, Locale.forLanguageTag(requestedLanguage));
    }
}
