package com.enterprise.mm.supplier.governance.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierAccreditationEvidenceRequirement;
import com.enterprise.mm.entity.SupplierEvidenceRequirement;
import com.enterprise.mm.entity.SupplierEvidenceTemplateVersion;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierAccreditationEvidenceRequirementRepository;
import com.enterprise.mm.repository.SupplierEvidenceRequirementRepository;
import com.enterprise.mm.repository.SupplierEvidenceTemplateVersionRepository;
import com.enterprise.mm.service.SupplierAccreditationEvidencePolicy;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierEvidenceChecklistServiceTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("5ee3919b-cfa7-42bb-88d6-234a77c67d69");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);

    @Mock SupplierEvidenceTemplateVersionRepository templateRepository;
    @Mock SupplierEvidenceRequirementRepository requirementRepository;
    @Mock SupplierAccreditationEvidenceRequirementRepository snapshotRepository;
    @Mock SupplierAccreditationDocumentRepository documentRepository;
    @Mock SupplierAccreditationEvidencePolicy evidencePolicy;

    private SupplierEvidenceChecklistService service;

    @BeforeEach
    void setUp() {
        service = new SupplierEvidenceChecklistService(
            templateRepository,
            requirementRepository,
            snapshotRepository,
            documentRepository,
            evidencePolicy
        );
    }

    @Test
    void reportsMissingEvidence() {
        SupplierAccreditationApplication application = application("GOODS");
        SupplierAccreditationEvidenceRequirement requirement =
            snapshot("QUALITY_CERTIFICATE", true, false);
        checklist(application, requirement, List.of());

        assertThat(service.checklist(application, TODAY))
            .singleElement()
            .extracting(SupplierEvidenceChecklistItem::state)
            .isEqualTo(EvidenceChecklistItemState.MISSING);
    }

    @Test
    void reportsPendingVerifiedRejectedAndExpiredEvidence() {
        assertThat(stateFor(document("PENDING_VERIFICATION", TODAY.plusDays(30)), false))
            .isEqualTo(EvidenceChecklistItemState.UNDER_VERIFICATION);
        assertThat(stateFor(document("REJECTED", TODAY.plusDays(30)), false))
            .isEqualTo(EvidenceChecklistItemState.REJECTED);
        assertThat(stateFor(document("VERIFIED", TODAY.minusDays(1)), true))
            .isEqualTo(EvidenceChecklistItemState.EXPIRED);

        SupplierAccreditationDocument verified = document("VERIFIED", TODAY.plusDays(30));
        when(evidencePolicy.isCurrentVerifiedEvidence(verified, TODAY)).thenReturn(true);
        assertThat(stateFor(verified, true)).isEqualTo(EvidenceChecklistItemState.VERIFIED);
    }

    @Test
    void matchesLegacyUploadedDocumentTypeAliasesToChecklistRequirements() {
        SupplierAccreditationApplication application = application("GOODS");
        SupplierAccreditationEvidenceRequirement requirement =
            snapshot("TAX_STATUS", false, false);
        SupplierAccreditationDocument verified = document("VERIFIED", null);
        verified.setDocumentType("TAX_CERTIFICATE");
        checklist(application, requirement, List.of(verified));
        when(evidencePolicy.isCurrentVerifiedEvidence(verified, TODAY)).thenReturn(true);

        assertThat(service.checklist(application, TODAY))
            .singleElement()
            .extracting(SupplierEvidenceChecklistItem::state)
            .isEqualTo(EvidenceChecklistItemState.VERIFIED);
    }

    @Test
    void legacyVerifiedEvidenceExpiresAfterMigrationGracePeriod() {
        SupplierAccreditationApplication application = application("GOODS");
        SupplierAccreditationEvidenceRequirement requirement =
            snapshot("LEGACY_VERIFIED_EVIDENCE", false, false);
        requirement.setDueDate(TODAY.minusDays(1));
        SupplierAccreditationDocument verified = document("VERIFIED", null);
        verified.setDocumentType("LEGACY_VERIFIED_EVIDENCE");
        checklist(application, requirement, List.of(verified));

        assertThat(service.checklist(application, TODAY))
            .singleElement()
            .extracting(SupplierEvidenceChecklistItem::state)
            .isEqualTo(EvidenceChecklistItemState.EXPIRED);
    }

    @Test
    void snapshotsTheTemplateOnlyOnce() {
        SupplierAccreditationApplication application = application("GOODS");
        when(snapshotRepository.existsByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalse(
            TENANT, COMPANY, application.getId())).thenReturn(true);

        service.snapshotFor(application);

        verifyNoInteractions(templateRepository, requirementRepository);
        verify(snapshotRepository, never()).saveAll(any());
    }

    @Test
    void seedsCompanyTemplateAndCopiesOrderedRequirements() {
        SupplierAccreditationApplication application = application("SERVICE");
        when(snapshotRepository.existsByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalse(
            TENANT, COMPANY, application.getId())).thenReturn(false);
        when(templateRepository
            .findFirstByTenantIdAndCompanyIdAndAccreditationTypeAndStatusAndIsDeletedFalseOrderByTemplateVersionDesc(
                TENANT, COMPANY, "SERVICES", "ACTIVE"))
            .thenReturn(Optional.empty());
        when(templateRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SupplierEvidenceTemplateVersion template = invocation.getArgument(0);
            template.setId(UUID.randomUUID());
            return template;
        });
        when(requirementRepository.saveAll(any())).thenAnswer(invocation -> {
            List<SupplierEvidenceRequirement> requirements = invocation.getArgument(0);
            return requirements;
        });
        SupplierEvidenceRequirement definition = new SupplierEvidenceRequirement();
        definition.setRequirementCode("REGISTRATION");
        definition.setDocumentType("REGISTRATION");
        definition.setName("Registration");
        definition.setMandatory(true);
        definition.setReusable(true);
        definition.setValidityRequired(false);
        definition.setOwnerRole("SUPPLIER_MANAGER");
        definition.setSlaDays(5);
        definition.setDisplayOrder(10);
        when(requirementRepository
            .findByTenantIdAndCompanyIdAndTemplateVersionIdAndIsDeletedFalseOrderByDisplayOrderAsc(
                any(), any(), any()))
            .thenReturn(List.of(definition));

        service.snapshotFor(application);

        verify(templateRepository).saveAndFlush(any(SupplierEvidenceTemplateVersion.class));
        verify(requirementRepository).saveAll(any());
        verify(snapshotRepository).saveAll(any());
    }

    @Test
    void defaultsContainExactRequirementsForEachScopeType() {
        assertThat(codes("GOODS")).containsExactly(
            "REGISTRATION", "TAX_STATUS", "BANK_CONFIRMATION",
            "QUALITY_CERTIFICATE", "FINANCIAL_CAPABILITY", "TECHNICAL_CAPABILITY");
        assertThat(codes("SERVICES")).containsExactly(
            "REGISTRATION", "TAX_STATUS", "BANK_CONFIRMATION",
            "SERVICE_LICENSE", "STAFF_CAPABILITY", "INSURANCE");
        assertThat(codes("WORKS")).containsExactly(
            "REGISTRATION", "TAX_STATUS", "BANK_CONFIRMATION",
            "WORKS_LICENSE", "SAFETY_CERTIFICATE", "EQUIPMENT_CAPABILITY");
    }

    private EvidenceChecklistItemState stateFor(
        SupplierAccreditationDocument document,
        boolean validityRequired
    ) {
        SupplierAccreditationApplication application = application("GOODS");
        SupplierAccreditationEvidenceRequirement requirement =
            snapshot("QUALITY_CERTIFICATE", validityRequired, false);
        checklist(application, requirement, List.of(document));
        return service.checklist(application, TODAY).getFirst().state();
    }

    private void checklist(
        SupplierAccreditationApplication application,
        SupplierAccreditationEvidenceRequirement requirement,
        List<SupplierAccreditationDocument> documents
    ) {
        when(snapshotRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByDisplayOrderAsc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(List.of(requirement));
        when(documentRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TENANT, COMPANY, application.getId()))
            .thenReturn(documents);
    }

    private SupplierAccreditationApplication application(String type) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setId(UUID.randomUUID());
        application.setTenantId(TENANT);
        application.setCompanyId(COMPANY);
        application.setSupplierId(UUID.randomUUID());
        application.setCode("ACC-1");
        application.setAccreditationType(type);
        application.setSubmittedAt(LocalDateTime.of(2026, 7, 20, 10, 0));
        return application;
    }

    private SupplierAccreditationEvidenceRequirement snapshot(
        String documentType,
        boolean validityRequired,
        boolean reusable
    ) {
        SupplierAccreditationEvidenceRequirement snapshot =
            new SupplierAccreditationEvidenceRequirement();
        snapshot.setId(UUID.randomUUID());
        snapshot.setRequirementCode(documentType);
        snapshot.setDocumentType(documentType);
        snapshot.setMandatory(true);
        snapshot.setReusable(reusable);
        snapshot.setValidityRequired(validityRequired);
        snapshot.setOwnerRole("SUPPLIER_MANAGER");
        snapshot.setDueDate(TODAY.plusDays(5));
        return snapshot;
    }

    private SupplierAccreditationDocument document(String status, LocalDate validTo) {
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setId(UUID.randomUUID());
        document.setDocumentType("QUALITY_CERTIFICATE");
        document.setStatus(status);
        document.setValidTo(validTo);
        document.setCreatedAt(LocalDateTime.now());
        return document;
    }

    private List<String> codes(String type) {
        return SupplierEvidenceRequirementDefaults.forType(type).stream()
            .map(SupplierEvidenceRequirementDefaults.Definition::requirementCode)
            .toList();
    }
}
