package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.AuthDtos.LoginResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoAssertionRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.SsoSettingsService.RuntimeSsoSettings;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.JwtService;
import com.enterprise.mm.security.WarehouseRoleAuthorities;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SsoAssertionService {
    private final ConcurrentHashMap<String, Long> usedNonces = new ConcurrentHashMap<>();
    private final SsoSettingsService settingsService;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public LoginResponse authenticate(SsoAssertionRequest request) {
        RuntimeSsoSettings sso = validate(request);
        return authenticateClaims(
            request.tenantId(),
            request.companyId(),
            request.username(),
            request.email(),
            request.fullName(),
            request.roles(),
            sso.providerType()
        );
    }
    @Transactional
    public LoginResponse authenticateClaims(
        String rawTenantId,
        UUID rawCompanyId,
        String username,
        String email,
        String fullName,
        List<String> requestedRoleNames,
        String method
    ) {
        if (username == null || username.isBlank()) {
            throw new BusinessException("SSO username is required");
        }
        String tenantId = TenantContext.normalize(rawTenantId);
        UUID companyId = rawCompanyId == null ? CompanyContext.DEFAULT_COMPANY_ID : rawCompanyId;
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        Set<RoleName> roles = resolveRoles(tenantId, requestedRoleNames);
        User user = userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(tenantId, companyId, username)
            .orElseGet(User::new);
        if (user.getId() == null) {
            user.setCode("SSO-" + UUID.randomUUID().toString().substring(0, 8));
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setEnabled(true);
            user.setStatus("ACTIVE");
        }
        user.setName(fullName == null || fullName.isBlank() ? username : fullName);
        user.setFullName(user.getName());
        if (email != null && !email.isBlank()) {
            if (user.getEmail() != null && !user.getEmail().isBlank()
                && !user.getEmail().equalsIgnoreCase(email)) {
                user.getAdditionalEmails().add(user.getEmail().trim().toLowerCase(Locale.ROOT));
            }
            user.setEmail(email.trim().toLowerCase(Locale.ROOT));
        }
        user.reconcilePrimaryEmail();
        user.setRoles(new HashSet<>(roles));
        User saved = userRepository.save(user);
        var authorities = WarehouseRoleAuthorities.expand(roles.stream()
            .map(role -> "ROLE_" + role.name())
            .toList());
        Set<String> grantedRoleNames = authorities.stream().map(SimpleGrantedAuthority::getAuthority).collect(Collectors.toSet());
        String companyName = companyRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, companyId)
            .map(company -> company.getName())
            .orElse("Default company");
        return new LoginResponse(
            jwtService.generate(saved, authorities, tenantId, companyId),
            saved.getUsername(),
            tenantId,
            companyId,
            companyName,
            grantedRoleNames,
            false,
            method == null || method.isBlank() ? "SSO" : method
        );
    }
    public String canonicalPayload(SsoAssertionRequest request) {
        String roles = (request.roles() == null ? List.<String>of() : request.roles()).stream()
            .map(role -> role.toUpperCase(Locale.ROOT))
            .sorted()
            .collect(Collectors.joining(","));
        return String.join("|",
            nullToEmpty(request.tenantId()),
            request.companyId() == null ? "" : request.companyId().toString(),
            nullToEmpty(request.username()),
            nullToEmpty(request.email()),
            nullToEmpty(request.externalSubject()),
            roles,
            Long.toString(request.issuedAtEpochSeconds()),
            nullToEmpty(request.nonce())
        );
    }
    private RuntimeSsoSettings validate(SsoAssertionRequest request) {
        String tenantId = request == null ? TenantContext.DEFAULT_TENANT : TenantContext.normalize(request.tenantId());
        UUID companyId = request == null || request.companyId() == null ? CompanyContext.DEFAULT_COMPANY_ID : request.companyId();
        RuntimeSsoSettings sso = settingsService.runtime(tenantId, companyId);
        if (!sso.enabled()) {
            throw new BusinessException("SSO is not enabled");
        }
        if (sso.assertionSecret() == null || sso.assertionSecret().isBlank()) {
            throw new BusinessException("SSO assertion secret is not configured");
        }
        if (request == null || request.username() == null || request.username().isBlank() || request.nonce() == null || request.nonce().isBlank()) {
            throw new BusinessException("Invalid SSO assertion");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - request.issuedAtEpochSeconds()) > sso.timestampSkewSeconds()) {
            throw new BusinessException("SSO assertion timestamp is outside allowed skew");
        }
        if (!MessageDigest.isEqual(sign(canonicalPayload(request), sso).getBytes(StandardCharsets.UTF_8), nullToEmpty(request.signature()).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("Invalid SSO assertion signature");
        }
        purgeOldNonces(now, Math.max(60, sso.timestampSkewSeconds()));
        Long existing = usedNonces.putIfAbsent(request.nonce(), request.issuedAtEpochSeconds());
        if (existing != null) {
            throw new BusinessException("SSO assertion nonce has already been used");
        }
        return sso;
    }
    private Set<RoleName> resolveRoles(String tenantId, List<String> requestedRoles) {
        Set<String> allowed = settingsService.runtime(tenantId, CompanyContext.currentCompany()).allowedRoles().stream().map(role -> role.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> normalized = (requestedRoles == null || requestedRoles.isEmpty() ? List.of(RoleName.VIEWER.name()) : requestedRoles).stream()
            .map(role -> role.toUpperCase(Locale.ROOT).replace("ROLE_", ""))
            .filter(allowed::contains)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
        if (normalized.isEmpty()) {
            normalized = List.of(RoleName.VIEWER.name());
        }
        return normalized.stream()
            .map(roleName -> {
                try {
                    return RoleName.valueOf(roleName);
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException("Role not found for SSO mapping: " + roleName);
                }
            })
            .collect(Collectors.toSet());
    }
    private String sign(String payload, RuntimeSsoSettings sso) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sso.assertionSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("Cannot verify SSO assertion");
        }
    }
    private void purgeOldNonces(long now, long maxAge) {
        usedNonces.entrySet().removeIf(entry -> now - entry.getValue() > maxAge);
    }
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
