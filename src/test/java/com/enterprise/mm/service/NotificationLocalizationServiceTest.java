package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.Notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class NotificationLocalizationServiceTest {
    private NotificationLocalizationService service;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("message");
        messageSource.setDefaultEncoding("UTF-8");
        service = new NotificationLocalizationService(messageSource, new ObjectMapper());
    }

    @Test
    void rendersNotificationTemplateInRequestedLanguage() {
        Notification notification = new Notification();
        notification.setTitle("Service approval required");
        notification.setMessage("Approval is required for SR-100");
        notification.setTitleKey("notification.service.approvalRequired.title");
        notification.setMessageKey("notification.service.approvalRequired.message");
        notification.setTemplateArgsJson(service.serializeArgs(Map.of("reference", "SR-100")));

        NotificationLocalizationService.RenderedNotification ru = service.render(notification, Locale.forLanguageTag("ru"));
        NotificationLocalizationService.RenderedNotification uz = service.render(notification, Locale.forLanguageTag("uz"));
        NotificationLocalizationService.RenderedNotification en = service.render(notification, Locale.ENGLISH);

        assertThat(ru.title()).isEqualTo("Требуется согласование услуги");
        assertThat(ru.message()).isEqualTo("Требуется согласование для SR-100");
        assertThat(ru.language()).isEqualTo("ru");
        assertThat(uz.title()).isEqualTo("Xizmatni tasdiqlash kerak");
        assertThat(uz.message()).isEqualTo("SR-100 uchun tasdiqlash kerak");
        assertThat(uz.language()).isEqualTo("uz");
        assertThat(en.title()).isEqualTo("Service approval required");
        assertThat(en.message()).isEqualTo("Approval is required for SR-100");
        assertThat(en.language()).isEqualTo("en");
    }

    @Test
    void fallsBackToStoredTextWhenTemplateKeyIsMissing() {
        Notification notification = new Notification();
        notification.setTitle("Stored title");
        notification.setMessage("Stored message");
        notification.setTitleKey("notification.missing.title");
        notification.setMessageKey("notification.missing.message");

        NotificationLocalizationService.RenderedNotification rendered = service.render(notification, Locale.forLanguageTag("ru"));

        assertThat(rendered.title()).isEqualTo("Stored title");
        assertThat(rendered.message()).isEqualTo("Stored message");
        assertThat(rendered.language()).isEqualTo("ru");
    }

    @Test
    void unsupportedLanguageFallsBackToEnglish() {
        Notification notification = new Notification();
        notification.setTitleKey("notification.mrp.shortage.title");
        notification.setMessageKey("notification.mrp.shortage.message");
        notification.setTemplateArgsJson(service.serializeArgs(Map.of(
            "materialCode", "MAT-7",
            "quantity", "12"
        )));

        NotificationLocalizationService.RenderedNotification rendered = service.render(notification, Locale.forLanguageTag("fr"));

        assertThat(rendered.title()).isEqualTo("Material shortage: MAT-7");
        assertThat(rendered.message()).isEqualTo("Recommended purchase quantity: 12");
        assertThat(rendered.language()).isEqualTo("en");
    }
}
