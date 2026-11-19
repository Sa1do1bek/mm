package com.enterprise.mm.controller;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.FcmTokenRegisterRequest;
import com.enterprise.mm.payload.FcmTokenResponse;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.FcmTokenService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device FCM token registration (toir-compatible).
 * Supports both {@code /api/notification-devices} and {@code /api/v1/notification-devices}.
 */
@RestController
@RequiredArgsConstructor
public class FcmTokenController {

    private final FcmTokenService fcmTokenService;
    private final UserRepository userRepository;

    @PostMapping({"/api/notification-devices/register", "/api/v1/notification-devices/register"})
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FcmTokenResponse> register(Authentication authentication,
                                                  @Valid @RequestBody FcmTokenRegisterRequest request) {
        return ApiResponse.created(fcmTokenService.register(currentUserId(authentication), request));
    }

    @GetMapping({"/api/notification-devices/me", "/api/v1/notification-devices/me"})
    public ApiResponse<List<FcmTokenResponse>> mine(Authentication authentication) {
        return ApiResponse.ok(fcmTokenService.findMine(currentUserId(authentication)));
    }

    @DeleteMapping({"/api/notification-devices/deactivate-all", "/api/v1/notification-devices/deactivate-all"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateAll(Authentication authentication) {
        fcmTokenService.deactivateAllUserTokens(currentUserId(authentication));
    }

    @DeleteMapping({"/api/notification-devices/{token:.+}", "/api/v1/notification-devices/{token:.+}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(Authentication authentication, @PathVariable String token) {
        fcmTokenService.unregister(currentUserId(authentication), token);
    }

    private UUID currentUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BadRequestException("Authenticated user is required");
        }
        String username = authentication.getName().trim();
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return userRepository.findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(
                        tenantId, companyId, username)
                .or(() -> userRepository.findByTenantIdAndUsernameIgnoreCaseAndIsDeletedFalse(tenantId, username))
                .or(() -> userRepository.findByUsernameIgnoreCaseAndIsDeletedFalse(username))
                .map(User::getId)
                .orElseThrow(() -> new NotFoundException("User not found: " + username));
    }
}
