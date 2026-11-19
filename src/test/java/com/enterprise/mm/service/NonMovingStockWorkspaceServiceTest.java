package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.mapper.NonMovingStockMapper;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.NonMovingStockCaseResponse;
import com.enterprise.mm.payload.NonMovingStockCaseLineResponse;
import com.enterprise.mm.payload.NonMovingStockDecisionResponse;
import com.enterprise.mm.payload.NonMovingStockWorkspaceFilter;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.NonMovingStockDecisionApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class NonMovingStockWorkspaceServiceTest {
    @Mock NonMovingStockCaseRepository caseRepository;
    @Mock NonMovingStockCaseLineRepository caseLineRepository;
    @Mock NonMovingStockRepository stockRepository;
    @Mock IntegrationEventRepository integrationEventRepository;
    @Mock InventoryAccountingSettingsRepository inventoryAccountingSettingsRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock AttachmentRepository attachmentRepository;
    @Mock NonMovingStockCaseService caseService;
    @Mock NonMovingStockMapper stockMapper;
    @Mock UserRepository userRepository;
    @Mock CommissionRepository commissionRepository;
    @Mock ApprovalEngineService approvalEngineService;
    @Mock NonMovingStockEvidencePolicy evidencePolicy;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-nms-workspace");
        CompanyContext.setCompany(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "case.manager", "n/a", List.of(new SimpleGrantedAuthority("ROLE_MM_MANAGER"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void detectedPositionResponseExposesServerOwnedCaseCreationContract() {
        var response = new com.enterprise.mm.payload.NonMovingStockResponse();
        UUID activeCaseId = UUID.randomUUID();

        Object eligible;
        Object caseId;
        Object caseNo;
        try {
            response.getClass().getMethod("setCaseCreationEligible", Boolean.class).invoke(response, false);
            response.getClass().getMethod("setActiveCaseId", UUID.class).invoke(response, activeCaseId);
            response.getClass().getMethod("setActiveCaseNo", String.class).invoke(response, "NMS-CASE-ACTIVE");
            eligible = response.getClass().getMethod("getCaseCreationEligible").invoke(response);
            caseId = response.getClass().getMethod("getActiveCaseId").invoke(response);
            caseNo = response.getClass().getMethod("getActiveCaseNo").invoke(response);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Detected position response is missing case-creation metadata", exception);
        }

        assertThat(eligible).isEqualTo(false);
        assertThat(caseId).isEqualTo(activeCaseId);
        assertThat(caseNo).isEqualTo("NMS-CASE-ACTIVE");
    }

    @Test
    void activeCaseMembershipMakesDetectedPositionIneligibleForCaseCreation() {
        NonMovingStock detected = new NonMovingStock();
        detected.setId(UUID.randomUUID());
        detected.setTenantId(TenantContext.currentTenant());
        detected.setCompanyId(CompanyContext.currentCompany());
        detected.setStatus("DETECTED");
        var response = new com.enterprise.mm.payload.NonMovingStockResponse();
        response.setId(detected.getId());
        response.setCompanyId(detected.getCompanyId());
        response.setStatus(detected.getStatus());
        NonMovingStockCase activeCase = new NonMovingStockCase();
        activeCase.setId(UUID.randomUUID());
        activeCase.setTenantId(TenantContext.currentTenant());
        activeCase.setCompanyId(CompanyContext.currentCompany());
        activeCase.setCaseNo("NMS-CASE-ACTIVE");
        activeCase.setStatus("UNDER_REVIEW");
        NonMovingStockCaseLine membership = new NonMovingStockCaseLine();
        membership.setCaseId(activeCase.getId());
        membership.setNonMovingStockId(detected.getId());
        membership.setActiveMembership(true);
        when(stockRepository.findByIdAndIsDeletedFalse(detected.getId()))
            .thenReturn(java.util.Optional.of(detected));
        when(stockMapper.toResponse(detected)).thenReturn(response);
        when(caseLineRepository.findByTenantIdAndNonMovingStockIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), detected.getId()
        )).thenReturn(List.of(membership));
        when(caseRepository.findByIdAndIsDeletedFalse(activeCase.getId()))
            .thenReturn(java.util.Optional.of(activeCase));

        var position = service().position(detected.getId());

        assertThat(position.getCaseCreationEligible()).isFalse();
        assertThat(position.getActiveCaseId()).isEqualTo(activeCase.getId());
        assertThat(position.getActiveCaseNo()).isEqualTo(activeCase.getCaseNo());
    }

    @Test
    void summaryAndQueuesUseTheSameNormalizedFilter() {
        NonMovingStockCase overdue = new NonMovingStockCase();
        overdue.setId(UUID.randomUUID());
        overdue.setStatus("UNDER_REVIEW");
        overdue.setReviewDueDate(LocalDate.now().minusDays(1));
        overdue.setTotalBookValue(BigDecimal.valueOf(100));
        readySnapshot(overdue, "USD");
        NonMovingStockCase approval = new NonMovingStockCase();
        approval.setId(UUID.randomUUID());
        approval.setStatus("APPROVAL_PENDING");
        approval.setTotalBookValue(BigDecimal.valueOf(200));
        readySnapshot(approval, "USD");
        NonMovingStockCase commission = new NonMovingStockCase();
        commission.setId(UUID.randomUUID());
        commission.setStatus("COMMISSION_REVIEW");
        commission.setTotalBookValue(BigDecimal.valueOf(300));
        readySnapshot(commission, "USD");
        NonMovingStockCase execution = new NonMovingStockCase();
        execution.setId(UUID.randomUUID());
        execution.setStatus("IN_EXECUTION");
        execution.setTotalBookValue(BigDecimal.valueOf(400));
        readySnapshot(execution, "USD");
        NonMovingStockCase closed = new NonMovingStockCase();
        closed.setId(UUID.randomUUID());
        closed.setStatus("CLOSED");
        closed.setClosedAt(LocalDateTime.now().minusDays(1));
        closed.setTotalBookValue(BigDecimal.valueOf(500));
        NonMovingStock position = new NonMovingStock();
        position.setStatus("DETECTED");
        when(caseRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), any(), anyBoolean(), anyBoolean(), any()
        ))
            .thenAnswer(invocation -> new PageImpl<>(Boolean.TRUE.equals(invocation.getArgument(27))
                ? List.of(overdue, approval, commission, execution)
                : List.of(overdue, approval, commission, execution, closed)));
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        ))
            .thenReturn(new PageImpl<>(List.of(position)));
        when(integrationEventRepository.countDistinctCaseAggregatesWithStatuses(
            any(), any(), any(), eq(List.of("FAILED", "RETRY", "DEAD", "CANCELLED"))
        )).thenReturn(1L);
        NonMovingStockWorkspaceService service = new NonMovingStockWorkspaceService(
            caseRepository, caseLineRepository, stockRepository, integrationEventRepository, auditLogRepository,
            attachmentRepository, caseService, stockMapper, userRepository, commissionRepository,
            approvalEngineService, evidencePolicy, inventoryAccountingSettingsRepository
        );

        var summary = service.summary(new NonMovingStockWorkspaceFilter(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null
        ));

        assertThat(summary.detectedPositions()).isEqualTo(1);
        assertThat(summary.activeCases()).isEqualTo(4);
        assertThat(summary.overdueCases()).isEqualTo(1);
        assertThat(summary.commissionReviewCases()).isEqualTo(1);
        assertThat(summary.approvalPendingCases()).isEqualTo(1);
        assertThat(summary.executionBlockedCases()).isEqualTo(1);
        assertThat(summary.closedPeriodCases()).isEqualTo(1);
        assertThat(summary.failedIntegrations()).isEqualTo(1);
        assertThat(summary.valueAtRisk()).isEqualByComparingTo("1000");
        assertThat(summary.valueCurrencyCode()).isEqualTo("USD");
        assertThat(summary.valueHealth()).isEqualTo("READY");
        assertThat(summary.valueHealthMessage()).isNull();
    }

    @Test
    void missingAccountingSettingsMakesSummaryPartialAndDetectedEstimateExplicitlyUnavailable() {
        NonMovingStock detected = new NonMovingStock();
        detected.setId(UUID.randomUUID());
        detected.setTenantId(TenantContext.currentTenant());
        detected.setCompanyId(CompanyContext.currentCompany());
        detected.setQuantity(BigDecimal.valueOf(4));
        var detectedResponse = new com.enterprise.mm.payload.NonMovingStockResponse();
        detectedResponse.setQuantity(BigDecimal.valueOf(4));
        detectedResponse.setEstimatedValue(BigDecimal.valueOf(120));

        when(caseRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), any(), anyBoolean(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of()));
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of(detected)));
        when(stockMapper.toResponse(detected)).thenReturn(detectedResponse);
        when(stockRepository.findByIdAndIsDeletedFalse(detected.getId()))
            .thenReturn(java.util.Optional.of(detected));
        when(inventoryAccountingSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany()
        )).thenReturn(java.util.Optional.empty());

        var service = service();
        var summary = service.summary(null);
        var position = service.position(detected.getId());

        assertThat(summary.valueCurrencyCode()).isEqualTo("UZS");
        assertThat(summary.valueHealth()).isEqualTo("PARTIAL");
        assertThat(summary.valueHealthMessage()).contains("settings", "UZS fallback");
        assertThat(position.getEstimatedValue()).isEqualByComparingTo("120");
        assertThat(position.getValueCurrencyCode()).isEqualTo("UZS");
        assertThat(position.getValueHealth()).isEqualTo("PARTIAL");
        assertThat(position.getValueHealthMessage()).contains("settings", "UZS fallback");
    }

    @Test
    void unscopedGlobalAdminDoesNotPresentMixedCompanyCurrenciesAsOneComparableValue() {
        UUID companyUzs = UUID.randomUUID();
        UUID companyUsd = UUID.randomUUID();
        NonMovingStockCase uzsCase = new NonMovingStockCase();
        uzsCase.setId(UUID.randomUUID());
        uzsCase.setCompanyId(companyUzs);
        uzsCase.setStatus("UNDER_REVIEW");
        uzsCase.setTotalBookValue(BigDecimal.valueOf(100));
        readySnapshot(uzsCase, "UZS");
        NonMovingStockCase usdCase = new NonMovingStockCase();
        usdCase.setId(UUID.randomUUID());
        usdCase.setCompanyId(companyUsd);
        usdCase.setStatus("UNDER_REVIEW");
        usdCase.setTotalBookValue(BigDecimal.valueOf(200));
        readySnapshot(usdCase, "USD");

        var authentication = new UsernamePasswordAuthenticationToken(
            "global.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(caseRepository.search(
            any(), nullable(UUID.class), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), any(), anyBoolean(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of(uzsCase, usdCase)));
        when(stockRepository.search(
            any(), nullable(UUID.class), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of()));
        var summary = service().summary(null);

        assertThat(summary.valueAtRisk()).isNull();
        assertThat(summary.valueCurrencyCode()).isNull();
        assertThat(summary.valueHealth()).isEqualTo("UNAVAILABLE");
        assertThat(summary.valueHealthMessage()).contains(
            "multiple persisted case accounting currency snapshots", "USD", "UZS", "not comparable"
        );
    }

    @Test
    void summaryUsesStableQueueScopesInsteadOfTheCurrentlySelectedQueueContract() {
        NonMovingStockCase active = new NonMovingStockCase();
        active.setId(UUID.randomUUID());
        active.setStatus("UNDER_REVIEW");
        active.setTotalBookValue(BigDecimal.valueOf(125));
        readySnapshot(active, "UZS");
        NonMovingStockCase closed = new NonMovingStockCase();
        closed.setId(UUID.randomUUID());
        closed.setStatus("CLOSED");
        closed.setTotalBookValue(BigDecimal.valueOf(900));

        when(caseRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), any(), anyBoolean(), anyBoolean(), any()
        )).thenAnswer(invocation -> {
            assertThat((Boolean) invocation.getArgument(3)).isFalse();
            assertThat((String) invocation.getArgument(4)).isEqualTo("%bearing%");
            assertThat((List<String>) invocation.getArgument(7)).containsExactly("__NO_STATUS_FILTER__");
            assertThat((Boolean) invocation.getArgument(8)).isTrue();
            assertThat((Boolean) invocation.getArgument(13)).isFalse();
            assertThat((List<String>) invocation.getArgument(16)).containsExactly("__NO_FILTER__");
            assertThat((Boolean) invocation.getArgument(17)).isTrue();
            assertThat((List<String>) invocation.getArgument(21)).containsExactly("__NO_FILTER__");
            assertThat((Boolean) invocation.getArgument(22)).isTrue();
            assertThat((List<String>) invocation.getArgument(23)).containsExactly("__NO_FILTER__");
            assertThat((Boolean) invocation.getArgument(24)).isTrue();
            boolean activeOnly = invocation.getArgument(27);
            return new PageImpl<>(activeOnly ? List.of(active) : List.of(active, closed));
        });
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenAnswer(invocation -> {
            assertThat((Boolean) invocation.getArgument(3)).isFalse();
            assertThat((String) invocation.getArgument(4)).isEqualTo("%bearing%");
            assertThat((List<String>) invocation.getArgument(7))
                .containsExactly("DETECTED", "UNDER_REVIEW");
            assertThat((Boolean) invocation.getArgument(8)).isFalse();
            return new PageImpl<>(List.of(new NonMovingStock()));
        });

        var summary = service().summary(new NonMovingStockWorkspaceFilter(
            "bearing", List.of("CLOSED"), UUID.randomUUID(), null,
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), UUID.randomUUID(), true,
            List.of("WRITE_OFF"), List.of("FAILED"), UUID.randomUUID(), BigDecimal.TEN,
            BigDecimal.valueOf(1000), List.of("FAILED_INTEGRATION"), List.of("FAILED"),
            List.of("INCLUDED"), true
        ));

        assertThat(summary.detectedPositions()).isEqualTo(1);
        assertThat(summary.activeCases()).isEqualTo(1);
        assertThat(summary.closedPeriodCases()).isEqualTo(1);
        assertThat(summary.valueAtRisk()).isEqualByComparingTo("125");
    }

    @Test
    void summaryUsesPersistedCaseCurrencyAfterCompanyBaseCurrencyChanges() {
        NonMovingStockCase historicalUsdCase = new NonMovingStockCase();
        historicalUsdCase.setId(UUID.randomUUID());
        historicalUsdCase.setCompanyId(CompanyContext.currentCompany());
        historicalUsdCase.setCaseNo("NMS-HIST-USD");
        historicalUsdCase.setStatus("UNDER_REVIEW");
        historicalUsdCase.setTotalBookValue(BigDecimal.valueOf(750));
        readySnapshot(historicalUsdCase, "USD");
        stubSummaryCases(List.of(historicalUsdCase));
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of()));
        var summary = service().summary(null);

        assertThat(summary.valueAtRisk()).isEqualByComparingTo("750");
        assertThat(summary.valueCurrencyCode()).isEqualTo("USD");
        assertThat(summary.valueHealth()).isEqualTo("READY");
        assertThat(summary.valueHealthMessage()).isNull();
        org.mockito.Mockito.verifyNoInteractions(inventoryAccountingSettingsRepository);
    }

    @Test
    void legacyCurrencySnapshotIsSummedOnlyAsExplicitPartial() {
        NonMovingStockCase legacy = new NonMovingStockCase();
        legacy.setId(UUID.randomUUID());
        legacy.setCaseNo("NMS-LEGACY");
        legacy.setStatus("UNDER_REVIEW");
        legacy.setTotalBookValue(BigDecimal.valueOf(90));
        legacy.setAccountingCurrencyCode("USD");
        legacy.setValuationHealth(null);
        legacy.setValuationHealthMessage(null);
        stubSummaryCases(List.of(legacy));
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of()));

        var summary = service().summary(null);

        assertThat(summary.valueAtRisk()).isEqualByComparingTo("90");
        assertThat(summary.valueCurrencyCode()).isEqualTo("USD");
        assertThat(summary.valueHealth()).isEqualTo("PARTIAL");
        assertThat(summary.valueHealthMessage()).contains("NMS-LEGACY", "provenance");
    }

    @Test
    void missingPersistedCurrencyMakesCaseExposureUnavailable() {
        NonMovingStockCase legacy = new NonMovingStockCase();
        legacy.setId(UUID.randomUUID());
        legacy.setCaseNo("NMS-NO-CURRENCY");
        legacy.setStatus("UNDER_REVIEW");
        legacy.setTotalBookValue(BigDecimal.valueOf(90));
        stubSummaryCases(List.of(legacy));
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of()));

        var summary = service().summary(null);

        assertThat(summary.valueAtRisk()).isNull();
        assertThat(summary.valueCurrencyCode()).isNull();
        assertThat(summary.valueHealth()).isEqualTo("UNAVAILABLE");
        assertThat(summary.valueHealthMessage()).contains("NMS-NO-CURRENCY", "persisted accounting currency");
    }

    @Test
    void missingCaseBookValueSnapshotMakesAggregateUnavailableInsteadOfReadyZero() {
        NonMovingStockCase missingValue = new NonMovingStockCase();
        missingValue.setId(UUID.randomUUID());
        missingValue.setCaseNo("NMS-NO-VALUE");
        missingValue.setStatus("UNDER_REVIEW");
        missingValue.setTotalBookValue(null);
        readySnapshot(missingValue, "USD");
        stubSummaryCases(List.of(missingValue));
        when(stockRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of()));

        var summary = service().summary(null);

        assertThat(summary.valueAtRisk()).isNull();
        assertThat(summary.valueCurrencyCode()).isEqualTo("USD");
        assertThat(summary.valueHealth()).isEqualTo("UNAVAILABLE");
        assertThat(summary.valueHealthMessage()).contains("NMS-NO-VALUE", "book-value snapshot");
    }

    @Test
    void caseQueuePreservesCaseAndLineAccountingCurrencyFromTheDetailContract() {
        UUID caseId = UUID.randomUUID();
        NonMovingStockCase entity = new NonMovingStockCase();
        entity.setId(caseId);
        entity.setCompanyId(CompanyContext.currentCompany());
        entity.setStatus("UNDER_REVIEW");
        NonMovingStockCaseLineResponse line = new NonMovingStockCaseLineResponse();
        line.setCurrencyCode("USD");
        line.setValuationHealth("PARTIAL");
        line.setValuationHealthMessage("Fallback provenance");
        NonMovingStockCaseResponse response = new NonMovingStockCaseResponse();
        response.setId(caseId);
        response.setCompanyId(CompanyContext.currentCompany());
        response.setStatus("UNDER_REVIEW");
        response.setAccountingCurrencyCode("USD");
        response.setValuationHealth("PARTIAL");
        response.setValuationHealthMessage("Fallback provenance");
        response.setLines(List.of(line));
        when(caseRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), any(), anyBoolean(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(List.of(entity)));
        when(caseService.get(caseId)).thenReturn(response);

        var queued = service().cases(null, Pageable.unpaged());

        assertThat(queued.getContent()).singleElement().satisfies(item -> {
            assertThat(item.getAccountingCurrencyCode()).isEqualTo("USD");
            assertThat(item.getValuationHealth()).isEqualTo("PARTIAL");
            assertThat(item.getValuationHealthMessage()).isEqualTo("Fallback provenance");
            assertThat(item.getLines()).singleElement().satisfies(caseLine -> {
                assertThat(caseLine.getCurrencyCode()).isEqualTo("USD");
                assertThat(caseLine.getValuationHealth()).isEqualTo("PARTIAL");
                assertThat(caseLine.getValuationHealthMessage()).isEqualTo("Fallback provenance");
            });
        });
    }

    @Test
    void contextCarriesRealAuditApprovalImpactAndStateAwareActions() {
        UUID caseId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID commissionId = UUID.randomUUID();
        NonMovingStockCaseResponse stockCase = new NonMovingStockCaseResponse();
        stockCase.setId(caseId);
        stockCase.setCompanyId(CompanyContext.currentCompany());
        stockCase.setStatus("APPROVAL_PENDING");
        stockCase.setVersion(4L);
        stockCase.setCommissionId(commissionId);
        stockCase.setAccountingCurrencyCode("USD");
        stockCase.setValuationHealth("PARTIAL");
        stockCase.setValuationHealthMessage("Fallback provenance");
        NonMovingStockCaseLineResponse caseLine = new NonMovingStockCaseLineResponse();
        caseLine.setCurrencyCode("USD");
        caseLine.setValuationHealth("PARTIAL");
        caseLine.setValuationHealthMessage("Fallback provenance");
        stockCase.setLines(List.of(caseLine));
        NonMovingStockDecisionResponse decision = new NonMovingStockDecisionResponse();
        decision.setId(decisionId);
        decision.setStatus("APPROVAL_PENDING");
        stockCase.setDecisions(List.of(decision));
        when(caseService.get(caseId)).thenReturn(stockCase);

        NonMovingStockCase entity = new NonMovingStockCase();
        entity.setId(caseId);
        entity.setStatus("APPROVAL_PENDING");
        when(caseRepository.findByIdAndIsDeletedFalse(caseId)).thenReturn(java.util.Optional.of(entity));
        Commission commission = new Commission();
        commission.setId(commissionId);
        commission.setTenantId(TenantContext.currentTenant());
        commission.setCompanyId(CompanyContext.currentCompany());
        commission.setCode("NMS-COM-01");
        commission.setName("Non-moving stock commission");
        when(commissionRepository.findByIdAndIsDeletedFalse(commissionId)).thenReturn(java.util.Optional.of(commission));

        AuditLog audit = new AuditLog();
        audit.setId(UUID.randomUUID());
        audit.setAction("DECISION_SUBMITTED_FOR_APPROVAL");
        audit.setUsername("case.manager");
        audit.setDetails("WRITE_OFF");
        audit.setOccurredAt(LocalDateTime.of(2026, 7, 15, 10, 30));
        when(auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            any(), any(), any(), eq(caseId)
        )).thenReturn(List.of(audit));

        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setEventType("NON_MOVING_STOCK_WRITE_OFF");
        event.setStatus("FAILED");
        event.setLastError("Accounting endpoint unavailable");
        when(integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
            any(), any(), any(), eq(caseId)
        )).thenReturn(List.of(event));

        Attachment evidence = new Attachment();
        evidence.setId(UUID.randomUUID());
        evidence.setOriginalFileName("commission-protocol.pdf");
        evidence.setUploadedAt(LocalDateTime.of(2026, 7, 15, 10, 0));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            any(), any(), any(), eq(caseId)
        )).thenReturn(List.of(evidence));

        ApprovalInstanceResponse approval = new ApprovalInstanceResponse(
            approvalId, UUID.randomUUID(), "Non-moving stock decision", NonMovingStockDecisionApprovalAdapter.MODULE_KEY,
            decisionId, "NMS-100 / WRITE_OFF", ApprovalInstanceStatus.PENDING, 1,
            LocalDateTime.of(2026, 7, 15, 10, 31), null, List.of()
        );
        when(approvalEngineService.currentInstance(NonMovingStockDecisionApprovalAdapter.MODULE_KEY, decisionId))
            .thenReturn(approval);
        when(approvalEngineService.history(approvalId)).thenReturn(List.of(new ApprovalHistoryResponse(
            UUID.randomUUID(), approvalId, ApprovalHistoryAction.STARTED, "case.manager", null, 1,
            "Submitted", LocalDateTime.of(2026, 7, 15, 10, 31)
        )));

        var context = service().workspace(caseId);

        assertThat(context.stockCase()).isSameAs(stockCase);
        assertThat(context.stockCase().getAccountingCurrencyCode()).isEqualTo("USD");
        assertThat(context.stockCase().getValuationHealth()).isEqualTo("PARTIAL");
        assertThat(context.stockCase().getValuationHealthMessage()).isEqualTo("Fallback provenance");
        assertThat(context.stockCase().getLines()).singleElement().satisfies(line -> {
            assertThat(line.getCurrencyCode()).isEqualTo("USD");
            assertThat(line.getValuationHealth()).isEqualTo("PARTIAL");
            assertThat(line.getValuationHealthMessage()).isEqualTo("Fallback provenance");
        });
        assertThat(context.auditTimeline()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("DECISION_SUBMITTED_FOR_APPROVAL");
            assertThat(item.actor()).isEqualTo("case.manager");
        });
        assertThat(context.approval()).isSameAs(approval);
        assertThat(context.approvalHistory()).singleElement().satisfies(item ->
            assertThat(item.action()).isEqualTo(ApprovalHistoryAction.STARTED)
        );
        assertThat(context.integrationEvents()).singleElement().satisfies(item ->
            assertThat(item.lastError()).isEqualTo("Accounting endpoint unavailable")
        );
        assertThat(context.allowedActions()).extracting(action -> action.code()).containsExactly("OPEN_APPROVAL");
        assertThat(context.stockCase().getCommissionCode()).isEqualTo("NMS-COM-01");
        assertThat(context.stockCase().getCommissionName()).isEqualTo("Non-moving stock commission");
    }

    @Test
    void executedCaseSurfacesComputedClosureBlockersAndDoesNotOfferClose() {
        UUID caseId = UUID.randomUUID();
        NonMovingStockCaseResponse stockCase = new NonMovingStockCaseResponse();
        stockCase.setId(caseId);
        stockCase.setCompanyId(CompanyContext.currentCompany());
        stockCase.setStatus("EXECUTED");
        when(caseService.get(caseId)).thenReturn(stockCase);

        NonMovingStockCase entity = new NonMovingStockCase();
        entity.setId(caseId);
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(CompanyContext.currentCompany());
        entity.setStatus("EXECUTED");
        when(caseRepository.findByIdAndIsDeletedFalse(caseId)).thenReturn(java.util.Optional.of(entity));
        when(evidencePolicy.closureReadiness(entity)).thenReturn(new NonMovingStockEvidencePolicy.ClosureReadiness(
            false,
            List.of(new NonMovingStockEvidencePolicy.Blocker(
                "MISSING_EVIDENCE", "Required write-off/disposal act is not attached"
            )),
            List.of(new NonMovingStockEvidencePolicy.EvidenceRequirement(
                "WRITE_OFF_EVIDENCE", List.of("WRITE_OFF", "SCRAP"),
                List.of("DISPOSAL_ACT", "WRITE_OFF_ACT", "COMMISSION_PROTOCOL"), false,
                "Attach a write-off/disposal act or commission protocol"
            ))
        ));

        var context = service().workspace(caseId);

        assertThat(context.readiness().closureReady()).isFalse();
        assertThat(context.readiness().evidenceRequirements())
            .extracting(requirement -> requirement.code())
            .contains("WRITE_OFF_EVIDENCE");
        assertThat(context.blockers()).extracting(blocker -> blocker.code())
            .contains("MISSING_EVIDENCE");
        assertThat(context.allowedActions()).extracting(action -> action.code())
            .doesNotContain("CLOSE");
    }

    @Test
    void selectorsUseStrictCurrentCompanyScopeAndOnlyActiveNonMovingCommissions() {
        PageRequest pageable = PageRequest.of(0, 25);
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setCode("USR-9");
        user.setFullName("Aziza Karimova");
        user.setName("Aziza account");
        user.setEnabled(true);
        when(userRepository.searchActiveCompanyUsers(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "Aziza", pageable
        )).thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        Commission commission = new Commission();
        commission.setId(UUID.randomUUID());
        commission.setCode("NMS-COM-1");
        commission.setName("Неликвиды 2026");
        commission.setType(CommissionType.NON_MOVING_STOCK);
        commission.setActive(true);
        when(commissionRepository.search(
            eq(TenantContext.currentTenant()), eq(CompanyContext.currentCompany()), eq(false), eq("2026"),
            any(), any(), any(), eq(true), eq(CommissionType.NON_MOVING_STOCK), eq(true), eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(commission), pageable, 1));

        var users = service().userSelector("  Aziza ", pageable);
        var commissions = service().commissionSelector(" 2026 ", pageable);

        assertThat(users).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo(user.getId());
            assertThat(option.displayName()).isEqualTo("Aziza Karimova");
        });
        assertThat(commissions).singleElement().satisfies(option ->
            assertThat(option.displayName()).isEqualTo("Неликвиды 2026")
        );
    }

    private NonMovingStockWorkspaceService service() {
        return new NonMovingStockWorkspaceService(
            caseRepository, caseLineRepository, stockRepository, integrationEventRepository, auditLogRepository,
            attachmentRepository, caseService, stockMapper, userRepository, commissionRepository,
            approvalEngineService, evidencePolicy, inventoryAccountingSettingsRepository
        );
    }

    private void readySnapshot(NonMovingStockCase stockCase, String currencyCode) {
        stockCase.setAccountingCurrencyCode(currencyCode);
        stockCase.setValuationHealth("READY");
        stockCase.setValuationHealthMessage(null);
    }

    private void stubSummaryCases(List<NonMovingStockCase> cases) {
        when(caseRepository.search(
            any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(),
            any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), any(), anyBoolean(), anyBoolean(), any()
        )).thenReturn(new PageImpl<>(cases));
    }
}
