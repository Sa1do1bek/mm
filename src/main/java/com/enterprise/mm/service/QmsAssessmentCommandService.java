package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.QmsAssessmentApproveRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.SupplierAssessmentResponse;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QmsAssessmentCommandService {
    private static final String APPROVE_OPERATION = "QMS_ASSESSMENT_APPROVE";

    private final QmsAssessmentService assessmentService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public SupplierAssessmentResponse approve(
        UUID assessmentId,
        QmsAssessmentApproveRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for QMS assessment approval");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required for QMS assessment approval");
        }
        return idempotencyService.execute(
            APPROVE_OPERATION,
            assessmentId,
            idempotencyKey,
            request,
            SupplierAssessmentResponse.class,
            () -> {
                SupplierAssessmentResponse current = assessmentService.getAssessment(assessmentId);
                if (!Objects.equals(current.version(), request.expectedVersion())) {
                    throw new ConflictException(
                        "QMS assessment was changed by another user; refresh and retry");
                }
                return assessmentService.approveAssessment(assessmentId);
            }
        );
    }
}
