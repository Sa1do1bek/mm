package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Notification;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.NotificationMapper;
import com.enterprise.mm.repository.NotificationRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class NotificationReadServiceTest {
    private static final String TENANT = "tenant-notification-read";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007703");

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"))
            )
        );
        repository = mock(NotificationRepository.class);
        service = new NotificationService(
            repository,
            mock(NotificationMapper.class),
            mock(AuditService.class),
            mock(FirebasePushNotificationSender.class),
            mock(UserRepository.class),
            mock(NotificationLocalizationService.class),
            mock(NotificationEntityNavigationResolver.class)
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void markReadCurrentUserMarksOwnUnreadNotification() {
        Notification notification = notification("alice", false);
        when(repository.findByIdAndIsDeletedFalse(notification.getId()))
            .thenReturn(Optional.of(notification));

        service.markReadCurrentUser(notification.getId());

        verify(repository).save(notification);
        org.assertj.core.api.Assertions.assertThat(notification.isRead()).isTrue();
    }

    @Test
    void markReadCurrentUserAllowsNotificationAddressedToCurrentRole() {
        Notification notification = notification("PROCUREMENT_MANAGER", false);
        when(repository.findByIdAndIsDeletedFalse(notification.getId()))
            .thenReturn(Optional.of(notification));

        service.markReadCurrentUser(notification.getId());

        verify(repository).save(notification);
        org.assertj.core.api.Assertions.assertThat(notification.isRead()).isTrue();
    }

    @Test
    void markReadCurrentUserDoesNotExposeAnotherUsersNotification() {
        Notification notification = notification("bob", false);
        when(repository.findByIdAndIsDeletedFalse(notification.getId()))
            .thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.markReadCurrentUser(notification.getId()))
            .isInstanceOf(NotFoundException.class);

        verify(repository, never()).save(notification);
        org.assertj.core.api.Assertions.assertThat(notification.isRead()).isFalse();
    }

    private Notification notification(String recipient, boolean read) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setTenantId(TENANT);
        notification.setCompanyId(COMPANY_ID);
        notification.setRecipientUsername(recipient);
        notification.setRead(read);
        return notification;
    }
}
