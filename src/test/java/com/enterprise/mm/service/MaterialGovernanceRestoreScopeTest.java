package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialAttributeTemplate;
import com.enterprise.mm.entity.MaterialDuplicateCandidate;
import com.enterprise.mm.entity.MaterialMasterRequest;
import com.enterprise.mm.entity.MaterialQualityIssue;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialAttributeTemplateMapper;
import com.enterprise.mm.mapper.MaterialDuplicateCandidateMapper;
import com.enterprise.mm.mapper.MaterialMasterRequestMapper;
import com.enterprise.mm.mapper.MaterialQualityIssueMapper;
import com.enterprise.mm.repository.MaterialAttributeTemplateRepository;
import com.enterprise.mm.repository.MaterialDuplicateCandidateRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialMasterRequestRepository;
import com.enterprise.mm.repository.MaterialQualityIssueRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialGovernanceRestoreScopeTest {
    @Mock private MaterialMasterRequestRepository masterRepository;
    @Mock private MaterialDuplicateCandidateRepository duplicateRepository;
    @Mock private MaterialQualityIssueRepository qualityRepository;
    @Mock private MaterialAttributeTemplateRepository templateRepository;
    @Mock private MaterialCatalogGuard catalogGuard;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock private AuditService auditService;
    @Mock private MaterialService materialService;

    private MaterialMasterRequestService masterService;
    private MaterialDuplicateCandidateService duplicateService;
    private MaterialQualityIssueService qualityService;
    private MaterialAttributeTemplateService templateService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("material-governance-restore-test");
        CompanyContext.setCompany(UUID.randomUUID());
        masterService = new MaterialMasterRequestService(
            masterRepository, new MaterialMasterRequestMapper(), auditService,
            catalogGuard, materialGroupRepository, unitOfMeasureRepository, materialService);
        duplicateService = new MaterialDuplicateCandidateService(
            duplicateRepository, new MaterialDuplicateCandidateMapper(), catalogGuard, auditService,
            materialService);
        qualityService = new MaterialQualityIssueService(
            qualityRepository, new MaterialQualityIssueMapper(), catalogGuard, auditService);
        templateService = new MaterialAttributeTemplateService(
            templateRepository, new MaterialAttributeTemplateMapper(), materialGroupRepository, auditService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void everyRestoreRejectsOtherTenantBeforeMutation() {
        MaterialMasterRequest master = otherTenant(new MaterialMasterRequest());
        MaterialDuplicateCandidate duplicate = otherTenant(new MaterialDuplicateCandidate());
        MaterialQualityIssue quality = otherTenant(new MaterialQualityIssue());
        MaterialAttributeTemplate template = otherTenant(new MaterialAttributeTemplate());
        when(masterRepository.findAnyById(master.getId())).thenReturn(Optional.of(master));
        when(duplicateRepository.findAnyById(duplicate.getId())).thenReturn(Optional.of(duplicate));
        when(qualityRepository.findAnyById(quality.getId())).thenReturn(Optional.of(quality));
        when(templateRepository.findAnyById(template.getId())).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> masterService.restore(master.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> duplicateService.restore(duplicate.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> qualityService.restore(quality.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> templateService.restore(template.getId())).isInstanceOf(NotFoundException.class);

        verify(masterRepository, never()).save(master);
        verify(duplicateRepository, never()).save(duplicate);
        verify(qualityRepository, never()).save(quality);
        verify(templateRepository, never()).save(template);
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T otherTenant(T entity) {
        entity.setId(UUID.randomUUID());
        entity.setTenantId("other-tenant");
        entity.setCompanyId(CompanyContext.currentCompany());
        entity.setDeleted(true);
        return entity;
    }
}
