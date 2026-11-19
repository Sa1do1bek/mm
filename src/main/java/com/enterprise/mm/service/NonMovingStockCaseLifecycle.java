package com.enterprise.mm.service;

import static com.enterprise.mm.enums.NonMovingStockCaseStatus.APPROVAL_PENDING;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.CANCELLED;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.CASE_CREATED;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.CLOSED;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.COMMISSION_REVIEW;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.DECISION_APPROVED;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.EXECUTED;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.IN_EXECUTION;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.REVIEW_DEFERRED;
import static com.enterprise.mm.enums.NonMovingStockCaseStatus.UNDER_REVIEW;
import static java.util.Map.entry;

import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.error.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NonMovingStockCaseLifecycle {

    private static final Map<NonMovingStockCaseStatus, Set<NonMovingStockCaseStatus>> ALLOWED = Map.ofEntries(
        entry(CASE_CREATED, Set.of(UNDER_REVIEW, CANCELLED)),
        entry(UNDER_REVIEW, Set.of(COMMISSION_REVIEW, REVIEW_DEFERRED, CANCELLED)),
        entry(COMMISSION_REVIEW, Set.of(APPROVAL_PENDING, UNDER_REVIEW, CANCELLED)),
        entry(APPROVAL_PENDING, Set.of(DECISION_APPROVED, UNDER_REVIEW)),
        entry(DECISION_APPROVED, Set.of(IN_EXECUTION, REVIEW_DEFERRED)),
        entry(IN_EXECUTION, Set.of(EXECUTED)),
        entry(EXECUTED, Set.of(CLOSED)),
        entry(REVIEW_DEFERRED, Set.of(UNDER_REVIEW, CANCELLED)),
        entry(CLOSED, Set.of(UNDER_REVIEW)),
        entry(CANCELLED, Set.of(UNDER_REVIEW))
    );

    public void requireTransition(NonMovingStockCaseStatus from, NonMovingStockCaseStatus to) {
        if (from == null || to == null || !ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new BusinessException("Invalid non-moving stock case transition: " + from + " -> " + to);
        }
    }

    public void apply(
        NonMovingStockCase stockCase,
        NonMovingStockCaseStatus target,
        long expectedVersion,
        String reason,
        LocalDate nextReviewDate,
        String actor
    ) {
        if (stockCase == null) {
            throw new BusinessException("Non-moving stock case is required");
        }
        long actualVersion = stockCase.getVersion() == null ? 0L : stockCase.getVersion();
        if (actualVersion != expectedVersion) {
            throw new BusinessException(
                "Non-moving stock case version conflict: expected " + expectedVersion + " but was " + actualVersion
            );
        }
        NonMovingStockCaseStatus current = status(stockCase.getStatus());
        requireTransition(current, target);
        validateCommand(current, target, reason, nextReviewDate);

        LocalDateTime now = LocalDateTime.now();
        if (target == REVIEW_DEFERRED) {
            stockCase.setReviewDueDate(nextReviewDate);
        } else if (target == CLOSED) {
            stockCase.setClosedAt(now);
            stockCase.setClosedBy(trim(actor));
            stockCase.setClosureReason(trim(reason));
        } else if (target == CANCELLED) {
            stockCase.setCancelledAt(now);
            stockCase.setCancelledBy(trim(actor));
            stockCase.setCancellationReason(trim(reason));
        } else if (target == UNDER_REVIEW && (current == CLOSED || current == CANCELLED)) {
            stockCase.setReopenedAt(now);
            stockCase.setReopenedBy(trim(actor));
            stockCase.setReopenReason(trim(reason));
            stockCase.setClosedAt(null);
            stockCase.setClosedBy(null);
            stockCase.setClosureReason(null);
            stockCase.setCancelledAt(null);
            stockCase.setCancelledBy(null);
            stockCase.setCancellationReason(null);
        }
        stockCase.setStatus(target.name());
    }

    public NonMovingStockCaseStatus status(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Non-moving stock case status is required");
        }
        try {
            return NonMovingStockCaseStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported non-moving stock case status: " + value);
        }
    }

    private void validateCommand(
        NonMovingStockCaseStatus current,
        NonMovingStockCaseStatus target,
        String reason,
        LocalDate nextReviewDate
    ) {
        if (target == REVIEW_DEFERRED) {
            requireReason(reason, "Deferred review reason is required");
            if (nextReviewDate == null || !nextReviewDate.isAfter(LocalDate.now())) {
                throw new BusinessException("Deferred review date must be in the future");
            }
        }
        if (target == CANCELLED || target == CLOSED || (target == UNDER_REVIEW && (current == CLOSED || current == CANCELLED))) {
            requireReason(reason, target + " reason is required");
        }
    }

    private void requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(message);
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
