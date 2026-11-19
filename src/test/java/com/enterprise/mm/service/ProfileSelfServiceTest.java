package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProfileSelfServiceTest {
    private UserRepository users;
    private AttachmentService attachments;
    private ProfileSelfService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("profile-test");
        users = mock(UserRepository.class);
        attachments = mock(AttachmentService.class);
        service = new ProfileSelfService(users, attachments, mock(AuditService.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId("profile-test");
        user.setUsername("self");
        user.setEnabled(true);
        when(users.findCurrentUserForPasswordChange(user.getId(), "profile-test", "self"))
            .thenReturn(Optional.of(user));
        when(users.findByIdAndIsDeletedFalse(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void emailCanBeSavedAndRemovedOnCurrentUser() {
        User user = user();

        assertThat(service.updateEmail(user, "  Self@Example.com  ")).isEqualTo("self@example.com");
        assertThat(user.getEmail()).isEqualTo("self@example.com");
        verify(users).save(same(user));

        service.deleteEmail(user);
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void invalidEmailIsRejectedBeforeSaving() {
        User user = user();
        assertThatThrownBy(() -> service.updateEmail(user, "not an address"))
            .isInstanceOf(BadRequestException.class);
        verify(users, never()).save(same(user));
    }

    @Test
    void additionalEmailsCanBeAddedEditedAndListedWithoutReplacingPrimary() {
        User user = user();
        user.setEmail("first@example.com");

        assertThat(service.addEmail(user, " Second@Example.com "))
            .containsExactly(new ProfileSelfService.EmailAddress("first@example.com", true),
                new ProfileSelfService.EmailAddress("second@example.com", false));
        assertThat(service.changeEmail(user, "second@example.com", "third@example.com"))
            .containsExactly(new ProfileSelfService.EmailAddress("first@example.com", true),
                new ProfileSelfService.EmailAddress("third@example.com", false));
        assertThat(service.emails(user)).hasSize(2);
        assertThat(user.getEmail()).isEqualTo("first@example.com");
    }

    @Test
    void duplicateEmailIsRejectedAcrossPrimaryAndAdditionalAddresses() {
        User user = user();
        user.setEmail("first@example.com");
        service.addEmail(user, "second@example.com");

        assertThatThrownBy(() -> service.addEmail(user, "FIRST@example.com"))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.changeEmail(user, "second@example.com", "first@example.com"))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void primaryCanBeSwitchedAndDeletionPromotesTheRemainingAddress() {
        User user = user();
        user.setEmail("first@example.com");
        service.addEmail(user, "second@example.com");

        assertThat(service.makePrimary(user, "second@example.com"))
            .containsExactly(new ProfileSelfService.EmailAddress("second@example.com", true),
                new ProfileSelfService.EmailAddress("first@example.com", false));
        assertThat(service.removeEmail(user, "second@example.com"))
            .containsExactly(new ProfileSelfService.EmailAddress("first@example.com", true));
        assertThat(service.removeEmail(user, "first@example.com")).isEmpty();
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void legacyPrimaryChangesKeepAdditionalAddressesConsistent() {
        User user = new User();
        user.getAdditionalEmails().add("second@example.com");
        user.reconcilePrimaryEmail();
        assertThat(user.getEmail()).isEqualTo("second@example.com");
        assertThat(user.getAdditionalEmails()).isEmpty();

        user.getAdditionalEmails().add("second@example.com");
        user.getAdditionalEmails().add("other@example.com");
        user.reconcilePrimaryEmail();
        assertThat(user.getAdditionalEmails()).containsExactly("other@example.com");
    }

    @Test
    void replacingPhotoRemovesPreviousPhotoOnlyAfterSuccessfulUpload() {
        UUID userId = UUID.randomUUID();
        UUID oldId = UUID.randomUUID();
        AttachmentDto old = new AttachmentDto();
        old.setId(oldId);
        old.setAttachmentType("PROFILE_PHOTO");
        when(attachments.list("USER_PROFILE", userId)).thenReturn(List.of(old));
        var png = new MockMultipartFile("file", "avatar.png", "image/png",
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

        service.updatePhoto(userId, png);

        var order = org.mockito.Mockito.inOrder(attachments);
        order.verify(attachments).uploadProfilePhoto(eq(userId), same(png));
        order.verify(attachments).softDelete(oldId);
    }

    @Test
    void disguisedPhotoIsRejected() {
        UUID userId = UUID.randomUUID();
        var disguised = new MockMultipartFile("file", "avatar.png", "image/png", "<script>".getBytes());
        assertThatThrownBy(() -> service.updatePhoto(userId, disguised))
            .isInstanceOf(BadRequestException.class);
        verify(attachments, never()).uploadProfilePhoto(eq(userId), same(disguised));
    }
}
