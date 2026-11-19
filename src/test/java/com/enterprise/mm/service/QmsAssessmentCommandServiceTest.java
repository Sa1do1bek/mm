package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.enums.QmsAssessmentStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.QmsAssessmentApproveRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.SupplierAssessmentResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QmsAssessmentCommandServiceTest {

    @Mock QmsAssessmentService assessmentService;
    @Mock IdempotencyService idempotencyService;

    private QmsAssessmentCommandService service;
    private UUID assessmentId;

    @BeforeEach
    void setUp() {
        service = new QmsAssessmentCommandService(assessmentService, idempotencyService);
        assessmentId = UUID.randomUUID();
        lenient().when(idempotencyService.execute(
            anyString(), eq(assessmentId), anyString(), any(),
            eq(SupplierAssessmentResponse.class), any()))
            .thenAnswer(invocation -> ((Supplier<SupplierAssessmentResponse>) invocation.getArgument(5)).get());
    }

    @Test
    void rejectsStaleApprovalBeforeChangingSupplierRating() {
        when(assessmentService.getAssessment(assessmentId)).thenReturn(response(7L));

        assertThatThrownBy(() -> service.approve(
            assessmentId, new QmsAssessmentApproveRequest(6L), "qms-key"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(assessmentService, never()).approveAssessment(assessmentId);
    }

    @Test
    void refusesBlankIdempotencyKeys() {
        assertThatThrownBy(() -> service.approve(
            assessmentId, new QmsAssessmentApproveRequest(7L), "  "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Idempotency-Key");

        verify(idempotencyService, never()).execute(
            anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void refusesMissingVersionBeforeTouchingTheIdempotencyStore() {
        assertThatThrownBy(() -> service.approve(
            assessmentId, new QmsAssessmentApproveRequest(null), "qms-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.approve(assessmentId, null, "qms-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expectedVersion");

        verify(idempotencyService, never()).execute(
            anyString(), any(), anyString(), any(), any(), any());
    }

    private SupplierAssessmentResponse response(Long version) {
        return new SupplierAssessmentResponse(
            assessmentId, "tenant", UUID.randomUUID(), UUID.randomUUID(), "QMS-1", "Assessment",
            null, LocalDate.now(), BigDecimal.ZERO, QmsAssessmentStatus.DRAFT, null, false,
            LocalDateTime.now(), LocalDateTime.now(), version, List.of());
    }
}
