package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenderSupplierAccessServiceTest {
    private static final String TENANT = "tenant-access";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000005901");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 18);

    @Mock private RfqSupplierInvitationRepository invitationRepository;

    private TenderSupplierAccessService service;

    @BeforeEach
    void setUp() {
        service = new TenderSupplierAccessService(
            invitationRepository,
            Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void activeSupplierCanParticipateInOpenTenderWithoutInvitation() {
        RFQ rfq = rfq("OPEN_TENDER");
        Supplier supplier = supplier("ACTIVE");

        assertThat(service.canViewTender(rfq, supplier)).isTrue();
        assertThat(service.activeInvitation(rfq, supplier)).isEmpty();
    }

    @Test
    void activeSupplierCanParticipateInOpenCompetitionWithoutInvitation() {
        RFQ rfq = rfq("OPEN_COMPETITION");
        Supplier supplier = supplier("ACTIVE");

        assertThat(service.canViewTender(rfq, supplier)).isTrue();
        assertThat(service.activeInvitation(rfq, supplier)).isEmpty();
    }

    @Test
    void legacyLimitedSupplierCanViewOpenTenderWithoutInvitation() {
        RFQ rfq = rfq("OPEN_TENDER");
        Supplier supplier = supplier("LIMITED");

        assertThat(service.canViewTender(rfq, supplier)).isTrue();
        assertThat(service.activeInvitation(rfq, supplier)).isEmpty();
    }

    @Test
    void closedTenderRequiresActiveInvitation() {
        RFQ rfq = rfq("CLOSED_TENDER");
        Supplier supplier = supplier("ACTIVE");

        assertThat(service.canViewTender(rfq, supplier)).isFalse();
        assertThatThrownBy(() -> service.validateBidSubmission(rfq, supplier))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("invitation");
    }

    @Test
    void defaultRfqStillRequiresActiveInvitation() {
        RFQ rfq = rfq("REQUEST_FOR_QUOTATION");
        Supplier supplier = supplier("ACTIVE");
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setId(UUID.randomUUID());
        invitation.setSupplierId(supplier.getId());
        when(invitationRepository.findActiveInvitation(TENANT, COMPANY_ID, rfq.getId(), supplier.getId()))
            .thenReturn(Optional.of(invitation));

        assertThat(service.canViewTender(rfq, supplier)).isTrue();
        assertThat(service.activeInvitation(rfq, supplier)).contains(invitation);
    }

    @Test
    void openTenderRejectsInactiveSupplierAndExpiredDeadline() {
        RFQ rfq = rfq("OPEN_TENDER");
        Supplier supplier = supplier("INACTIVE");

        assertThat(service.canViewTender(rfq, supplier)).isFalse();
        assertThatThrownBy(() -> service.validateViewTender(rfq, supplier))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active");

        supplier.setStatus("ACTIVE");
        rfq.setDueDate(TODAY.minusDays(1));
        assertThatThrownBy(() -> service.validateBidSubmission(rfq, supplier))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("deadline");
    }

    private RFQ rfq(String tenderProcedureType) {
        RFQ rfq = new RFQ();
        rfq.setId(UUID.randomUUID());
        rfq.setTenantId(TENANT);
        rfq.setCompanyId(COMPANY_ID);
        rfq.setStatus("OPEN");
        rfq.setDueDate(TODAY.plusDays(5));
        rfq.setTenderProcedureType(tenderProcedureType);
        rfq.setPurpose("PROCUREMENT");
        rfq.setActivePublishedVersionId(UUID.randomUUID());
        return rfq;
    }

    private Supplier supplier(String status) {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(COMPANY_ID);
        supplier.setStatus(status);
        return supplier;
    }
}
