package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Criteria;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAssessment;
import com.enterprise.mm.entity.SupplierAssessmentDetail;
import com.enterprise.mm.enums.QmsAssessmentStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.QmsAssessmentCreateWithDetailsRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.CriteriaRepository;
import com.enterprise.mm.repository.SupplierAssessmentRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QmsAssessmentIntegrityTest {
    @Mock private SupplierAssessmentRepository assessmentRepository;
    @Mock private CriteriaRepository criteriaRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AuditService auditService;
    @Mock private SupplierAdmissionService admissionService;

    private QmsAssessmentServiceImpl service;
    private UUID companyId;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("qms-integrity-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(companyId);
        supplier.setName("Supplier");
        service = new QmsAssessmentServiceImpl(
            assessmentRepository,
            criteriaRepository,
            supplierRepository,
            companyRepository,
            auditService,
            admissionService
        );
        lenient().when(assessmentRepository.save(any())).thenAnswer(invocation -> {
            SupplierAssessment assessment = invocation.getArgument(0);
            if (assessment.getId() == null) assessment.setId(UUID.randomUUID());
            if (assessment.getTenantId() == null) assessment.setTenantId(TenantContext.currentTenant());
            return assessment;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void approvalUsesCompleteNormalizedWeightsAndRoundsHalfUp() {
        Criteria first = criteria("C1", "50", "2");
        Criteria second = criteria("C2", "100", "1");
        SupplierAssessment assessment = assessment(
            detail(first, "40"),
            detail(second, "80"));
        stubApproval(assessment, List.of(first, second));

        var approved = service.approveAssessment(assessment.getId());

        assertThat(approved.totalWeightedScore()).isEqualByComparingTo("80.00");
        assertThat(approved.status()).isEqualTo(QmsAssessmentStatus.APPROVED);
        verify(admissionService).recalculate(supplier.getId());
    }

    @Test
    void approvalRejectsMissingDuplicateOrInvalidCriteriaSets() {
        Criteria first = criteria("C1", "100", "1");
        Criteria second = criteria("C2", "100", "1");
        SupplierAssessment missing = assessment(detail(first, "80"));
        stubApproval(missing, List.of(first, second));
        assertThatThrownBy(() -> service.approveAssessment(missing.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("every active criteria");

        SupplierAssessment duplicate = assessment(detail(first, "80"), detail(first, "90"));
        stubApproval(duplicate, List.of(first));
        assertThatThrownBy(() -> service.approveAssessment(duplicate.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("duplicate");

        Criteria zeroWeight = criteria("C3", "100", "0");
        SupplierAssessment invalidWeight = assessment(detail(zeroWeight, "80"));
        stubApproval(invalidWeight, List.of(zeroWeight));
        assertThatThrownBy(() -> service.approveAssessment(invalidWeight.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("weight");
    }

    @Test
    void createWithDetailsPersistsOneValidatedAggregate() {
        Criteria first = criteria("C1", "50", "2");
        Criteria second = criteria("C2", "100", "1");
        Company company = new Company();
        company.setId(companyId);
        company.setTenantId(TenantContext.currentTenant());
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(company));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), supplier.getId())).thenReturn(Optional.of(supplier));
        when(criteriaRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), first.getId())).thenReturn(Optional.of(first));
        when(criteriaRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), second.getId())).thenReturn(Optional.of(second));
        QmsAssessmentCreateWithDetailsRequest request = request(
            new QmsAssessmentCreateWithDetailsRequest.Detail(first.getId(), new BigDecimal("40"), "First"),
            new QmsAssessmentCreateWithDetailsRequest.Detail(second.getId(), new BigDecimal("80"), "Second"));

        var created = service.createWithDetails(request);

        assertThat(created.details()).hasSize(2);
        ArgumentCaptor<SupplierAssessment> captor = ArgumentCaptor.forClass(SupplierAssessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertThat(captor.getValue().getDetails())
            .allSatisfy(detail -> {
                assertThat(detail.getAssessment()).isSameAs(captor.getValue());
                assertThat(detail.getCompanyId()).isEqualTo(companyId);
            });
        verify(auditService).logInCurrentTransaction(
            org.mockito.ArgumentMatchers.eq("SupplierAssessment"),
            org.mockito.ArgumentMatchers.eq(created.id()),
            org.mockito.ArgumentMatchers.eq("CREATE_WITH_DETAILS"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createWithDetailsRejectsDuplicateBeforeAnyPersistenceOrAudit() {
        UUID criteriaId = UUID.randomUUID();
        QmsAssessmentCreateWithDetailsRequest request = request(
            new QmsAssessmentCreateWithDetailsRequest.Detail(criteriaId, BigDecimal.TEN, null),
            new QmsAssessmentCreateWithDetailsRequest.Detail(criteriaId, BigDecimal.ONE, null));

        assertThatThrownBy(() -> service.createWithDetails(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("duplicate");
        verify(assessmentRepository, never()).save(any());
        verify(auditService, never()).logInCurrentTransaction(any(), any(), any(), any(), any(), any());
    }

    private void stubApproval(SupplierAssessment assessment, List<Criteria> activeCriteria) {
        when(assessmentRepository.findWithDetailsByIdAndIsDeletedFalse(assessment.getId()))
            .thenReturn(Optional.of(assessment));
        when(criteriaRepository.findByTenantIdAndCompanyIdAndIsActiveTrueAndIsDeletedFalseOrderByCodeAsc(
            TenantContext.currentTenant(), companyId)).thenReturn(activeCriteria);
    }

    private SupplierAssessment assessment(SupplierAssessmentDetail... details) {
        SupplierAssessment assessment = new SupplierAssessment();
        assessment.setId(UUID.randomUUID());
        assessment.setTenantId(TenantContext.currentTenant());
        assessment.setCompanyId(companyId);
        assessment.setSupplier(supplier);
        assessment.setAssessmentDate(LocalDate.now());
        assessment.setAssessmentStatus(QmsAssessmentStatus.DRAFT);
        assessment.setCode("QA-TEST");
        assessment.setName("Assessment");
        for (SupplierAssessmentDetail detail : details) {
            detail.setAssessment(assessment);
            assessment.getDetails().add(detail);
        }
        return assessment;
    }

    private SupplierAssessmentDetail detail(Criteria criteria, String score) {
        SupplierAssessmentDetail detail = new SupplierAssessmentDetail();
        detail.setId(UUID.randomUUID());
        detail.setTenantId(TenantContext.currentTenant());
        detail.setCompanyId(companyId);
        detail.setStatus("ACTIVE");
        detail.setCriteria(criteria);
        detail.setGivenScore(new BigDecimal(score));
        return detail;
    }

    private Criteria criteria(String code, String maxScore, String weight) {
        Criteria criteria = new Criteria();
        criteria.setId(UUID.randomUUID());
        criteria.setTenantId(TenantContext.currentTenant());
        criteria.setCompanyId(companyId);
        criteria.setCode(code);
        criteria.setName(code);
        criteria.setStatus("ACTIVE");
        criteria.setIsActive(true);
        criteria.setMaxScore(new BigDecimal(maxScore));
        criteria.setWeight(new BigDecimal(weight));
        return criteria;
    }

    private QmsAssessmentCreateWithDetailsRequest request(
        QmsAssessmentCreateWithDetailsRequest.Detail... details
    ) {
        return new QmsAssessmentCreateWithDetailsRequest(
            companyId,
            supplier.getId(),
            LocalDate.now(),
            UUID.randomUUID(),
            "QA-NEW",
            "New assessment",
            null,
            List.of(details)
        );
    }
}
