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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.error.BusinessException;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NonMovingStockCaseLifecycleTest {

    private NonMovingStockCaseLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new NonMovingStockCaseLifecycle();
    }

    @ParameterizedTest
    @MethodSource("canonicalTransitions")
    void allowsCanonicalTransitions(NonMovingStockCaseStatus from, NonMovingStockCaseStatus to) {
        assertThatCode(() -> lifecycle.requireTransition(from, to)).doesNotThrowAnyException();
    }

    @Test
    void rejectsLifecycleShortcuts() {
        assertThatThrownBy(() -> lifecycle.requireTransition(CASE_CREATED, EXECUTED))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CASE_CREATED")
            .hasMessageContaining("EXECUTED");
    }

    @Test
    void deferredReviewRequiresReasonAndFutureDate() {
        NonMovingStockCase stockCase = stockCase(UNDER_REVIEW, 4L);

        assertThatThrownBy(() -> lifecycle.apply(
            stockCase, REVIEW_DEFERRED, 4L, " ", LocalDate.now().plusDays(1), "manager"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("reason");

        assertThatThrownBy(() -> lifecycle.apply(
            stockCase, REVIEW_DEFERRED, 4L, "Await demand plan", LocalDate.now(), "manager"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("future");
    }

    @Test
    void optimisticVersionConflictDoesNotMutateCase() {
        NonMovingStockCase stockCase = stockCase(CASE_CREATED, 7L);

        assertThatThrownBy(() -> lifecycle.apply(
            stockCase, UNDER_REVIEW, 6L, "Start review", null, "owner"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("version");
        assertThat(stockCase.getStatus()).isEqualTo(CASE_CREATED.name());
    }

    @Test
    void recordsTerminalAndReopenMetadata() {
        NonMovingStockCase stockCase = stockCase(EXECUTED, 2L);
        lifecycle.apply(stockCase, CLOSED, 2L, "Evidence reconciled", null, "controller");

        assertThat(stockCase.getClosedAt()).isNotNull();
        assertThat(stockCase.getClosedBy()).isEqualTo("controller");
        assertThat(stockCase.getClosureReason()).isEqualTo("Evidence reconciled");

        lifecycle.apply(stockCase, UNDER_REVIEW, 2L, "Integration correction", null, "mm-manager");

        assertThat(stockCase.getReopenedAt()).isNotNull();
        assertThat(stockCase.getReopenedBy()).isEqualTo("mm-manager");
        assertThat(stockCase.getReopenReason()).isEqualTo("Integration correction");
        assertThat(stockCase.getClosedAt()).isNull();
    }

    private static NonMovingStockCase stockCase(NonMovingStockCaseStatus status, long version) {
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setStatus(status.name());
        stockCase.setVersion(version);
        return stockCase;
    }

    private static Stream<Arguments> canonicalTransitions() {
        return Stream.of(
            Arguments.of(CASE_CREATED, UNDER_REVIEW),
            Arguments.of(CASE_CREATED, CANCELLED),
            Arguments.of(UNDER_REVIEW, COMMISSION_REVIEW),
            Arguments.of(UNDER_REVIEW, REVIEW_DEFERRED),
            Arguments.of(COMMISSION_REVIEW, APPROVAL_PENDING),
            Arguments.of(APPROVAL_PENDING, DECISION_APPROVED),
            Arguments.of(APPROVAL_PENDING, UNDER_REVIEW),
            Arguments.of(DECISION_APPROVED, IN_EXECUTION),
            Arguments.of(DECISION_APPROVED, REVIEW_DEFERRED),
            Arguments.of(IN_EXECUTION, EXECUTED),
            Arguments.of(EXECUTED, CLOSED),
            Arguments.of(REVIEW_DEFERRED, UNDER_REVIEW),
            Arguments.of(CLOSED, UNDER_REVIEW),
            Arguments.of(CANCELLED, UNDER_REVIEW)
        );
    }
}
