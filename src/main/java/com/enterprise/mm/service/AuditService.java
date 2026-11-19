package com.enterprise.mm.service;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository auditLogRepository;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, UUID entityId, String action, String details) {
        auditLogRepository.save(buildLog(entityType, entityId, action, details, null, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, UUID entityId, String action, String details, Object oldSnapshot, Object newSnapshot) {
        auditLogRepository.save(buildLog(entityType, entityId, action, details, oldSnapshot, newSnapshot));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void logInCurrentTransaction(String entityType, UUID entityId, String action, String details) {
        auditLogRepository.save(buildLog(entityType, entityId, action, details, null, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void logInCurrentTransaction(String entityType, UUID entityId, String action, String details, Object oldSnapshot, Object newSnapshot) {
        auditLogRepository.save(buildLog(entityType, entityId, action, details, oldSnapshot, newSnapshot));
    }

    public String snapshot(Object value) {
        return snapshotSerializer.toJson(value);
    }

    private AuditLog buildLog(String entityType, UUID entityId, String action, String details, Object oldSnapshot, Object newSnapshot) {
        String userFullName = fullName();
        UUID companyId = CompanyContext.currentCompany();
        AuditLog log = new AuditLog();
        log.setCode(action + "-" + UUID.randomUUID().toString().substring(0, 8));
        log.setName(entityType + " " + action);
        log.setStatus("RECORDED");
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setAction(action);
        log.setUsername(username());
        log.setFullName(userFullName);
        log.setCompanyId(companyId);
        log.setDetails(structuredDetails(action, entityId, details, userFullName, companyId));
        log.setOldSnapshot(snapshotSerializer.toJson(oldSnapshot));
        log.setNewSnapshot(snapshotSerializer.toJson(newSnapshot));
        log.setDiff(snapshotSerializer.diff(oldSnapshot, newSnapshot));
        log.setOccurredAt(LocalDateTime.now());
        log.setIpAddress(ipAddress());
        return log;
    }

    private String structuredDetails(String action, UUID entityId, String details, String username, UUID companyId) {
        String entityCode = details == null || details.isBlank() ? null : details;
        return "{"
            + "\"action\":\"" + escape(action) + "\","
            + "\"entityId\":" + jsonValue(entityId == null ? null : entityId.toString()) + ","
            + "\"code\":" + jsonValue(entityCode) + ","
            + "\"user\":\"" + escape(username) + "\","
            + "\"companyId\":" + jsonValue(companyId == null ? null : companyId.toString()) + ","
            + "\"details\":" + jsonValue(details)
            + "}";
    }

    private String jsonValue(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }


    private String fullName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "System";
        }
        return authenticatedUser(auth)
            .map(User::getFullName)
            .filter(name -> !name.isBlank())
            .orElse(auth.getName());
    }

    private String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null || auth.getName().isBlank()
            ? "system"
            : auth.getName();
    }

    private java.util.Optional<User> authenticatedUser(Authentication auth) {
        if (auth.getCredentials() instanceof AuthenticatedTokenState tokenState) {
            return userRepository.findByIdAndIsDeletedFalse(tokenState.userId());
        }
        return userRepository.findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            auth.getName()
        );
    }
    private String ipAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        }
        return "system";
    }
}
