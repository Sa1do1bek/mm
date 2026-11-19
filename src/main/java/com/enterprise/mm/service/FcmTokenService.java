package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FcmToken;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.FcmTokenRegisterRequest;
import com.enterprise.mm.payload.FcmTokenResponse;
import com.enterprise.mm.repository.FcmTokenRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class FcmTokenService {

    private final FcmTokenRepository tokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public FcmTokenResponse register(UUID userId, FcmTokenRegisterRequest request) {
        if (userId == null) {
            throw new BadRequestException("Authenticated user is required");
        }
        userRepository.findByIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        String normalizedToken = normalizeToken(request.token());
        String deviceId = normalizeOptional(request.deviceId());
        LocalDateTime now = LocalDateTime.now();

        FcmToken entity = tokenRepository.findByTokenAndIsDeletedFalse(normalizedToken)
                .orElseGet(FcmToken::new);
        entity.setUserId(userId);
        entity.setToken(normalizedToken);
        entity.setPlatform(request.platform());
        entity.setDeviceId(deviceId);
        entity.setLanguage(normalizeLanguage(request.language()));
        entity.setActive(true);
        entity.setLastSeenAt(now);

        // Set multi-tenant context for new entities
        if (entity.getTenantId() == null || entity.getTenantId().isBlank()) {
            entity.setTenantId(TenantContext.currentTenant());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }

        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName("FCM " + request.platform().name());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("FCM");
        }
        return FcmTokenResponse.from(tokenRepository.save(entity));
    }

    @Transactional
    public void unregister(UUID userId, String token) {
        if (userId == null) {
            throw new BadRequestException("Authenticated user is required");
        }
        String normalizedToken = normalizeToken(token);
        FcmToken entity = tokenRepository.findByUserIdAndTokenAndIsDeletedFalse(userId, normalizedToken)
                .orElseThrow(() -> new NotFoundException("Notification device token not found"));
        entity.setActive(false);
        entity.setLastSeenAt(LocalDateTime.now());
        tokenRepository.save(entity);
    }

    @Transactional
    public void deactivateAllUserTokens(UUID userId) {
        List<FcmToken> tokens = tokenRepository.findAllByUserIdAndIsDeletedFalseOrderByLastSeenAtDesc(userId);
        LocalDateTime now = LocalDateTime.now();
        tokens.forEach(token -> {
            token.setActive(false);
            token.setLastSeenAt(now);
        });
        tokenRepository.saveAll(tokens);
        log.info("Deactivated all FCM tokens for user {}", userId);
    }

    @Transactional(readOnly = true)
    public List<FcmTokenResponse> findMine(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return tokenRepository.findAllByUserIdAndIsDeletedFalseOrderByLastSeenAtDesc(userId).stream()
                .map(FcmTokenResponse::from)
                .toList();
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BadRequestException("FCM token is required");
        }
        return token.trim();
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeLanguage(String value) {
        if (!StringUtils.hasText(value)) {
            return "en";
        }
        String language = java.util.Locale.forLanguageTag(value.trim()).getLanguage().toLowerCase(java.util.Locale.ROOT);
        return java.util.Set.of("en", "ru", "uz").contains(language) ? language : "en";
    }
}
