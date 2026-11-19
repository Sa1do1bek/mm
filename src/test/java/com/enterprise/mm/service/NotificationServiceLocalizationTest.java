package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Notification;
import com.enterprise.mm.mapper.NotificationMapper;
import com.enterprise.mm.payload.NotificationCreateRequest;
import com.enterprise.mm.repository.NotificationRepository;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

class NotificationServiceLocalizationTest {
    private static final String TENANT = "tenant-notification-locale";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007702");

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("message");
        messageSource.setDefaultEncoding("UTF-8");
        NotificationLocalizationService localizationService =
            new NotificationLocalizationService(messageSource, new ObjectMapper());
        repository = mock(NotificationRepository.class);
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(UUID.randomUUID());
            return notification;
        });
        service = new NotificationService(
            repository,
            new NotificationMapper(),
            mock(AuditService.class),
            mock(FirebasePushNotificationSender.class),
            mock(UserRepository.class),
            localizationService,
            new NotificationEntityNavigationResolver(
                mock(com.enterprise.mm.repository.ApprovalTaskRepository.class),
                mock(com.enterprise.mm.repository.ApprovalInstanceRepository.class),
                mock(UserRepository.class),
                localizationService
            )
        );
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createStoresEnglishSnapshotButReturnsRequestLocaleRendering() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setRecipientUsername("buyer");
        request.setTitleKey("notification.mrp.shortage.title");
        request.setMessageKey("notification.mrp.shortage.message");
        request.setTemplateArgs(Map.of(
            "materialCode", "MAT-100",
            "quantity", "15"
        ));

        var response = service.create(request);

        assertThat(response.getTitle()).isEqualTo("Недостаток материала: MAT-100");
        assertThat(response.getMessage()).isEqualTo("Рекомендуемое количество закупки: 15");
        assertThat(response.getLanguage()).isEqualTo("ru");
        org.mockito.Mockito.verify(repository).save(org.mockito.Mockito.argThat(notification ->
            "Material shortage: MAT-100".equals(notification.getTitle())
                && "Recommended purchase quantity: 15".equals(notification.getMessage())
                && "en".equals(notification.getRenderedLanguage())
        ));
    }

    @Test
    void createPersistsEntityNavigationFields() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        UUID entityId = UUID.randomUUID();
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setRecipientUsername("approver");
        request.setTitleKey("notification.approval.task.assigned.title");
        request.setMessageKey("notification.approval.task.assigned.message.noStep");
        request.setTemplateArgs(Map.of("document", "PR-1"));
        request.setEntityType("APPROVAL_WORKFLOW");
        request.setEntityId(entityId);

        var response = service.create(request);

        assertThat(response.getEntityType()).isEqualTo("APPROVAL_WORKFLOW");
        assertThat(response.getEntityId()).isEqualTo(entityId);
        org.mockito.Mockito.verify(repository).save(org.mockito.Mockito.argThat(notification ->
            "APPROVAL_WORKFLOW".equals(notification.getEntityType())
                && entityId.equals(notification.getEntityId())
        ));
    }
}
