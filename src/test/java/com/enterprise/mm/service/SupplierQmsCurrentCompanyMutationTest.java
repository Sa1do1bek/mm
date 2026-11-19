package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Criteria;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAssessment;
import com.enterprise.mm.entity.SupplierEvaluation;
import com.enterprise.mm.enums.QmsAssessmentStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierEvaluationMapper;
import com.enterprise.mm.payload.BusinessActionDtos.SupplierEvaluationRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.AddDetailRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.CreateDraftAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.UpdateAssessmentRequest;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaCreateRequest;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaUpdateRequest;
import com.enterprise.mm.payload.SupplierEvaluationCreateRequest;
import com.enterprise.mm.payload.SupplierEvaluationUpdateRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.CriteriaRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierAssessmentRepository;
import com.enterprise.mm.repository.SupplierEvaluationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierQmsCurrentCompanyMutationTest {
    private static final String TENANT = "supplier-qms-company-boundary";

    @Mock private SupplierEvaluationRepository evaluationRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierAssessmentRepository assessmentRepository;
    @Mock private CriteriaRepository criteriaRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AuditService auditService;
    @Mock private SupplierAdmissionService admissionService;

    private UUID currentCompanyId;
    private UUID otherCompanyId;
    private SupplierEvaluationService evaluationService;
    private QmsAssessmentServiceImpl assessmentService;
    private QmsCriteriaService criteriaService;

    @BeforeEach
    void setUp() {
        currentCompanyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(currentCompanyId);

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        evaluationService = new SupplierEvaluationService(
            evaluationRepository,
            new SupplierEvaluationMapper(),
            supplierRepository,
            purchaseOrderRepository,
            auditService,
            new ObjectMapper(),
            admissionService
        );
        assessmentService = new QmsAssessmentServiceImpl(
            assessmentRepository,
            criteriaRepository,
            supplierRepository,
            companyRepository,
            auditService,
            admissionService
        );
        criteriaService = new QmsCriteriaService(criteriaRepository, companyRepository, auditService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
    }

    @Test
    void globalAdminCanReadButCannotUpdateDeleteOrRestoreForeignEvaluationById() {
        SupplierEvaluation evaluation = evaluation(otherCompanyId);
        when(evaluationRepository.findByIdAndIsDeletedFalse(evaluation.getId()))
            .thenReturn(Optional.of(evaluation));
        when(evaluationRepository.findAnyById(evaluation.getId())).thenReturn(Optional.of(evaluation));

        assertThat(evaluationService.get(evaluation.getId()).getCompanyId()).isEqualTo(otherCompanyId);

        assertThatThrownBy(() -> evaluationService.update(
            evaluation.getId(), new SupplierEvaluationUpdateRequest()))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> evaluationService.softDelete(evaluation.getId()))
            .isInstanceOf(NotFoundException.class);

        evaluation.softDelete();
        assertThatThrownBy(() -> evaluationService.restore(evaluation.getId()))
            .isInstanceOf(NotFoundException.class);

        assertThat(evaluation.isDeleted()).isTrue();
        verify(evaluationRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreateOrCommandEvaluationForForeignSupplier() {
        Supplier supplier = supplier(otherCompanyId);
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplier.getId()))
            .thenReturn(Optional.of(supplier));
        SupplierEvaluationCreateRequest request = new SupplierEvaluationCreateRequest();
        request.setSupplierId(supplier.getId());
        request.setDeliveryScore(BigDecimal.TEN);

        assertThatThrownBy(() -> evaluationService.create(request))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Supplier not found");
        SupplierEvaluationRequest command = new SupplierEvaluationRequest(
            supplier.getId(), null, BigDecimal.TEN, BigDecimal.TEN,
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN);
        assertThatThrownBy(() -> evaluationService.evaluate(command))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Supplier not found");
        assertThatThrownBy(() -> evaluationService.evaluateDelivery(
            supplier.getId(), null, 90))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Supplier not found");

        verify(evaluationRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotRecalculateEvaluationFromForeignOperationalDocuments() {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(UUID.randomUUID());
        purchaseOrder.setTenantId(TENANT);
        purchaseOrder.setCompanyId(otherCompanyId);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(TENANT);
        item.setCompanyId(otherCompanyId);

        assertThatThrownBy(() -> evaluationService.evaluateReceipt(
            purchaseOrder, item, UUID.randomUUID(), BigDecimal.ONE, LocalDate.now()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Purchase order not found");

        QualityInspection inspection = new QualityInspection();
        inspection.setId(UUID.randomUUID());
        inspection.setTenantId(TENANT);
        inspection.setCompanyId(otherCompanyId);
        inspection.setStockMovementId(UUID.randomUUID());
        assertThatThrownBy(() -> evaluationService.recalculateFromQualityInspection(inspection))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Quality inspection not found");

        verify(evaluationRepository, never()).save(any());
    }

    @Test
    void globalAdminCanReadButCannotModifyOrApproveForeignAssessmentById() {
        SupplierAssessment assessment = assessment(otherCompanyId);
        when(assessmentRepository.findWithDetailsByIdAndIsDeletedFalse(assessment.getId()))
            .thenReturn(Optional.of(assessment));

        assertThat(assessmentService.getAssessment(assessment.getId()).companyId())
            .isEqualTo(otherCompanyId);

        UpdateAssessmentRequest update = new UpdateAssessmentRequest(
            LocalDate.now(), UUID.randomUUID(), "QMS-REMOTE", "Remote", null);
        assertThatThrownBy(() -> assessmentService.updateDraftAssessment(assessment.getId(), update))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> assessmentService.addDetail(
            assessment.getId(), new AddDetailRequest(UUID.randomUUID(), BigDecimal.ONE, null)))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> assessmentService.approveAssessment(assessment.getId()))
            .isInstanceOf(NotFoundException.class);

        assertThat(assessment.getAssessmentStatus()).isEqualTo(QmsAssessmentStatus.DRAFT);
        verify(assessmentRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreateAssessmentWhileAnotherCompanyIsCurrent() {
        Company company = company(otherCompanyId);
        Supplier supplier = supplier(otherCompanyId);
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, otherCompanyId))
            .thenReturn(Optional.of(company));
        CreateDraftAssessmentRequest request = new CreateDraftAssessmentRequest(
            otherCompanyId, supplier.getId(), LocalDate.now(), null, "QMS-REMOTE", "Remote", null);

        assertThatThrownBy(() -> assessmentService.createDraftAssessment(request))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Company not found");
        verify(assessmentRepository, never()).save(any());
        verify(supplierRepository, never())
            .findByTenantIdAndIdAndIsDeletedFalse(TENANT, supplier.getId());
    }

    @Test
    void globalAdminCanReadButCannotUpdateOrDeleteForeignCriteriaById() {
        Criteria criteria = criteria(otherCompanyId);
        when(criteriaRepository.findByIdAndIsDeletedFalse(criteria.getId()))
            .thenReturn(Optional.of(criteria));

        assertThat(criteriaService.get(criteria.getId()).companyId()).isEqualTo(otherCompanyId);

        CriteriaUpdateRequest update = new CriteriaUpdateRequest(
            null, "Remote updated", null, BigDecimal.TEN, BigDecimal.ONE, true);
        assertThatThrownBy(() -> criteriaService.update(criteria.getId(), update))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> criteriaService.softDelete(criteria.getId()))
            .isInstanceOf(NotFoundException.class);

        assertThat(criteria.isDeleted()).isFalse();
        assertThat(criteria.getName()).isEqualTo("Remote criteria");
        verify(criteriaRepository, never()).save(any());
    }

    @Test
    void globalAdminCannotCreateCriteriaWhileAnotherCompanyIsCurrent() {
        Company company = company(otherCompanyId);
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, otherCompanyId))
            .thenReturn(Optional.of(company));
        CriteriaCreateRequest request = new CriteriaCreateRequest(
            otherCompanyId, "QC-REMOTE", "Remote", null, BigDecimal.TEN, BigDecimal.ONE, true);

        assertThatThrownBy(() -> criteriaService.create(request))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Company not found");

        verify(criteriaRepository, never()).save(any());
    }

    private SupplierEvaluation evaluation(UUID companyId) {
        SupplierEvaluation evaluation = new SupplierEvaluation();
        evaluation.setId(UUID.randomUUID());
        evaluation.setTenantId(TENANT);
        evaluation.setCompanyId(companyId);
        evaluation.setSupplierId(UUID.randomUUID());
        evaluation.setCode("EVAL-REMOTE");
        evaluation.setName("Remote evaluation");
        evaluation.setStatus("POSTED");
        evaluation.setDeliveryScore(BigDecimal.TEN);
        evaluation.setQualityScore(BigDecimal.TEN);
        evaluation.setQuantityScore(BigDecimal.TEN);
        evaluation.setPriceScore(BigDecimal.TEN);
        evaluation.setClaimsScore(BigDecimal.TEN);
        return evaluation;
    }

    private SupplierAssessment assessment(UUID companyId) {
        SupplierAssessment assessment = new SupplierAssessment();
        assessment.setId(UUID.randomUUID());
        assessment.setTenantId(TENANT);
        assessment.setCompanyId(companyId);
        assessment.setSupplier(supplier(companyId));
        assessment.setAssessmentDate(LocalDate.now());
        assessment.setAssessmentStatus(QmsAssessmentStatus.DRAFT);
        assessment.setCode("QMS-REMOTE");
        assessment.setName("Remote assessment");
        return assessment;
    }

    private Criteria criteria(UUID companyId) {
        Criteria criteria = new Criteria();
        criteria.setId(UUID.randomUUID());
        criteria.setTenantId(TENANT);
        criteria.setCompanyId(companyId);
        criteria.setCode("QC-REMOTE");
        criteria.setName("Remote criteria");
        criteria.setStatus("ACTIVE");
        criteria.setIsActive(true);
        criteria.setMaxScore(BigDecimal.TEN);
        criteria.setWeight(BigDecimal.ONE);
        return criteria;
    }

    private Company company(UUID companyId) {
        Company company = new Company();
        company.setId(companyId);
        company.setTenantId(TENANT);
        company.setCompanyId(companyId);
        company.setName("Company");
        return company;
    }

    private Supplier supplier(UUID companyId) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-REMOTE");
        supplier.setName("Supplier");
        return supplier;
    }
}
