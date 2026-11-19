package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.Notification;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationEntityNavigationResolverTest {
    @Mock
    ApprovalTaskRepository approvalTaskRepository;
    @Mock
    ApprovalInstanceRepository approvalInstanceRepository;
    @Mock
    UserRepository userRepository;

    private NotificationEntityNavigationResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NotificationEntityNavigationResolver(
            approvalTaskRepository,
            approvalInstanceRepository,
            userRepository,
            new NotificationLocalizationService(
                new org.springframework.context.support.ResourceBundleMessageSource(),
                new com.fasterxml.jackson.databind.ObjectMapper()
            )
        );
    }

    @Test
    void resolvesLegacyApprovalNotificationFromTemplateArgs() {
        UUID taskId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(2);

        Notification notification = new Notification();
        notification.setTenantId("tenant-1");
        notification.setCompanyId(UUID.randomUUID());
        notification.setRecipientUsername("approver.user");
        notification.setTitleKey(NotificationEntityNavigationResolver.APPROVAL_ASSIGNED_TITLE_KEY);
        notification.setMessageKey("notification.approval.task.assigned.message.withStep");
        notification.setTemplateArgsJson("{\"document\":\"PR-100 - Bearings\",\"step\":1}");
        notification.setCreatedAt(createdAt);

        ApprovalTask task = new ApprovalTask();
        task.setId(taskId);
        task.setApprovalInstanceId(instanceId);
        task.setStepOrder(1);
        task.setAssigneeType(ApprovalAssigneeType.USER);
        task.setAssigneeUsername("approver.user");
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setCreatedAt(createdAt.minusSeconds(5));

        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(instanceId);
        instance.setName("PR-100 - Bearings");

        when(approvalTaskRepository.findNavigationCandidatesForUser(
            notification.getTenantId(),
            notification.getCompanyId(),
            "approver.user"
        )).thenReturn(List.of(task));
        when(approvalInstanceRepository.findByIdAndIsDeletedFalse(instanceId)).thenReturn(Optional.of(instance));

        var target = resolver.resolve(notification);

        assertThat(target).isPresent();
        assertThat(target.get().entityType()).isEqualTo(NotificationEntityType.APPROVAL_WORKFLOW.name());
        assertThat(target.get().entityId()).isEqualTo(taskId);
    }

    @Test
    void keepsPersistedNavigationWhenAlreadyPresent() {
        UUID taskId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setEntityType(NotificationEntityType.APPROVAL_WORKFLOW.name());
        notification.setEntityId(taskId);

        var target = resolver.resolve(notification);

        assertThat(target).contains(new NotificationEntityNavigationResolver.NavigationTarget(
            NotificationEntityType.APPROVAL_WORKFLOW.name(),
            taskId
        ));
    }
}
