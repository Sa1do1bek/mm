package com.enterprise.mm.service;

import com.enterprise.mm.entity.NonMovingStockCase;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NonMovingStockNotificationService {
    private final NotificationService notificationService;



    public void notifyOwner(NonMovingStockCase stockCase, String event, String detail) {
        if (stockCase == null) {
            return;
        }
        String recipient = stockCase.getResponsibleUsername() == null
            ? "MM_MANAGER" : stockCase.getResponsibleUsername();
        notificationService.createForUsernameOrRole(
            recipient,
            "notification.nonMovingStock.case.title",
            "notification.nonMovingStock.case.message",
            Map.of(
                "caseNo", stockCase.getCaseNo() == null ? "" : stockCase.getCaseNo(),
                "event", event == null ? "" : event.replace('_', ' '),
                "detail", detail == null ? (event == null ? "" : event) : detail,
                "url", "/non-moving-stock/cases/" + stockCase.getId()
            ),
            null
        );
    }
}
