package com.enterprise.mm.service;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ServiceWorkflowNotificationService {
    private final NotificationService notificationService;

    public ServiceWorkflowNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void approvalRequired(String recipient, String reference) {
        notify(recipient, "approvalRequired", reference);
    }

    public void executionOverdue(String recipient, String reference) {
        notify(recipient, "executionOverdue", reference);
    }

    public void reviewRequired(String recipient, String reference) {
        notify(recipient, "reviewRequired", reference);
    }

    public void claimRaised(String recipient, String reference) {
        notify(recipient, "claimRaised", reference);
    }

    public void paymentReady(String recipient, String reference) {
        notify(recipient, "paymentReady", reference);
    }

    private void notify(String recipient, String key, String reference) {
        notificationService.createForUsernameOrRole(
            recipient,
            "notification.service." + key + ".title",
            "notification.service." + key + ".message",
            Map.of("reference", reference == null ? "" : reference),
            null
        );
    }
}
