package com.enterprise.mm.service;

import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.Notification;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationEntityNavigationResolver {
    static final String APPROVAL_ASSIGNED_TITLE_KEY = "notification.approval.task.assigned.title";

    private final ApprovalTaskRepository approvalTaskRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final UserRepository userRepository;
    private final NotificationLocalizationService localizationService;

    public Optional<NavigationTarget> resolve(Notification notification) {
        if (notification == null) {
            return Optional.empty();
        }
        if (hasText(notification.getEntityType()) && notification.getEntityId() != null) {
            return Optional.of(new NavigationTarget(notification.getEntityType(), notification.getEntityId()));
        }
        if (!isApprovalAssignmentNotification(notification)) {
            return Optional.empty();
        }
        Map<String, Object> args = localizationService.args(notification);
        String document = text(args.get("document"));
        Integer step = parseStep(args.get("step"));
        String recipient = notification.getRecipientUsername();
        if (!hasText(recipient)) {
            return Optional.empty();
        }
        Set<String> roleNames = recipientRoles(
            notification.getTenantId(),
            notification.getCompanyId(),
            recipient.trim()
        );
        List<ApprovalTask> candidates = roleNames.isEmpty()
            ? approvalTaskRepository.findNavigationCandidatesForUser(
                notification.getTenantId(),
                notification.getCompanyId(),
                recipient.trim()
            )
            : approvalTaskRepository.findNavigationCandidates(
                notification.getTenantId(),
                notification.getCompanyId(),
                recipient.trim(),
                roleNames
            );
        LocalDateTime notificationTime = notification.getCreatedAt();
        return candidates.stream()
            .map(task -> match(task, document, step, notificationTime))
            .flatMap(Optional::stream)
            .min(Comparator.comparingLong(NavigationMatch::timeDistanceSeconds))
            .map(match -> new NavigationTarget(
                NotificationEntityType.APPROVAL_WORKFLOW.name(),
                match.task().getId()
            ));
    }

    private boolean isApprovalAssignmentNotification(Notification notification) {
        if (APPROVAL_ASSIGNED_TITLE_KEY.equals(notification.getTitleKey())) {
            return true;
        }
        String messageKey = notification.getMessageKey();
        return messageKey != null && messageKey.startsWith("notification.approval.task.assigned.message");
    }

    private Optional<NavigationMatch> match(
        ApprovalTask task,
        String document,
        Integer step,
        LocalDateTime notificationTime
    ) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByIdAndIsDeletedFalse(task.getApprovalInstanceId())
            .orElse(null);
        if (instance == null || !documentMatches(instance, document)) {
            return Optional.empty();
        }
        if (step != null && task.getStepOrder() != null && !step.equals(task.getStepOrder())) {
            return Optional.empty();
        }
        long distance = timeDistanceSeconds(task.getCreatedAt(), notificationTime);
        if (distance > Duration.ofDays(30).toSeconds()) {
            return Optional.empty();
        }
        return Optional.of(new NavigationMatch(task, distance));
    }

    private Set<String> recipientRoles(String tenantId, UUID companyId, String recipientUsername) {
        return userRepository.resolveActiveUserByUsername(tenantId, companyId, recipientUsername)
            .map(User::getRoles)
            .orElse(Set.of())
            .stream()
            .map(RoleName::name)
            .collect(Collectors.toSet());
    }

    private boolean documentMatches(ApprovalInstance instance, String document) {
        if (!hasText(document)) {
            return true;
        }
        String expected = document.trim();
        if (hasText(instance.getName()) && instance.getName().trim().equalsIgnoreCase(expected)) {
            return true;
        }
        return hasText(instance.getModuleKey()) && instance.getModuleKey().trim().equalsIgnoreCase(expected);
    }

    private Integer parseStep(Object rawStep) {
        if (rawStep == null) {
            return null;
        }
        String value = String.valueOf(rawStep).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long timeDistanceSeconds(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(Duration.between(left, right).toSeconds());
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record NavigationTarget(String entityType, UUID entityId) {
    }

    private record NavigationMatch(ApprovalTask task, long timeDistanceSeconds) {
    }
}
