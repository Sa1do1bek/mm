package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqResponse;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.SupplierPortalAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class RfqPublicationTransactionTest {
    private static final String TENANT = "tenant-rfq-publication-transaction";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000009901");

    @Mock private RFQRepository rfqRepository;
    @Mock private RfqPublishedVersionRepository versionRepository;
    @Mock private RfqSupplierInvitationRepository invitationRepository;
    @Mock private RfqInvitationDeliveryRepository deliveryRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RfqPublicationValidator validator;
    @Mock private RfqArtifactStagingService stagingService;
    @Mock private SupplierPortalAccountService portalAccountService;
    @Mock private AuditService auditService;
    @Mock private ObjectProvider<RfqDeliveryService> deliveryServiceProvider;

    private RfqPublicationTransaction transaction;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        transaction = new RfqPublicationTransaction(
            rfqRepository,
            versionRepository,
            invitationRepository,
            deliveryRepository,
            supplierRepository,
            validator,
            stagingService,
            portalAccountService,
            auditService,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC),
            deliveryServiceProvider
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void openCompetitionSkipsSupplierWhosePortalAccountConflictsAndQueuesEligibleSupplier() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = rfq(rfqId, "OPEN_COMPETITION");
        Supplier conflicted = supplier("Conflict supplier", "conflict@supplier.test");
        Supplier eligible = supplier("Eligible supplier", "eligible@supplier.test");
        RfqSupplierInvitation conflictedInvitation = invitation(rfqId, conflicted);
        RfqSupplierInvitation eligibleInvitation = invitation(rfqId, eligible);
        stubPublication(rfq, List.of(conflicted, eligible), List.of(conflictedInvitation, eligibleInvitation));
        when(portalAccountService.ensureForRfqInvitation(conflicted.getId(), conflicted.getEmail()))
            .thenThrow(new BusinessException("Supplier portal account conflicts with the RFQ recipient email"));
        when(portalAccountService.ensureForRfqInvitation(eligible.getId(), eligible.getEmail()))
            .thenReturn(new SupplierPortalAccount());

        PublishRfqResponse response = transaction.commit(candidate(rfqId, snapshot(rfqId)));

        ArgumentCaptor<List<RfqInvitationDelivery>> deliveries = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(deliveries.capture());
        assertThat(deliveries.getValue()).hasSize(1);
        assertThat(deliveries.getValue().get(0).getSupplierId()).isEqualTo(eligible.getId());
        assertThat(response.queuedDeliveryCount()).isEqualTo(1);
    }

    @Test
    void openCompetitionBlocksPublicationWhenNoSupplierCanReceiveLetter() {
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = rfq(rfqId, "OPEN_COMPETITION");
        Supplier conflicted = supplier("Conflict supplier", "conflict@supplier.test");
        RfqSupplierInvitation invitation = invitation(rfqId, conflicted);
        stubPublication(rfq, List.of(conflicted), List.of(invitation));
        when(portalAccountService.ensureForRfqInvitation(conflicted.getId(), conflicted.getEmail()))
            .thenThrow(new BusinessException("Supplier portal account conflicts with the RFQ recipient email"));

        assertThatThrownBy(() -> transaction.commit(candidate(rfqId, snapshot(rfqId))))
            .isInstanceOf(RfqPublicationValidationException.class)
            .satisfies(exception -> assertThat(((RfqPublicationValidationException) exception).getIssues())
                .extracting(issue -> issue.code())
                .containsExactly("RFQ_NO_ELIGIBLE_SUPPLIERS"));
        verify(deliveryRepository, never()).saveAll(any());
    }

    private void stubPublication(RFQ rfq, List<Supplier> candidates, List<RfqSupplierInvitation> invitations) {
        when(rfqRepository.lockScopedById(TENANT, COMPANY_ID, rfq.getId())).thenReturn(Optional.of(rfq));
        when(versionRepository.countByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalse(TENANT, COMPANY_ID, rfq.getId()))
            .thenReturn(0L);
        when(versionRepository.saveAndFlush(any(RfqPublishedVersion.class))).thenAnswer(invocation -> {
            RfqPublishedVersion version = invocation.getArgument(0);
            version.setId(UUID.randomUUID());
            return version;
        });
        when(versionRepository.save(any(RfqPublishedVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stagingService.promote(any(), any())).thenReturn(new RfqArtifactStagingService.PromotedPackage(
            UUID.randomUUID(),
            UUID.randomUUID()
        ));
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TENANT, COMPANY_ID, rfq.getId()
        )).thenReturn(List.of());
        when(supplierRepository.findActiveRfqSupplierCandidates(TENANT, COMPANY_ID)).thenReturn(candidates);
        when(invitationRepository.saveAll(any())).thenReturn(invitations);
        when(supplierRepository.findAllById(any())).thenReturn(candidates);
    }

    private RFQ rfq(UUID id, String tenderProcedureType) {
        RFQ rfq = new RFQ();
        rfq.setId(id);
        rfq.setTenantId(TENANT);
        rfq.setCompanyId(COMPANY_ID);
        rfq.setVersion(3L);
        rfq.setCode("RFQ-OPEN-1");
        rfq.setName("Open RFQ");
        rfq.setStatus("DRAFT");
        rfq.setPurpose("PROCUREMENT");
        rfq.setTenderProcedureType(tenderProcedureType);
        rfq.setPackageLanguage("ru");
        rfq.setDueDate(LocalDate.of(2026, 7, 25));
        return rfq;
    }

    private Supplier supplier(String name, String email) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(COMPANY_ID);
        supplier.setName(name);
        supplier.setLegalName(name + " LLC");
        supplier.setEmail(email);
        return supplier;
    }

    private RfqSupplierInvitation invitation(UUID rfqId, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setId(UUID.randomUUID());
        invitation.setTenantId(TENANT);
        invitation.setCompanyId(COMPANY_ID);
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfqId);
        invitation.setSupplierId(supplier.getId());
        invitation.setSupplier(supplier);
        return invitation;
    }

    private RfqPackageSnapshot snapshot(UUID rfqId) {
        return new RfqPackageSnapshot(
            rfqId,
            "RFQ-OPEN-1",
            "Open RFQ",
            "Description",
            "ru",
            LocalDate.of(2026, 7, 25),
            null,
            "Reply in the portal",
            List.of(new RfqPackageSnapshot.Position(UUID.randomUUID(), "MAT-1", "Material", null, "pcs", java.math.BigDecimal.ONE, LocalDate.of(2026, 7, 25))),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private RfqPublicationService.PublicationCandidate candidate(UUID rfqId, RfqPackageSnapshot snapshot) {
        Path directory = Path.of("target", "rfq-publication-transaction-test");
        return new RfqPublicationService.PublicationCandidate(
            rfqId,
            3L,
            snapshot,
            new RfqArtifactStagingService.StagedPackage(
                directory,
                directory.resolve("request.pdf"),
                directory.resolve("quotation.xlsx"),
                "hash",
                "RFQ-OPEN-1"
            )
        );
    }
}
