package com.enterprise.mm.service;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.repository.UserRepository;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ProfileSelfService {
    private static final String PHOTO_ENTITY = "USER_PROFILE";
    private static final String PHOTO_TYPE = "PROFILE_PHOTO";
    private static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;

    private final UserRepository userRepository;
    private final AttachmentService attachmentService;
    private final AuditService auditService;

    public record EmailAddress(String email, boolean primary) {
    }

    @Transactional(readOnly = true)
    public List<EmailAddress> emails(User actor) {
        User user = userRepository.findByIdAndIsDeletedFalse(actor.getId())
            .filter(found -> TenantContext.currentTenant().equals(found.getTenantId()))
            .orElseThrow(() -> new NotFoundException("User not found"));
        return emailAddresses(user);
    }

    @Transactional
    public List<EmailAddress> addEmail(User actor, String requestedEmail) {
        User user = locked(actor);
        String email = normalizeEmail(requestedEmail);
        requireNewEmail(user, email);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            user.setEmail(email);
        } else {
            user.getAdditionalEmails().add(email);
        }
        saveEmailChange(user, "ADD_OWN_EMAIL");
        return emailAddresses(user);
    }

    @Transactional
    public List<EmailAddress> changeEmail(User actor, String currentEmail, String requestedEmail) {
        User user = locked(actor);
        String current = lookupEmail(currentEmail);
        String next = normalizeEmail(requestedEmail);
        if (sameEmail(current, next)) return emailAddresses(user);
        requireNewEmail(user, next);
        if (sameEmail(user.getEmail(), current)) {
            user.setEmail(next);
        } else {
            String existing = secondary(user, current);
            user.getAdditionalEmails().remove(existing);
            user.getAdditionalEmails().add(next);
        }
        saveEmailChange(user, "UPDATE_OWN_EMAIL");
        return emailAddresses(user);
    }

    @Transactional
    public List<EmailAddress> removeEmail(User actor, String requestedEmail) {
        User user = locked(actor);
        String email = lookupEmail(requestedEmail);
        if (sameEmail(user.getEmail(), email)) {
            promoteFirstAdditionalOrClear(user);
        } else {
            user.getAdditionalEmails().remove(secondary(user, email));
        }
        saveEmailChange(user, "DELETE_OWN_EMAIL");
        return emailAddresses(user);
    }

    @Transactional
    public List<EmailAddress> makePrimary(User actor, String requestedEmail) {
        User user = locked(actor);
        String email = normalizeEmail(requestedEmail);
        if (sameEmail(user.getEmail(), email)) return emailAddresses(user);
        String existing = secondary(user, email);
        user.getAdditionalEmails().remove(existing);
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            user.getAdditionalEmails().add(user.getEmail().trim().toLowerCase(Locale.ROOT));
        }
        user.setEmail(email);
        saveEmailChange(user, "SET_PRIMARY_OWN_EMAIL");
        return emailAddresses(user);
    }

    @Transactional
    public String updateEmail(User user, String requestedEmail) {
        User current = locked(user);
        String email = normalizeEmail(requestedEmail);
        if (sameEmail(current.getEmail(), email)) return email;
        requireNewEmail(current, email);
        current.setEmail(email);
        saveEmailChange(current, "UPDATE_OWN_EMAIL");
        return email;
    }

    @Transactional
    public void deleteEmail(User user) {
        User current = locked(user);
        if (current.getEmail() != null) {
            promoteFirstAdditionalOrClear(current);
            saveEmailChange(current, "DELETE_OWN_EMAIL");
        }
    }

    private User locked(User actor) {
        return userRepository.findCurrentUserForPasswordChange(
            actor.getId(), TenantContext.currentTenant(), actor.getUsername()
        ).filter(User::isEnabled).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private String normalizeEmail(String requestedEmail) {
        if (requestedEmail == null) throw new BadRequestException("Email is required");
        String email = requestedEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 240
            || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BadRequestException("Enter a valid email address");
        }
        return email;
    }

    private String lookupEmail(String requestedEmail) {
        if (requestedEmail == null || requestedEmail.isBlank()) {
            throw new BadRequestException("Email is required");
        }
        return requestedEmail.trim();
    }

    private void requireNewEmail(User user, String email) {
        if (sameEmail(user.getEmail(), email) || user.getAdditionalEmails().stream().anyMatch(value -> sameEmail(value, email))) {
            throw new BadRequestException("This email is already on your profile");
        }
    }

    private String secondary(User user, String email) {
        return user.getAdditionalEmails().stream().filter(value -> sameEmail(value, email)).findFirst()
            .orElseThrow(() -> new NotFoundException("Email not found on your profile"));
    }

    private boolean sameEmail(String first, String second) {
        return first != null && second != null && first.trim().equalsIgnoreCase(second.trim());
    }

    private void promoteFirstAdditionalOrClear(User user) {
        String next = user.getAdditionalEmails().stream().min(String.CASE_INSENSITIVE_ORDER).orElse(null);
        if (next != null) user.getAdditionalEmails().remove(next);
        user.setEmail(next);
    }

    private List<EmailAddress> emailAddresses(User user) {
        String primary = user.getEmail();
        List<EmailAddress> additional = user.getAdditionalEmails().stream()
            .filter(value -> value != null && !sameEmail(value, primary))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .map(value -> new EmailAddress(value, false))
            .toList();
        if (primary == null || primary.isBlank()) return additional;
        java.util.ArrayList<EmailAddress> result = new java.util.ArrayList<>();
        result.add(new EmailAddress(primary, true));
        result.addAll(additional);
        return result;
    }

    private void saveEmailChange(User user, String action) {
        userRepository.save(user);
        auditService.log("User", user.getId(), action, user.getUsername(), null, null);
    }

    @Transactional(readOnly = true)
    public AttachmentService.AttachmentDownload photo(UUID userId) {
        return attachmentService.download(currentPhoto(userId).getId());
    }

    @Transactional
    public void updatePhoto(UUID userId, MultipartFile file) {
        validatePhoto(file);
        List<AttachmentDto> previous = photos(userId);
        attachmentService.uploadProfilePhoto(userId, file);
        previous.forEach(photo -> attachmentService.softDelete(photo.getId()));
    }

    @Transactional
    public void deletePhoto(UUID userId) {
        photos(userId).forEach(photo -> attachmentService.softDelete(photo.getId()));
    }

    private AttachmentDto currentPhoto(UUID userId) {
        return photos(userId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Profile photo not found"));
    }

    private List<AttachmentDto> photos(UUID userId) {
        return attachmentService.list(PHOTO_ENTITY, userId).stream()
            .filter(file -> PHOTO_TYPE.equals(file.getAttachmentType()))
            .toList();
    }

    private void validatePhoto(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_PHOTO_BYTES) {
            throw new BadRequestException("Choose a photo smaller than 5 MB");
        }
        String type = file.getContentType();
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(type)) {
            throw new BadRequestException("Profile photo must be JPEG, PNG, or WebP");
        }
        try {
            byte[] bytes = file.getBytes();
            boolean valid = switch (type) {
                case "image/jpeg" -> starts(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                case "image/png" -> starts(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
                case "image/webp" -> starts(bytes, "RIFF".getBytes())
                    && bytes.length >= 12 && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), "WEBP".getBytes());
                default -> false;
            };
            if (!valid) {
                throw new BadRequestException("Profile photo content does not match its file type");
            }
        } catch (IOException ex) {
            throw new BadRequestException("Profile photo could not be read");
        }
    }

    private boolean starts(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }
}
