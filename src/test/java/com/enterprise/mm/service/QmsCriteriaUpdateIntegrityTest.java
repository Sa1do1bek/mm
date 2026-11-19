package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Criteria;
import com.enterprise.mm.payload.QmsCriteriaDtos.CriteriaUpdateRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.CriteriaRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QmsCriteriaUpdateIntegrityTest {
    @Mock private CriteriaRepository repository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AuditService auditService;

    private QmsCriteriaService service;
    private UUID criteriaId;
    private Criteria criteria;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("qms-criteria-update");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        criteriaId = UUID.randomUUID();
        criteria = new Criteria();
        criteria.setId(criteriaId);
        criteria.setTenantId(TenantContext.currentTenant());
        criteria.setCompanyId(companyId);
        criteria.setCode("QC-00001");
        criteria.setName("Original name");
        criteria.setMaxScore(new BigDecimal("10.00"));
        criteria.setWeight(new BigDecimal("1.000"));
        criteria.setIsActive(true);
        criteria.setStatus("ACTIVE");

        service = new QmsCriteriaService(repository, companyRepository, auditService);
        when(repository.findByIdAndIsDeletedFalse(criteriaId)).thenReturn(Optional.of(criteria));
        when(repository.save(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void updatePreservesGeneratedCodeWhenReadOnlyUiOmitsIt() {
        CriteriaUpdateRequest request = new CriteriaUpdateRequest(
            null,
            "Updated name",
            "Updated description",
            new BigDecimal("25"),
            new BigDecimal("3"),
            true
        );

        var response = service.update(criteriaId, request);

        assertThat(response.code()).isEqualTo("QC-00001");
        assertThat(response.name()).isEqualTo("Updated name");
        assertThat(response.maxScore()).isEqualByComparingTo("25.00");
        assertThat(response.weight()).isEqualByComparingTo("3.000");
    }
}
