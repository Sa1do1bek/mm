package com.enterprise.mm.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocalizedTextTest {
    private static final String VALUE = """
        ru: Русское название
        en: English name
        uz: O‘zbekcha nom
        """;

    @Test
    void formatsUsingExistingDescriptionStorageConvention() {
        assertThat(LocalizedText.format("Русское название", "English name", "O‘zbekcha nom"))
            .isEqualTo("ru: Русское название\nen: English name\nuz: O‘zbekcha nom");
    }

    @Test
    void returnsRequestedTranslation() {
        assertThat(LocalizedText.translate(VALUE, Locale.forLanguageTag("uz-UZ")))
            .isEqualTo("O‘zbekcha nom");
        assertThat(LocalizedText.translate(VALUE, Locale.ENGLISH))
            .isEqualTo("English name");
    }

    @Test
    void fallsBackToRussianAndKeepsPlainTextUnchanged() {
        assertThat(LocalizedText.translate("ru: Запасное имя\nen: \nuz: ", Locale.ENGLISH))
            .isEqualTo("Запасное имя");
        assertThat(LocalizedText.translate("ru: \nen: \nuz: ", Locale.ENGLISH))
            .isNull();
        assertThat(LocalizedText.translate("Client supplied name", Locale.forLanguageTag("uz")))
            .isEqualTo("Client supplied name");
    }

    @Test
    void fallsBackFromBlankRequestedLanguageToEnglishThenRussianThenUzbek() {
        assertThat(LocalizedText.translate("ru: Russian name\nen: English name\nuz: ", Locale.forLanguageTag("uz")))
            .isEqualTo("English name");
        assertThat(LocalizedText.translate("ru: Russian name\nen: \nuz: Uzbek name", Locale.forLanguageTag("fr")))
            .isEqualTo("Russian name");
        assertThat(LocalizedText.translate("ru: \nen: \nuz: Uzbek name", Locale.ENGLISH))
            .isEqualTo("Uzbek name");
    }

    @Test
    void completesLegacyAndPartiallyTranslatedValues() {
        assertThat(LocalizedText.ensureComplete("Legacy name"))
            .isEqualTo("ru: Legacy name\nen: Legacy name\nuz: Legacy name");
        assertThat(LocalizedText.ensureComplete("en: Safety gloves"))
            .isEqualTo("ru: Safety gloves\nen: Safety gloves\nuz: Safety gloves");
        assertThat(LocalizedText.ensureComplete("ru: Russian name\nen: English name\nuz: "))
            .isEqualTo("ru: Russian name\nen: English name\nuz: English name");
        assertThat(LocalizedText.ensureComplete("ru: \nen: \nuz: "))
            .isNull();
    }

    @Test
    void exposesReusableParser() {
        assertThat(LocalizedText.parse(VALUE))
            .containsEntry("en", "English name")
            .containsKeys("ru", "uz");
    }
}
