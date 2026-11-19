package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AttachmentRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierAccreditationEvidencePolicyTest {
    @Mock private AttachmentRepository attachmentRepository;

    private SupplierAccreditationEvidencePolicy policy;
    private SupplierAccreditationDocument document;
    private Attachment attachment;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("evidence-policy-test");
        policy = new SupplierAccreditationEvidencePolicy(attachmentRepository);
        document = new SupplierAccreditationDocument();
        document.setId(UUID.randomUUID());
        document.setTenantId(TenantContext.currentTenant());
        document.setCompanyId(UUID.randomUUID());
        document.setAttachmentId(UUID.randomUUID());
        document.setStatus("VERIFIED");
        document.setValidFrom(LocalDate.now().minusDays(1));
        document.setValidTo(LocalDate.now().plusDays(1));

        attachment = new Attachment();
        attachment.setId(document.getAttachmentId());
        attachment.setTenantId(document.getTenantId());
        attachment.setCompanyId(document.getCompanyId());
        attachment.setStatus("ACTIVE");
        attachment.setEntityType(SupplierAccreditationEvidencePolicy.ATTACHMENT_ENTITY_TYPE);
        attachment.setEntityId(document.getId());
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            document.getTenantId(), document.getCompanyId(), document.getAttachmentId()))
            .thenReturn(Optional.of(attachment));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void acceptsOnlyCurrentVerifiedEvidenceWithAnActiveExactAttachmentLink() {
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isTrue();

        attachment.setStatus("INACTIVE");
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        attachment.setStatus("ACTIVE");

        attachment.setEntityType("SUPPLIER_ACCREDITATION_APPLICATION");
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        attachment.setEntityType(SupplierAccreditationEvidencePolicy.ATTACHMENT_ENTITY_TYPE);

        attachment.setEntityId(UUID.randomUUID());
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        attachment.setEntityId(document.getId());

        attachment.setCompanyId(UUID.randomUUID());
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        attachment.setCompanyId(document.getCompanyId());

        attachment.setTenantId("other-tenant");
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        attachment.setTenantId(document.getTenantId());

        document.setValidFrom(LocalDate.now().plusDays(1));
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        document.setValidFrom(LocalDate.now().minusDays(2));
        document.setValidTo(LocalDate.now().minusDays(1));
        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
    }

    @Test
    void missingOrSoftDeletedAttachmentFailsClosed() {
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            document.getTenantId(), document.getCompanyId(), document.getAttachmentId()))
            .thenReturn(Optional.empty());

        assertThat(policy.isCurrentVerifiedEvidence(document, LocalDate.now())).isFalse();
        assertThatThrownBy(() -> policy.requireActiveLinkedAttachment(document))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("linked attachment");
    }
}
