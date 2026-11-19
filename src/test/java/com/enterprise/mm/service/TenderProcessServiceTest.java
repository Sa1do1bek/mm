package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.TenderBidOpeningProtocol;
import com.enterprise.mm.entity.TenderCommission;
import com.enterprise.mm.entity.TenderCommissionMemberSetting;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.TenderBidOpeningProtocolMapper;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import com.enterprise.mm.repository.TenderBidOpeningLineRepository;
import com.enterprise.mm.repository.TenderBidOpeningProtocolRepository;
import com.enterprise.mm.repository.TenderCommissionMemberRepository;
import com.enterprise.mm.repository.TenderCommissionMemberSettingRepository;
import com.enterprise.mm.repository.TenderCommissionRepository;
import com.enterprise.mm.repository.TenderConflictCheckRepository;
import com.enterprise.mm.repository.TenderEvaluationProtocolRepository;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class TenderProcessServiceTest {
    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void selectionReadinessValidationDoesNotMarkReadTransactionRollbackOnlyForBusinessFailures() throws Exception {
        Method method = TenderProcessService.class.getMethod("validateReadyForSelection", RFQ.class, SupplierQuotation.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.noRollbackFor()).contains(BusinessException.class);
    }

    @Test
    void configuredTenderCommissionQuorumOverridesProcedureDefault() throws Exception {
        UUID rfqId = UUID.randomUUID();
        TenderCommissionRepository tenderCommissionRepository = mock(TenderCommissionRepository.class);
        TenderCommission tenderCommission = new TenderCommission();
        tenderCommission.setQuorumRequired(0);
        when(tenderCommissionRepository.findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            CompanyContext.DEFAULT_COMPANY_ID,
            rfqId
        )).thenReturn(List.of(tenderCommission));

        TenderProcessService service = new TenderProcessService(
            mock(RFQRepository.class),
            mock(RfqSupplierInvitationRepository.class),
            mock(SupplierQuotationRepository.class),
            mock(SupplierRelatedPartyRepository.class),
            tenderCommissionRepository,
            mock(TenderCommissionMemberSettingRepository.class),
            mock(TenderCommissionMemberRepository.class),
            mock(TenderBidOpeningProtocolRepository.class),
            mock(TenderBidOpeningLineRepository.class),
            mock(TenderConflictCheckRepository.class),
            mock(TenderEvaluationProtocolRepository.class),
            null,
            null,
            null,
            null
        );
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenderProcedureType("OPEN_TENDER");

        Method method = TenderProcessService.class.getDeclaredMethod("quorumRequired", RFQ.class);
        method.setAccessible(true);

        assertThat(method.invoke(service, rfq)).isEqualTo(0);
    }

    @Test
    void openBidsRejectsTenderBeforeResponseDeadline() {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID rfqId = UUID.randomUUID();
        RFQRepository rfqRepository = mock(RFQRepository.class);
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId(TenantContext.DEFAULT_TENANT);
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setStatus("OPEN");
        rfq.setDueDate(LocalDate.now().plusDays(1));
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));

        TenderProcessService service = new TenderProcessService(
            rfqRepository,
            mock(RfqSupplierInvitationRepository.class),
            mock(SupplierQuotationRepository.class),
            mock(SupplierRelatedPartyRepository.class),
            mock(TenderCommissionRepository.class),
            mock(TenderCommissionMemberSettingRepository.class),
            mock(TenderCommissionMemberRepository.class),
            mock(TenderBidOpeningProtocolRepository.class),
            mock(TenderBidOpeningLineRepository.class),
            mock(TenderConflictCheckRepository.class),
            mock(TenderEvaluationProtocolRepository.class),
            null,
            null,
            null,
            null
        );

        assertThatThrownBy(() -> service.openBids(rfqId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("deadline");
    }

    @Test
    void openBidsAllowsTenderBeforeDeadlineWhenExpectedQuotationsArrived() {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID rfqId = UUID.randomUUID();
        RFQRepository rfqRepository = mock(RFQRepository.class);
        SupplierQuotationRepository quotationRepository = mock(SupplierQuotationRepository.class);
        RfqSupplierInvitationRepository invitationRepository = mock(RfqSupplierInvitationRepository.class);
        TenderCommissionRepository tenderCommissionRepository = mock(TenderCommissionRepository.class);
        TenderCommissionMemberSettingRepository memberSettingRepository = mock(TenderCommissionMemberSettingRepository.class);
        TenderBidOpeningProtocolRepository openingProtocolRepository = mock(TenderBidOpeningProtocolRepository.class);
        TenderBidOpeningProtocolMapper openingProtocolMapper = mock(TenderBidOpeningProtocolMapper.class);
        RFQ rfq = new RFQ();
        rfq.setId(rfqId);
        rfq.setTenantId(TenantContext.DEFAULT_TENANT);
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setStatus("OPEN");
        rfq.setTenderProcedureType("OPEN_TENDER");
        rfq.setMinQuotationsRequired(2);
        rfq.setDueDate(LocalDate.now().plusDays(1));
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));
        when(invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.DEFAULT_TENANT,
            CompanyContext.DEFAULT_COMPANY_ID,
            rfqId
        )).thenReturn(List.of());
        when(quotationRepository.findCurrentSubmittedByRfq(
            TenantContext.DEFAULT_TENANT,
            CompanyContext.DEFAULT_COMPANY_ID,
            rfqId,
            List.of("ACTIVE", "SUBMITTED", "SELECTED", "REJECTED")
        )).thenReturn(List.of(quotation(UUID.randomUUID()), quotation(UUID.randomUUID())));
        TenderCommission commission = new TenderCommission();
        UUID commissionId = UUID.randomUUID();
        commission.setId(commissionId);
        commission.setQuorumRequired(1);
        when(tenderCommissionRepository.findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            CompanyContext.DEFAULT_COMPANY_ID,
            rfqId
        )).thenReturn(List.of(commission));
        TenderCommissionMemberSetting setting = new TenderCommissionMemberSetting();
        setting.setActive(true);
        setting.setConflictDeclarationSigned(true);
        when(memberSettingRepository.findByTenantIdAndTenderCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.DEFAULT_TENANT,
            commissionId
        )).thenReturn(List.of(setting));
        when(openingProtocolRepository.save(any(TenderBidOpeningProtocol.class)))
            .thenAnswer(invocation -> {
                TenderBidOpeningProtocol protocol = invocation.getArgument(0);
                if (protocol.getId() == null) {
                    protocol.setId(UUID.randomUUID());
                }
                return protocol;
            });

        TenderProcessService service = new TenderProcessService(
            rfqRepository,
            invitationRepository,
            quotationRepository,
            mock(SupplierRelatedPartyRepository.class),
            tenderCommissionRepository,
            memberSettingRepository,
            mock(TenderCommissionMemberRepository.class),
            openingProtocolRepository,
            mock(TenderBidOpeningLineRepository.class),
            mock(TenderConflictCheckRepository.class),
            mock(TenderEvaluationProtocolRepository.class),
            openingProtocolMapper,
            null,
            mock(IntegrationEventService.class),
            mock(AuditService.class)
        );

        assertThatCode(() -> service.openBids(rfqId, null)).doesNotThrowAnyException();
        assertThat(rfq.getStatus()).isEqualTo("BIDS_OPENED");
    }

    private SupplierQuotation quotation(UUID supplierId) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(UUID.randomUUID());
        quotation.setTenantId(TenantContext.DEFAULT_TENANT);
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setStatus("SUBMITTED");
        quotation.setSupplierId(supplierId);
        quotation.setSubmittedAt(LocalDate.now().atStartOfDay());
        return quotation;
    }
}
