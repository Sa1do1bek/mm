package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.FirebaseDiagnostics;
import com.enterprise.mm.config.FirebaseProperties;
import com.enterprise.mm.entity.FcmToken;
import com.enterprise.mm.entity.Notification;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.FcmTokenRepository;
import com.enterprise.mm.repository.UserRepository;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushNotification;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FirebasePushNotificationSender {

    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;
    private final FcmTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final FirebaseProperties firebaseProperties;
    private final NotificationLocalizationService localizationService;

    public void sendToUser(String recipientUsername, Notification notification) {
        if (notification == null) {
            return;
        }
        sendToUser(recipientUsername, notification.getTitle(), notification.getMessage(), notification.getId(), notification);
    }

    public void sendToUser(String recipientUsername, String title, String message, UUID notificationId) {
        sendToUser(recipientUsername, title, message, notificationId, null);
    }

    private void sendToUser(String recipientUsername, String title, String message, UUID notificationId, Notification notification) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (recipientUsername == null || recipientUsername.isBlank()) {
            log.warn("Firebase push skipped because recipientUsername is null or blank");
            return;
        }
        if (firebaseMessaging == null) {
            FirebaseDiagnostics.Status status = FirebaseDiagnostics.fromProperties(firebaseProperties);
            if (!status.enabled()) {
                log.info("Firebase push skipped for notification {} recipient {} because Firebase is disabled ({}=false)",
                        notificationId, recipientUsername, FirebaseDiagnostics.ENABLED_KEY);
            } else {
                log.warn("Firebase push skipped for notification {} recipient {} because FirebaseMessaging is not configured; credentialSource={}, credentialsAvailable={}, missingOrInvalidConfigKeys={}",
                        notificationId,
                        recipientUsername,
                        status.credentialSource(),
                        status.credentialsAvailable(),
                        status.missingOrInvalidConfigKeys());
            }
            return;
        }

        UUID userId = resolveUserId(recipientUsername);
        if (userId == null) {
            log.warn("Firebase push skipped for notification {} recipient {} because user not found",
                    notificationId, recipientUsername);
            return;
        }

        sendToUserId(userId, recipientUsername, title, message, notificationId, notification, firebaseMessaging);
    }

    public void sendToUserId(UUID userId, String recipientLabel, String title, String message, UUID notificationId) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (userId == null) {
            log.warn("Firebase push skipped because userId is null");
            return;
        }
        if (firebaseMessaging == null) {
            log.warn("Firebase push skipped for notification {} user {} because FirebaseMessaging is not configured",
                    notificationId, userId);
            return;
        }
        sendToUserId(userId, recipientLabel, title, message, notificationId, null, firebaseMessaging);
    }

    private void sendToUserId(UUID userId,
                              String recipientLabel,
                              String title,
                              String message,
                              UUID notificationId,
                              Notification notification,
                              FirebaseMessaging firebaseMessaging) {
        List<FcmToken> tokens = tokenRepository
                .findAllByUserIdAndActiveTrueAndIsDeletedFalseOrderByLastSeenAtDesc(userId);
        if (tokens.isEmpty()) {
            log.info("Firebase push skipped for notification {} recipient {} because no active FCM tokens were found",
                    notificationId, recipientLabel != null ? recipientLabel : userId);
            return;
        }

        log.info("Firebase push sending notification {} to recipient {} using {} active FCM token(s)",
                notificationId, recipientLabel != null ? recipientLabel : userId, tokens.size());
        tokens.forEach(token -> {
            NotificationLocalizationService.RenderedNotification rendered = renderForToken(notification, token, title, message);
            Map<String, String> data = payload(rendered.title(), rendered.message(), notificationId, notification);
            sendToToken(firebaseMessaging, token, rendered.title(), rendered.message(), notificationId, data);
        });
    }

    private UUID resolveUserId(String username) {
        return userRepository.resolveActiveUserByUsername(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                username
        ).map(User::getId).orElse(null);
    }

    private void sendToToken(FirebaseMessaging firebaseMessaging,
                             FcmToken token,
                             String title,
                             String message,
                             UUID notificationId,
                             Map<String, String> data) {
        try {
            Message firebaseMessage = buildMessage(token, title, message, data);
            String messageId = firebaseMessaging.send(firebaseMessage);
            log.info("Firebase push sent notification {} to recipient {} token id {} messageId={}",
                    notificationId, token.getUserId(), token.getId(), messageId);
        } catch (FirebaseMessagingException ex) {
            handleFirebaseFailure(token, notificationId, ex);
        } catch (RuntimeException ex) {
            log.warn("Unexpected Firebase push failure for notification {} recipient {} token id {}: {}",
                    notificationId, token.getUserId(), token.getId(), ex.getMessage());
        }
    }

    private Message buildMessage(FcmToken token, String title, String message, Map<String, String> data) {
        Message.Builder builder = Message.builder()
                .setToken(token.getToken())
                .putAllData(data);

        switch (token.getPlatform()) {
            case ANDROID -> builder.setAndroidConfig(
                    AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(
                                    AndroidNotification.builder()
                                            .setTitle(title)
                                            .setBody(message)
                                            .build()
                            )
                            .build()
            );
            case IOS -> builder.setApnsConfig(
                    ApnsConfig.builder()
                            .putHeader("apns-priority", "10")
                            .putHeader("apns-push-type", "alert")
                            .setAps(
                                    Aps.builder()
                                            .setAlert(
                                                    ApsAlert.builder()
                                                            .setTitle(title)
                                                            .setBody(message)
                                                            .build()
                                            )
                                            .setSound("default")
                                            .setContentAvailable(true)
                                            .build()
                            )
                            .build()
            );
            case WEB -> builder.setWebpushConfig(
                    WebpushConfig.builder()
                            .setNotification(
                                    WebpushNotification.builder()
                                            .setTitle(title)
                                            .setBody(message)
                                            .build()
                            )
                            .build()
            );
        }

        return builder.build();
    }

    private void handleFirebaseFailure(FcmToken token, UUID notificationId, FirebaseMessagingException ex) {
        MessagingErrorCode errorCode = ex.getMessagingErrorCode();
        if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
            token.setActive(false);
            tokenRepository.save(token);
            log.info("Firebase push failed permanently for notification {} recipient {} token id {}; deactivated token after FCM error {}: {}",
                    notificationId, token.getUserId(), token.getId(), errorCode, ex.getMessage());
            return;
        }
        log.warn("Temporary Firebase push failure for notification {} recipient {} token id {} with FCM error {}: {}",
                notificationId, token.getUserId(), token.getId(), errorCode, ex.getMessage());
    }

    private Map<String, String> payload(String title, String message, UUID notificationId) {
        return payload(title, message, notificationId, null);
    }

    private Map<String, String> payload(String title, String message, UUID notificationId, Notification notification) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("title", value(title));
        data.put("body", value(message));
        data.put("notificationId", notificationId != null ? notificationId.toString() : "");
        data.put("createdAt", Instant.now().toString());
        if (notification != null) {
            if (notification.getEntityType() != null && !notification.getEntityType().isBlank()) {
                data.put("entityType", notification.getEntityType());
            }
            if (notification.getEntityId() != null) {
                data.put("entityId", notification.getEntityId().toString());
            }
        }
        return data;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private NotificationLocalizationService.RenderedNotification renderForToken(
            Notification notification,
            FcmToken token,
            String fallbackTitle,
            String fallbackMessage
    ) {
        if (notification == null) {
            return new NotificationLocalizationService.RenderedNotification(
                    value(fallbackTitle),
                    value(fallbackMessage),
                    "en",
                    Map.of()
            );
        }
        Locale locale = Locale.forLanguageTag(token.getLanguage() == null ? "en" : token.getLanguage());
        return localizationService.render(notification, locale);
    }
}
