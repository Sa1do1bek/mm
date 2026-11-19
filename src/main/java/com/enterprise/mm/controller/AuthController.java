package com.enterprise.mm.controller;

import com.enterprise.mm.service.CompanyService;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AuthDtos.LoginRequest;
import com.enterprise.mm.payload.AuthDtos.LoginResponse;
import com.enterprise.mm.payload.AuthDtos.MeResponse;
import com.enterprise.mm.payload.ChangePasswordRequest;
import com.enterprise.mm.payload.CompanyResponse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.PasswordSecurityException;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.PasswordChangeService;
import com.enterprise.mm.service.SmsMfaService;
import com.enterprise.mm.service.TotpService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.enterprise.mm.security.JwtService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.ProfileSelfService;
import com.enterprise.mm.service.AttachmentService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.entity.Warehouse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final TotpService totpService;
    private final SmsMfaService smsMfaService;
    private final AuditService auditService;
    private final CompanyService companyService;
    private final PasswordChangeService passwordChangeService;
    private final RoleDataScopeService roleDataScopeService;
    private final WarehouseRepository warehouseRepository;
    private final ProfileSelfService profileSelfService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
        @Valid @RequestBody LoginRequest request,
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
        @RequestHeader(value = "X-Company-Id", required = false) String companyHeader
    ) {
        User loginUser = resolveLoginUser(request, tenantHeader, companyHeader);
        TenantContext.setTenant(loginUser.getTenantId());
        CompanyContext.setCompany(loginUser.getCompanyId());
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        Set<String> roles = authentication.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        User user = resolveAuthenticatedUser(authentication.getName(), loginUser);
        TenantContext.setTenant(user.getTenantId());
        CompanyContext.setCompany(user.getCompanyId());
        AuthCompanyScope companyScope = authCompanyScope(user);
        String mfaMethod = user.getMfaMethod() == null || user.getMfaMethod().isBlank() ? "TOTP" : user.getMfaMethod();
        if (user.isMfaEnabled() && "SMS".equalsIgnoreCase(mfaMethod) && (request.mfaCode() == null || request.mfaCode().isBlank())) {
            smsMfaService.requestChallenge(user, "LOGIN");
            return ApiResponse.ok(new LoginResponse(null, authentication.getName(), TenantContext.currentTenant(), companyScope.companyId(), companyScope.companyName(), roles, true, "SMS"));
        }
        if (user.isMfaEnabled() && "SMS".equalsIgnoreCase(mfaMethod) && !smsMfaService.verify(user, request.mfaCode())) {
            throw new BadCredentialsException("Invalid SMS MFA code");
        }
        if (user.isMfaEnabled() && "SMS".equalsIgnoreCase(mfaMethod)) {
            smsMfaService.clearChallenge(user);
        }
        if (user.isMfaEnabled() && !"SMS".equalsIgnoreCase(mfaMethod) && (request.mfaCode() == null || request.mfaCode().isBlank())) {
            return ApiResponse.ok(new LoginResponse(null, authentication.getName(), TenantContext.currentTenant(), companyScope.companyId(), companyScope.companyName(), roles, true, "TOTP"));
        }
        if (user.isMfaEnabled() && !"SMS".equalsIgnoreCase(mfaMethod) && !totpService.verify(user.getMfaSecret(), request.mfaCode())) {
            throw new BadCredentialsException("Invalid MFA code");
        }
        return ApiResponse.ok(new LoginResponse(
            jwtService.generate(user, authentication.getAuthorities(), TenantContext.currentTenant(), companyScope.companyId()),
            authentication.getName(),
            TenantContext.currentTenant(),
            companyScope.companyId(),
            companyScope.companyName(),
            roles,
            false,
            null
        ));
    }

    private User resolveLoginUser(LoginRequest request, String tenantHeader, String companyHeader) {
        String normalizedTenantHeader = tenantHeader == null ? null : tenantHeader.trim();
        String normalizedCompanyHeader = companyHeader == null ? null : companyHeader.trim();
        String tenantId = normalizedTenantHeader == null || normalizedTenantHeader.isBlank()
            ? null
            : TenantContext.normalize(normalizedTenantHeader);
        User user;
        if (tenantId != null && normalizedCompanyHeader != null && !normalizedCompanyHeader.isBlank()) {
            UUID companyId = CompanyContext.normalize(normalizedCompanyHeader);
            user = userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(tenantId, companyId, request.username())
                .or(() -> userRepository.findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(tenantId, request.username()))
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        } else if (tenantId != null) {
            user = userRepository.findAllByTenantIdAndUsernameAndIsDeletedFalse(tenantId, request.username()).stream()
                .findFirst()
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        } else {
            user = userRepository.findByUsernameAndIsDeletedFalse(request.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        }

        if (normalizedTenantHeader != null && !normalizedTenantHeader.isBlank()
            && !TenantContext.normalize(normalizedTenantHeader).equals(user.getTenantId())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("User is disabled");
        }
        return user;
    }

    private User resolveAuthenticatedUser(String username, User loginUser) {
        return userRepository.findByIdAndIsDeletedFalse(loginUser.getId())
            .filter(user -> user.getUsername().equals(username))
            .or(() -> userRepository.findByUsernameAndIsDeletedFalse(username))
            .orElseThrow(() -> new BadCredentialsException("User was authenticated but not found"));
    }

    private boolean isSuperAdmin(User user) {
        return user.getCompanyId() == null && user.getRoles().contains(com.enterprise.mm.enums.RoleName.ADMIN);
    }

    private AuthCompanyScope authCompanyScope(User user) {
        if (isSuperAdmin(user)) {
            return new AuthCompanyScope(null, null);
        }
        UUID companyId = CompanyContext.currentCompany();
        String companyName = companyRepository.findNameByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)
            .orElse("Default company");
        return new AuthCompanyScope(companyId, companyName);
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(Authentication authentication) {
        User user = currentUser(authentication);
        requireCurrentTokenVersion(authentication, user);
        Set<String> roles = authentication.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        AuthCompanyScope companyScope = authCompanyScope(user);
        auditService.log("User", user.getId(), "REFRESH_TOKEN", user.getUsername(), null, null);
        return ApiResponse.ok(new LoginResponse(
            jwtService.generate(user, authentication.getAuthorities(), TenantContext.currentTenant(), companyScope.companyId()),
            authentication.getName(),
            TenantContext.currentTenant(),
            companyScope.companyId(),
            companyScope.companyName(),
            roles,
            false,
            null
        ));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
        Authentication authentication,
        @Valid @RequestBody ChangePasswordRequest request
    ) {
        passwordChangeService.changePassword(authentication, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication) {
        User user = currentUser(authentication);
        auditService.log("User", user.getId(), "LOGOUT", user.getUsername(), null, null);
        return ApiResponse.message("logout-accepted");
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        User user = currentUser(authentication);
        Set<String> roles = authentication.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        AuthCompanyScope companyScope = authCompanyScope(user);
        String mfaMethod = user.getMfaMethod() == null || user.getMfaMethod().isBlank() ? "TOTP" : user.getMfaMethod();
        UUID assignedWarehouseId = null;
        String assignedWarehouseName = null;
        boolean isWarehouseRestricted = false;
        Set<UUID> accessibleWarehouseIds = Set.of();
        if (roleDataScopeService != null) {
            RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
            assignedWarehouseId = roles.contains("ROLE_DEPARTMENT_HEAD")
                ? scope.warehouseId() : employeeWarehouseIdOf(user);
            accessibleWarehouseIds = scope.warehouseIds();
            if (assignedWarehouseId != null && !accessibleWarehouseIds.contains(assignedWarehouseId)) {
                assignedWarehouseId = null;
            }
            isWarehouseRestricted = scope.warehouseScoped();
            if (assignedWarehouseId != null && warehouseRepository != null) {
                assignedWarehouseName = warehouseRepository.findByIdAndIsDeletedFalse(assignedWarehouseId)
                    .map(Warehouse::getName)
                    .orElse(null);
            }
        }
        return ApiResponse.ok(new MeResponse(
            user.getUsername(),
            user.getFullName(),
            user.getEmail(),
            TenantContext.currentTenant(),
            companyScope.companyId(),
            companyScope.companyName(),
            departmentIdOf(user),
            roles,
            user.isEnabled(),
            user.isMfaEnabled(),
            mfaMethod,
            assignedWarehouseId,
            assignedWarehouseName,
            isWarehouseRestricted,
            user.getId(),
            accessibleWarehouseIds
        ));
    }

    public record OwnEmailRequest(@NotBlank String email) {
    }

    public record OwnEmailResponse(String email) {
    }

    public record OwnEmailChangeRequest(@NotBlank String currentEmail, @NotBlank String email) {
    }

    @GetMapping("/me/emails")
    public ApiResponse<List<ProfileSelfService.EmailAddress>> ownEmails(Authentication authentication) {
        return ApiResponse.ok(profileSelfService.emails(currentUser(authentication)));
    }

    @PostMapping("/me/emails")
    public ApiResponse<List<ProfileSelfService.EmailAddress>> addOwnEmail(
        Authentication authentication,
        @Valid @RequestBody OwnEmailRequest request
    ) {
        return ApiResponse.ok(profileSelfService.addEmail(currentUser(authentication), request.email()));
    }

    @PutMapping("/me/emails")
    public ApiResponse<List<ProfileSelfService.EmailAddress>> changeOwnEmail(
        Authentication authentication,
        @Valid @RequestBody OwnEmailChangeRequest request
    ) {
        return ApiResponse.ok(profileSelfService.changeEmail(currentUser(authentication), request.currentEmail(), request.email()));
    }

    @DeleteMapping("/me/emails")
    public ApiResponse<List<ProfileSelfService.EmailAddress>> removeOwnEmail(
        Authentication authentication,
        @RequestParam String email
    ) {
        return ApiResponse.ok(profileSelfService.removeEmail(currentUser(authentication), email));
    }

    @PutMapping("/me/emails/primary")
    public ApiResponse<List<ProfileSelfService.EmailAddress>> makeOwnPrimaryEmail(
        Authentication authentication,
        @Valid @RequestBody OwnEmailRequest request
    ) {
        return ApiResponse.ok(profileSelfService.makePrimary(currentUser(authentication), request.email()));
    }

    @PutMapping("/me/email")
    public ApiResponse<OwnEmailResponse> updateOwnEmail(
        Authentication authentication,
        @Valid @RequestBody OwnEmailRequest request
    ) {
        User user = currentUser(authentication);
        return ApiResponse.ok(new OwnEmailResponse(profileSelfService.updateEmail(user, request.email())));
    }

    @DeleteMapping("/me/email")
    public ResponseEntity<Void> deleteOwnEmail(Authentication authentication) {
        profileSelfService.deleteEmail(currentUser(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/photo")
    public ResponseEntity<InputStreamResource> ownPhoto(Authentication authentication) {
        AttachmentService.AttachmentDownload photo = profileSelfService.photo(currentUser(authentication).getId());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(photo.metadata().getContentType()))
            .contentLength(photo.metadata().getSizeBytes())
            .body(photo.resource());
    }

    @PutMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateOwnPhoto(Authentication authentication, @RequestParam("file") MultipartFile file) {
        profileSelfService.updatePhoto(currentUser(authentication).getId(), file);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/photo")
    public ResponseEntity<Void> deleteOwnPhoto(Authentication authentication) {
        profileSelfService.deletePhoto(currentUser(authentication).getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/companies")
    public ApiResponse<List<CompanyResponse>> companies() {
        return ApiResponse.ok(companyService.getCompanies(TenantContext.currentTenant()));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null) {
            throw PasswordSecurityException.authenticationRequired();
        }
        if (authentication.getCredentials() instanceof AuthenticatedTokenState tokenState) {
            return userRepository.findByIdAndIsDeletedFalse(tokenState.userId())
                .filter(user -> user.isEnabled()
                    && user.getUsername().equals(authentication.getName())
                    && user.getTenantId().equals(TenantContext.currentTenant()))
                .orElseThrow(PasswordSecurityException::authenticationRequired);
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

    private UUID departmentIdOf(User user) {
        if (user == null) {
            return null;
        }
        if (user.getDepartment() != null) {
            return user.getDepartment().getId();
        }
        return user.getEmployee() == null || user.getEmployee().getDepartment() == null
            ? null
            : user.getEmployee().getDepartment().getId();
    }

    private UUID employeeWarehouseIdOf(User user) {
        return user == null || user.getEmployee() == null || user.getEmployee().getWarehouse() == null
            ? null
            : user.getEmployee().getWarehouse().getId();
    }

    private void requireCurrentTokenVersion(Authentication authentication, User user) {
        if (!(authentication.getCredentials() instanceof AuthenticatedTokenState tokenState)
            || !tokenState.userId().equals(user.getId())
            || tokenState.tokenVersion() != user.getTokenVersion()) {
            throw PasswordSecurityException.tokenRevoked();
        }
    }

    private record AuthCompanyScope(UUID companyId, String companyName) {
    }
}
