package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.MaterialBusinessTypePolicy;
import com.enterprise.mm.service.MaterialWmsIntegrationPublisher;
import com.enterprise.mm.service.MxikReconciliationService;
import com.enterprise.mm.service.MxikReconciliationService.Assignment;
import com.enterprise.mm.service.MxikReconciliationService.AssignmentRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MxikReconciliationServiceTest {
    @Mock MaterialRepository materials;
    @Mock MXIKRepository mxiks;
    @Mock AuditService audit;
    @Mock MaterialWmsIntegrationPublisher publisher;
    @Mock AiAnalysisJobRepository aiJobs;
    MxikReconciliationService service;
    UUID companyId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-test");
        CompanyContext.setCompany(companyId);
        service = new MxikReconciliationService(materials, mxiks, new MaterialBusinessTypePolicy(), audit, publisher, aiJobs, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void assignsCatalogCodeAndAudits() {
        Material material = material(2L);
        MXIK mxik = mxik("123456789", "GOODS");
        when(materials.lockForMxikReconciliation("tenant-test", companyId, materialId)).thenReturn(Optional.of(material));
        when(mxiks.findByKod("123456789")).thenReturn(Optional.of(mxik));
        when(materials.saveAndFlush(material)).thenReturn(material);

        var result = service.assign(new AssignmentRequest(List.of(new Assignment(materialId, 2L, "123456789"))));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().mxikCode()).isEqualTo("123456789");
        verify(publisher).publish(material);
        verify(audit).logInCurrentTransaction(org.mockito.ArgumentMatchers.eq("Material"),
            org.mockito.ArgumentMatchers.eq(materialId), org.mockito.ArgumentMatchers.eq("MXIK_ASSIGN_MANUAL"),
            any(), any(), any());
    }

    @Test
    void rejectsStaleVersionBeforeWriting() {
        when(materials.lockForMxikReconciliation("tenant-test", companyId, materialId))
            .thenReturn(Optional.of(material(3L)));
        assertThatThrownBy(() -> service.assign(new AssignmentRequest(List.of(
            new Assignment(materialId, 2L, "123456789")))))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("changed since review");
        verify(materials, never()).saveAndFlush(any());
    }

    @Test
    void rejectsWrongMxikType() {
        Material material = material(2L);
        when(materials.lockForMxikReconciliation("tenant-test", companyId, materialId)).thenReturn(Optional.of(material));
        when(mxiks.findByKod("123456789")).thenReturn(Optional.of(mxik("123456789", "SERVICE")));
        assertThatThrownBy(() -> service.assign(new AssignmentRequest(List.of(
            new Assignment(materialId, 2L, "123456789")))))
            .hasMessageContaining("MATERIAL_TYPE_MXIK_MISMATCH");
        verify(materials, never()).saveAndFlush(any());
    }

    @Test
    void rejectsAiApprovalWhenRecommendationWasComputedForOlderVersion() {
        Material material = material(5L);
        UUID jobId = UUID.randomUUID();
        AiAnalysisJob job = new AiAnalysisJob();
        job.setAnalysisType(AiAnalysisType.MATERIAL_MXIK_MATCH);
        job.setEntityType("MATERIAL");
        job.setEntityId(materialId);
        job.setStatus(AiAnalysisJobStatus.COMPLETED);
        job.setResultPayload("{\"materialId\":\"" + materialId + "\",\"materialVersion\":4,\"status\":\"RECOMMENDED\",\"recommendedMxik\":{\"code\":\"123456789\",\"name\":\"Test\"}}");
        when(materials.lockForMxikReconciliation("tenant-test", companyId, materialId)).thenReturn(Optional.of(material));
        when(mxiks.findByKod("123456789")).thenReturn(Optional.of(mxik("123456789", "GOODS")));
        when(aiJobs.findScoped(jobId, "tenant-test", companyId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.assign(new AssignmentRequest(List.of(
            new Assignment(materialId, 5L, "123456789", jobId)))))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("stale");
        verify(materials, never()).saveAndFlush(any());
    }

    @Test
    void showsRelatedMxikExamplesWithClosestNamesFirst() {
        UUID groupId = UUID.randomUUID();
        Material source = material(1L);
        source.setName("Sodium hydroxide flakes");
        source.setMaterialGroupId(groupId);
        Material generic = material(1L);
        generic.setId(UUID.randomUUID());
        generic.setName("Industrial chemical");
        generic.setMxikCode("111");
        Material close = material(1L);
        close.setId(UUID.randomUUID());
        close.setName("Sodium hydroxide pellets");
        close.setMxikCode("222");
        when(materials.findByTenantIdAndIdAndIsDeletedFalse("tenant-test", materialId))
            .thenReturn(Optional.of(source));
        when(materials.findMxikPeers(org.mockito.ArgumentMatchers.eq("tenant-test"),
            org.mockito.ArgumentMatchers.eq(companyId), org.mockito.ArgumentMatchers.eq(materialId),
            org.mockito.ArgumentMatchers.eq(groupId), any(Pageable.class)))
            .thenReturn(List.of(generic, close));

        assertThat(service.sameGroupWithMxik(materialId)).extracting("mxikCode")
            .containsExactly("222", "111");
    }

    private Material material(Long version) {
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId("tenant-test");
        material.setCompanyId(companyId);
        material.setName("Pump");
        material.setCode("P-1");
        material.setMaterialType(MaterialType.TMB);
        material.setVersion(version);
        return material;
    }

    private MXIK mxik(String code, String type) {
        MXIK mxik = new MXIK();
        mxik.setKod(code);
        mxik.setName("Pump goods");
        mxik.setType(type);
        return mxik;
    }
}
