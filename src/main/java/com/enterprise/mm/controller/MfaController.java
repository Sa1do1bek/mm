package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.MfaDtos.MfaSetupResponse;
import com.enterprise.mm.payload.MfaDtos.MfaStatusResponse;
import com.enterprise.mm.payload.MfaDtos.MfaVerifyRequest;
import com.enterprise.mm.payload.MfaDtos.SmsDeliveryCallbackRequest;
import com.enterprise.mm.payload.MfaDtos.SmsDeliveryCallbackResponse;
import com.enterprise.mm.payload.MfaDtos.SmsMfaRequest;
import com.enterprise.mm.payload.MfaDtos.SmsRetryRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.SmsMfaService;
import com.enterprise.mm.service.TotpService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.UserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mfa")
@RequiredArgsConstructor
public class MfaController {
    private final UserRepository userRepository;
    private final TotpService totpService;
    private final SmsMfaService smsMfaService;

    @GetMapping("/status")
    public ApiResponse<MfaStatusResponse> status(Authentication authentication) {
        return ApiResponse.ok(status(currentUser(authentication)));
    }

    @GetMapping("/setup")
    public ApiResponse<MfaSetupResponse> setup(Authentication authentication) {
        User user = currentUser(authentication);
        if (user.getMfaSecret() == null || user.getMfaSecret().isBlank()) {
            user.setMfaSecret(totpService.generateSecret());
            userRepository.save(user);
        }
        String label = URLEncoder.encode(TenantContext.currentTenant() + ":" + user.getUsername(), StandardCharsets.UTF_8);
        String issuer = URLEncoder.encode("Enterprise MM", StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + user.getMfaSecret() + "&issuer=" + issuer;
        return ApiResponse.ok(new MfaSetupResponse(user.getMfaSecret(), uri));
    }

    @PostMapping("/enable")
    public ApiResponse<Void> enable(Authentication authentication, @RequestBody MfaVerifyRequest request) {
        User user = currentUser(authentication);
        if (user.getMfaSecret() == null || user.getMfaSecret().isBlank()) {
            throw new BusinessException("MFA setup is required");
        }
        if (!totpService.verify(user.getMfaSecret(), request == null ? null : request.code())) {
            throw new BusinessException("Invalid MFA code");
        }
        user.setMfaEnabled(true);
        user.setMfaMethod("TOTP");
        userRepository.save(user);
        return ApiResponse.message("mfa-enabled");
    }

    @PostMapping("/sms/enable")
    public ApiResponse<MfaStatusResponse> enableSms(Authentication authentication, @RequestBody SmsMfaRequest request) {
        User user = currentUser(authentication);
        if (request == null || request.phoneNumber() == null || request.phoneNumber().isBlank()) {
            throw new BusinessException("Phone number is required");
        }
        user.setMfaPhoneNumber(request.phoneNumber());
        if (request.code() == null || request.code().isBlank()) {
            smsMfaService.requestChallenge(user, "ENABLE_SMS_MFA");
            return ApiResponse.ok(status(user));
        }
        if (!smsMfaService.verify(user, request.code())) {
            throw new BusinessException("Invalid SMS MFA code");
        }
        user.setMfaEnabled(true);
        user.setMfaMethod("SMS");
        smsMfaService.clearChallenge(user);
        return ApiResponse.ok(status(user));
    }

    @PostMapping("/sms/retry")
    public ApiResponse<MfaStatusResponse> retrySms(Authentication authentication, @RequestBody(required = false) SmsRetryRequest request) {
        User user = currentUser(authentication);
        smsMfaService.retryChallenge(user, request == null ? null : request.purpose());
        return ApiResponse.ok(status(user));
    }

    @PostMapping("/sms/delivery-callback")
    public ApiResponse<SmsDeliveryCallbackResponse> smsDeliveryCallback(
        @RequestBody(required = false) SmsDeliveryCallbackRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(smsMfaService.acceptDeliveryCallback(request, idempotencyKey));
    }

    @DeleteMapping
    public ApiResponse<Void> disable(Authentication authentication, @RequestBody(required = false) MfaVerifyRequest request) {
        User user = currentUser(authentication);
        String method = user.getMfaMethod() == null ? "TOTP" : user.getMfaMethod();
        if (user.isMfaEnabled() && "SMS".equalsIgnoreCase(method) && !smsMfaService.verify(user, request == null ? null : request.code())) {
            throw new BusinessException("Invalid SMS MFA code");
        }
        if (user.isMfaEnabled() && !"SMS".equalsIgnoreCase(method) && !totpService.verify(user.getMfaSecret(), request == null ? null : request.code())) {
            throw new BusinessException("Invalid MFA code");
        }
        user.setMfaEnabled(false);
        user.setMfaMethod("TOTP");
        user.setMfaSecret(null);
        user.setMfaPhoneNumber(null);
        user.setMfaSmsCodeHash(null);
        user.setMfaSmsExpiresAt(null);
        userRepository.save(user);
        return ApiResponse.message("mfa-disabled");
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null) {
            throw new BusinessException("Authentication is required");
        }
        return userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            authentication.getName()
        ).orElseGet(() -> userRepository.findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(
                TenantContext.currentTenant(),
                authentication.getName()
            ).filter(this::isSuperAdmin)
            .orElseThrow(() -> new BusinessException("User not found")));
    }

    private boolean isSuperAdmin(User user) {
        return user.getCompanyId() == null && user.getRoles().contains(com.enterprise.mm.enums.RoleName.ADMIN);
    }

    private MfaStatusResponse status(User user) {
        return new MfaStatusResponse(
            user.isMfaEnabled(),
            user.getMfaMethod() == null ? "TOTP" : user.getMfaMethod(),
            mask(user.getMfaPhoneNumber()),
            user.getMfaSecret() != null && !user.getMfaSecret().isBlank(),
            user.getMfaPhoneNumber() != null && !user.getMfaPhoneNumber().isBlank()
        );
    }

    private String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "+" + "*".repeat(Math.max(0, digits.length() - 4)) + digits.substring(digits.length() - 4);
    }
}
