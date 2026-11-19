package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps a service order's acceptance state derived from its current, non-deleted acts. */
@Service
@RequiredArgsConstructor
public class ServiceOrderAcceptanceLifecycleService {
    private static final Set<String> ACCEPTED_COVERAGE_STATUSES = Set.of(
        "APPROVED", "SENT_TO_PAYMENT", "PAID"
    );
    private static final Set<String> ACTIVE_REVIEW_STATUSES = Set.of(
        "DRAFT", "RETURNED", "SUBMITTED", "TECHNICAL_REVIEW", "DOCUMENT_REVIEW", "FINANCE_REVIEW"
    );
    private static final Set<String> TERMINAL_ORDER_STATUSES = Set.of("CLOSED", "CANCELLED");

    private final ServiceAcceptanceActRepository actRepository;
    private final ServiceOrderRepository orderRepository;

    @Transactional
    public String recompute(UUID serviceOrderId) {
        if (serviceOrderId == null) {
            return null;
        }
        ServiceOrder order = orderRepository
            .findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), serviceOrderId)
            .orElse(null);
        if (order == null || TERMINAL_ORDER_STATUSES.contains(order.getStatus())) {
            return order == null ? null : order.getStatus();
        }

        List<ServiceAcceptanceAct> acts = actRepository
            .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                TenantContext.currentTenant(), order.getCompanyId(), order.getId());
        BigDecimal approvedCoverage = acts.stream()
            .filter(act -> contributesToAcceptedCoverage(act.getStatus()))
            .map(ServiceAcceptanceAct::getAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        boolean hasActiveReview = acts.stream().anyMatch(act -> isActiveReviewActStatus(act.getStatus()));

        String derivedStatus;
        if (totalAmount.signum() > 0 && approvedCoverage.compareTo(totalAmount) >= 0) {
            derivedStatus = "ACCEPTED";
        } else if (approvedCoverage.signum() > 0) {
            derivedStatus = "PARTIALLY_ACCEPTED";
        } else if (hasActiveReview) {
            derivedStatus = "ACT_PENDING";
        } else {
            derivedStatus = "ACTIVE";
        }

        if (!Objects.equals(order.getStatus(), derivedStatus)) {
            order.setStatus(derivedStatus);
            orderRepository.save(order);
        }
        return derivedStatus;
    }

    public static boolean isInactiveActStatus(String status) {
        return "REJECTED".equals(status) || "CANCELLED".equals(status);
    }

    public static boolean contributesToAcceptedCoverage(String status) {
        return ACCEPTED_COVERAGE_STATUSES.contains(status);
    }

    public static boolean isActiveReviewActStatus(String status) {
        return ACTIVE_REVIEW_STATUSES.contains(status);
    }
}
