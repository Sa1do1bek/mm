package com.enterprise.mm.service;

import com.enterprise.mm.payload.QmsAssessmentDtos.AddDetailRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.CreateDraftAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.SupplierAssessmentResponse;
import com.enterprise.mm.payload.QmsAssessmentDtos.UpdateAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentCreateWithDetailsRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface QmsAssessmentService {
    SupplierAssessmentResponse createDraftAssessment(CreateDraftAssessmentRequest request);

    SupplierAssessmentResponse createWithDetails(QmsAssessmentCreateWithDetailsRequest request);

    SupplierAssessmentResponse updateDraftAssessment(UUID assessmentId, UpdateAssessmentRequest request);

    SupplierAssessmentResponse addDetail(UUID assessmentId, AddDetailRequest request);

    SupplierAssessmentResponse approveAssessment(UUID assessmentId);

    SupplierAssessmentResponse getAssessment(UUID assessmentId);

    Page<SupplierAssessmentResponse> searchAssessments(UUID supplierId, boolean scoreDesc, Pageable pageable);

    Page<SupplierAssessmentResponse> searchAssessments(UUID supplierId, String search, List<String> status, LocalDate dateFrom, LocalDate dateTo, boolean scoreDesc, Pageable pageable);
}
