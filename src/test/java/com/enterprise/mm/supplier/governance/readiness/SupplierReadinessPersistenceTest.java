package com.enterprise.mm.supplier.governance.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierAccreditationEvidenceRequirement;
import com.enterprise.mm.entity.SupplierActivationRecord;
import com.enterprise.mm.entity.SupplierEvidenceRequirement;
import com.enterprise.mm.entity.SupplierEvidenceTemplateVersion;
import com.enterprise.mm.repository.SupplierAccreditationEvidenceRequirementRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.repository.SupplierEvidenceRequirementRepository;
import com.enterprise.mm.repository.SupplierEvidenceTemplateVersionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class SupplierReadinessPersistenceTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY_ID = UUID.fromString("0a32af53-d316-4204-80c7-964fed4c6236");

    @Autowired SupplierEvidenceTemplateVersionRepository templateRepository;
    @Autowired SupplierEvidenceRequirementRepository requirementRepository;
    @Autowired SupplierAccreditationEvidenceRequirementRepository snapshotRepository;
    @Autowired SupplierActivationRecordRepository activationRepository;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void storesVersionedChecklistAndImmutableActivationSnapshot() {
        SupplierEvidenceTemplateVersion template = new SupplierEvidenceTemplateVersion();
        template.setTenantId(TENANT);
        template.setCompanyId(COMPANY_ID);
        template.setCode("GOODS-V1");
        template.setName("GOODS evidence version 1");
        template.setStatus("ACTIVE");
        template.setAccreditationType("GOODS");
        template.setTemplateVersion(1);
        template.setEffectiveFrom(LocalDate.of(2026, 7, 23));
        template = templateRepository.saveAndFlush(template);

        SupplierEvidenceRequirement requirement = new SupplierEvidenceRequirement();
        requirement.setTenantId(TENANT);
        requirement.setCompanyId(COMPANY_ID);
        requirement.setCode("QUALITY_CERTIFICATE");
        requirement.setName("Quality certificate");
        requirement.setStatus("ACTIVE");
        requirement.setTemplateVersionId(template.getId());
        requirement.setRequirementCode("QUALITY_CERTIFICATE");
        requirement.setDocumentType("QUALITY_CERTIFICATE");
        requirement.setMandatory(true);
        requirement.setReusable(false);
        requirement.setValidityRequired(true);
        requirement.setOwnerRole("SUPPLIER_MANAGER");
        requirement.setSlaDays(5);
        requirement.setDisplayOrder(40);
        requirementRepository.saveAndFlush(requirement);

        UUID applicationId = UUID.randomUUID();
        SupplierAccreditationEvidenceRequirement snapshot = new SupplierAccreditationEvidenceRequirement();
        snapshot.setTenantId(TENANT);
        snapshot.setCompanyId(COMPANY_ID);
        snapshot.setCode("APP-QUALITY_CERTIFICATE");
        snapshot.setName("Quality certificate");
        snapshot.setStatus("ACTIVE");
        snapshot.setApplicationId(applicationId);
        snapshot.setTemplateVersionId(template.getId());
        snapshot.setRequirementCode("QUALITY_CERTIFICATE");
        snapshot.setDocumentType("QUALITY_CERTIFICATE");
        snapshot.setMandatory(true);
        snapshot.setReusable(false);
        snapshot.setValidityRequired(true);
        snapshot.setOwnerRole("SUPPLIER_MANAGER");
        snapshot.setDueDate(LocalDate.of(2026, 7, 28));
        snapshot.setDisplayOrder(40);
        snapshotRepository.saveAndFlush(snapshot);

        UUID supplierId = UUID.randomUUID();
        SupplierActivationRecord activation = new SupplierActivationRecord();
        activation.setTenantId(TENANT);
        activation.setCompanyId(COMPANY_ID);
        activation.setCode("ACT-SUPPLIER-1");
        activation.setName("Supplier activation");
        activation.setStatus("RECORDED");
        activation.setSupplierId(supplierId);
        activation.setActivatedBy("supplier.manager");
        activation.setActivatedAt(LocalDateTime.of(2026, 7, 23, 10, 30));
        activation.setActivationComment("Final review complete");
        activation.setReadinessSnapshotJson("{\"overallStatus\":\"READY_TO_ACTIVATE\"}");
        activation.setPolicyVersion("supplier-readiness-v1");
        activationRepository.saveAndFlush(activation);

        assertThat(requirementRepository
            .findByTenantIdAndCompanyIdAndTemplateVersionIdAndIsDeletedFalseOrderByDisplayOrderAsc(
                TENANT, COMPANY_ID, template.getId()))
            .extracting(SupplierEvidenceRequirement::getRequirementCode)
            .containsExactly("QUALITY_CERTIFICATE");
        assertThat(snapshotRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByDisplayOrderAsc(
                TENANT, COMPANY_ID, applicationId))
            .extracting(SupplierAccreditationEvidenceRequirement::getRequirementCode)
            .containsExactly("QUALITY_CERTIFICATE");
        assertThat(activationRepository
            .existsByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalse(
                TENANT, COMPANY_ID, supplierId, "RECORDED"))
            .isTrue();
    }

    @TestConfiguration
    static class AuditTestConfig {
        @Bean
        AuditorAware<String> auditorProvider() {
            return () -> Optional.of("supplier-readiness-test");
        }
    }
}
