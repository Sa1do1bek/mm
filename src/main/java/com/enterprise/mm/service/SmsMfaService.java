package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.IntegrationEventCreateRequest;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.payload.MfaDtos.SmsDeliveryCallbackRequest;
import com.enterprise.mm.payload.MfaDtos.SmsDeliveryCallbackResponse;
import com.enterprise.mm.payload.NotificationCreateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.config.SecurityIdentityProperties;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SmsMfaService {
    private final SecureRandom random = new SecureRandom();
    private final SecurityIdentityProperties properties;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final IntegrationEventService integrationEventService;
    private final SmsGatewayClient smsGatewayClient;
    private final ObjectMapper objectMapper;
    @Transactional
    public void requestChallenge(User user, String purpose) {
        if (!smsGatewayClient.mfaEnabled()) {
            throw new BusinessException("SMS MFA is not enabled");
        }
        if (user.getMfaPhoneNumber() == null || user.getMfaPhoneNumber().isBlank()) {
            throw new BusinessException("MFA phone number is not configured");
        }
        int ttlSeconds = smsGatewayClient.ttlSeconds();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        user.setMfaSmsCodeHash(hash(user, code));
        user.setMfaSmsExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        userRepository.save(user);
        Map<String, Object> payload = new HashMap<>();
        payload.put("purpose", purpose);
        payload.put("username", user.getUsername());
        payload.put("phoneNumber", user.getMfaPhoneNumber());
        payload.put("maskedPhoneNumber", mask(user.getMfaPhoneNumber()));
        payload.put("ttlSeconds", ttlSeconds);
        if (properties.getSmsMfa().isIncludeCodeInOutbox()) {
            payload.put("code", code);
        }
        integrationEventService.emit("MFA_SMS_REQUESTED", "SMS", user.getId(), payload);
        smsGatewayClient.send(user, code, purpose);
        NotificationCreateRequest notification = new NotificationCreateRequest();
        notification.setCode("MFA-SMS-" + user.getId().toString().substring(0, 8));
        notification.setName("MFA SMS requested");
        notification.setStatus("ACTIVE");
        notification.setRecipientUsername(user.getUsername());
        notification.setTitleKey("notification.mfa.sms.title");
        notification.setMessageKey("notification.mfa.sms.message");
        notification.setTemplateArgs(Map.of("phone", mask(user.getMfaPhoneNumber())));
        notificationService.create(notification);
    }
    @Transactional
    public void retryChallenge(User user, String purpose) {
        requestChallenge(user, purpose == null || purpose.isBlank() ? "SMS_RETRY" : purpose);
    }
    @Transactional
    public SmsDeliveryCallbackResponse acceptDeliveryCallback(SmsDeliveryCallbackRequest request, String headerIdempotencyKey) {
        if (request == null) {
            throw new BusinessException("SMS delivery callback payload is required");
        }
        String status = normalize(request.status() == null || request.status().isBlank() ? request.providerStatus() : request.status());
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", request.messageId());
        payload.put("username", request.username());
        payload.put("phoneNumber", request.phoneNumber());
        payload.put("status", status);
        payload.put("providerStatus", request.providerStatus());
        payload.put("deliveredAt", request.deliveredAt());
        payload.put("payload", request.payload());
        IntegrationEventCreateRequest event = new IntegrationEventCreateRequest();
        event.setCode("SMS-" + status + "-" + Integer.toHexString(payload.hashCode()).toUpperCase(Locale.ROOT));
        event.setName("SMS delivery " + status);
        event.setEventType("SMS.DELIVERY_" + status);
        event.setAggregateType("SMS");
        event.setPayload(writePayload(payload));
        IntegrationEventResponse saved = integrationEventService.createIdempotent(event, firstNonBlank(headerIdempotencyKey, request.idempotencyKey()));
        return new SmsDeliveryCallbackResponse(saved.getId(), saved.getStatus(), "SMS delivery callback accepted");
    }
    public boolean verify(User user, String code) {
        if (code == null || !code.matches("\\d{6}") || user.getMfaSmsCodeHash() == null || user.getMfaSmsExpiresAt() == null) {
            return false;
        }
        if (LocalDateTime.now().isAfter(user.getMfaSmsExpiresAt())) {
            return false;
        }
        return user.getMfaSmsCodeHash().equals(hash(user, code));
    }
    @Transactional
    public void clearChallenge(User user) {
        user.setMfaSmsCodeHash(null);
        user.setMfaSmsExpiresAt(null);
        userRepository.save(user);
    }
    private String hash(User user, String code) {
        try {
            String payload = properties.getSmsMfa().getCodePepper() + ":" + user.getId() + ":" + code;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("Cannot hash SMS MFA code");
        }
    }
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
    }
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize SMS delivery payload");
        }
    }
    private String mask(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "+" + "*".repeat(Math.max(0, digits.length() - 4)) + digits.substring(digits.length() - 4);
    }
}

