package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.NotificationResponse;
import com.enterprise.mm.payload.NotificationUpdateRequest;
import com.enterprise.mm.payload.NotificationCreateRequest;
import com.enterprise.mm.payload.NotificationSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.NotificationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Notification;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.NotificationRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private final NotificationRepository repository;
    private final NotificationMapper mapper;
    private final String entityName = "Notification";
    private final AuditService auditService;
    private final FirebasePushNotificationSender firebasePushNotificationSender;
    private final UserRepository userRepository;
    private final NotificationLocalizationService localizationService;
    private final NotificationEntityNavigationResolver navigationResolver;
    @Transactional(readOnly = true)
    public Page<NotificationResponse> search(NotificationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean admin = isAdmin();
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.status(),
            !filter.hasStatus(),
            filter.fromDateTime(),
            filter.toDateTime(),
            admin,
            admin ? List.of() : currentPrincipals(),
            pageable
        ).map(this::toLocalizedResponse);
    }
    @Transactional
    public int readAllCurrentUser() {
        var unread = isAdmin()
            ? repository.findByTenantIdAndCompanyIdAndReadFalseAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            : repository.findUnreadForPrincipals(TenantContext.currentTenant(), CompanyContext.currentCompany(), currentPrincipals());
        unread.forEach(notification -> notification.setRead(true));
        repository.saveAll(unread);
        return unread.size();
    }

    @Transactional
    public void markReadCurrentUser(UUID id) {
        Notification notification = findEntity(id);
        String recipient = normalizePrincipal(notification.getRecipientUsername());
        if (!isAdmin() && !recipient.isEmpty() && !currentPrincipals().contains(recipient)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!notification.isRead()) {
            notification.setRead(true);
            repository.save(notification);
        }
    }

    private List<String> currentPrincipals() {
        Authentication auth = authentication();
        if (auth == null) {
            return List.of("SYSTEM");
        }
        List<String> roles = auth.getAuthorities().stream()
            .map(authority -> normalizePrincipal(authority.getAuthority()))
            .flatMap(role -> role.startsWith("ROLE_") ? Stream.of(role, role.substring(5)) : Stream.of(role))
            .distinct()
            .toList();
        return Stream.concat(Stream.of(normalizePrincipal(auth.getName())), roles.stream())
            .distinct()
            .toList();
    }
    private boolean isAdmin() {
        Authentication auth = authentication();
        return auth != null && auth.getAuthorities().stream()
            .map(authority -> normalizePrincipal(authority.getAuthority()))
            .anyMatch(role -> role.equals("ADMIN") || role.equals("ROLE_ADMIN"));
    }
    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
    private String normalizePrincipal(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    @Transactional(readOnly = true)
    public NotificationResponse get(UUID id) {
        return toLocalizedResponse(findEntity(id));
    }
    @Transactional
    public NotificationResponse create(NotificationCreateRequest dto) {
        Notification entity = mapper.toEntity(dto);
        applyTemplateMetadata(entity, dto);
        beforeCreate(entity, dto);
        Notification saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        schedulePushAfterCommit(saved);
        return toLocalizedResponse(saved);
    }

    /**
     * Creates one in-app + push notification per active company user that has the role.
     * Role names alone cannot receive FCM tokens, so they must be expanded to usernames.
     */
    @Transactional
    public int createForRole(RoleName role, String title, String message, String name) {
        return createForRole(role, title, message, name, null, null);
    }

    @Transactional
    public int createForRole(
        RoleName role,
        String title,
        String message,
        String name,
        String entityType,
        UUID entityId
    ) {
        if (role == null) {
            return 0;
        }
        List<User> users = userRepository.findActiveCompanyUsersByRole(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            role
        );
        int created = 0;
        for (User user : users) {
            if (user.getUsername() == null || user.getUsername().isBlank()) {
                continue;
            }
            NotificationCreateRequest request = new NotificationCreateRequest();
            request.setRecipientUsername(user.getUsername());
            request.setTitle(title);
            request.setMessage(message);
            request.setName(name != null ? name : title);
            request.setStatus("ACTIVE");
            request.setEntityType(entityType);
            request.setEntityId(entityId);
            create(request);
            created++;
        }
        if (created == 0) {
            log.warn("No active users found for role {} in company {}; notification not created",
                role, CompanyContext.currentCompany());
        }
        return created;
    }

    @Transactional
    public int createForRole(RoleName role, String titleKey, String messageKey, Map<String, Object> args, String name) {
        return createForRole(role, titleKey, messageKey, args, name, null, null);
    }

    @Transactional
    public int createForRole(
        RoleName role,
        String titleKey,
        String messageKey,
        Map<String, Object> args,
        String name,
        String entityType,
        UUID entityId
    ) {
        if (role == null) {
            return 0;
        }
        List<User> users = userRepository.findActiveCompanyUsersByRole(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            role
        );
        int created = 0;
        for (User user : users) {
            if (user.getUsername() == null || user.getUsername().isBlank()) {
                continue;
            }
            NotificationCreateRequest request = new NotificationCreateRequest();
            request.setRecipientUsername(user.getUsername());
            request.setTitleKey(titleKey);
            request.setMessageKey(messageKey);
            request.setTemplateArgs(args);
            request.setName(name);
            request.setStatus("ACTIVE");
            request.setEntityType(entityType);
            request.setEntityId(entityId);
            create(request);
            created++;
        }
        if (created == 0) {
            log.warn("No active users found for role {} in company {}; notification not created",
                role, CompanyContext.currentCompany());
        }
        return created;
    }

    /**
     * If {@code recipient} looks like a known RoleName, expand to users; otherwise treat as username.
     */
    @Transactional
    public int createForUsernameOrRole(String recipient, String title, String message, String name) {
        if (recipient == null || recipient.isBlank()) {
            log.warn("Notification skipped because recipient is blank");
            return 0;
        }
        String normalized = recipient.trim();
        RoleName role = tryParseRole(normalized);
        if (role != null) {
            return createForRole(role, title, message, name);
        }
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setRecipientUsername(normalized);
        request.setTitle(title);
        request.setMessage(message);
        request.setName(name != null ? name : title);
        request.setStatus("ACTIVE");
        create(request);
        return 1;
    }

    @Transactional
    public int createForUsernameOrRole(String recipient, String titleKey, String messageKey, Map<String, Object> args, String name) {
        if (recipient == null || recipient.isBlank()) {
            log.warn("Notification skipped because recipient is blank");
            return 0;
        }
        String normalized = recipient.trim();
        RoleName role = tryParseRole(normalized);
        if (role != null) {
            return createForRole(role, titleKey, messageKey, args, name);
        }
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setRecipientUsername(normalized);
        request.setTitleKey(titleKey);
        request.setMessageKey(messageKey);
        request.setTemplateArgs(args);
        request.setName(name);
        request.setStatus("ACTIVE");
        create(request);
        return 1;
    }

    private RoleName tryParseRole(String value) {
        String normalized = value.startsWith("ROLE_") ? value.substring(5) : value;
        try {
            return RoleName.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void schedulePushAfterCommit(Notification saved) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trySendPush(saved);
                }
            });
            return;
        }
        trySendPush(saved);
    }

    private void trySendPush(Notification notification) {
        try {
            firebasePushNotificationSender.sendToUser(notification.getRecipientUsername(), notification);
        } catch (RuntimeException ex) {
            // Push delivery is best-effort; the saved in-app notification remains the source of truth.
            log.warn("Firebase push delivery failed for notification {}: {}", notification.getId(), ex.getMessage());
        }
    }
    @Transactional
    public NotificationResponse update(UUID id, NotificationUpdateRequest dto) {
        Notification entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        applyTemplateMetadata(entity, dto);
        beforeUpdate(entity, dto);
        Notification saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toLocalizedResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Notification entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public NotificationResponse restore(UUID id) {
        Notification entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Notification saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toLocalizedResponse(saved);
    }
    protected Notification findEntity(UUID id) {
        Notification entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeCreate(Notification entity, NotificationCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(Notification entity, NotificationUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void applyTemplateMetadata(Notification entity, NotificationCreateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setTemplateArgsJson(localizationService.serializeArgs(dto.getTemplateArgs()));
        Map<String, Object> args = localizationService.deserializeArgs(entity.getTemplateArgsJson());
        Locale snapshotLocale = Locale.ENGLISH;
        if (hasText(entity.getTitleKey()) && !hasText(entity.getTitle())) {
            entity.setTitle(localizationService.renderTitle(entity.getTitleKey(), entity.getTitle(), args, snapshotLocale));
        }
        if (hasText(entity.getMessageKey()) && !hasText(entity.getMessage())) {
            entity.setMessage(localizationService.renderMessage(entity.getMessageKey(), entity.getMessage(), args, snapshotLocale));
        }
        if (!hasText(entity.getName()) && hasText(entity.getTitle())) {
            entity.setName(entity.getTitle());
        }
        if ((hasText(entity.getTitleKey()) || hasText(entity.getMessageKey())) && !hasText(entity.getRenderedLanguage())) {
            entity.setRenderedLanguage(localizationService.language(snapshotLocale));
        }
    }

    private NotificationResponse toLocalizedResponse(Notification entity) {
        NotificationLocalizationService.RenderedNotification rendered =
            localizationService.render(entity, LocaleContextHolder.getLocale());
        NotificationResponse response = mapper.toResponse(entity);
        if (response == null) {
            return null;
        }
        response.setTitle(rendered.title());
        response.setMessage(rendered.message());
        response.setTemplateArgs(rendered.args());
        response.setLanguage(rendered.language());
        navigationResolver.resolve(entity).ifPresent(target -> {
            response.setEntityType(target.entityType());
            response.setEntityId(target.entityId());
        });
        return response;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

