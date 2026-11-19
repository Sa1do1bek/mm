package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceRequestMilestone;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntakeMilestone;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceRequestMilestoneRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class ServiceRequestMilestoneServiceTest {
    private static final String TENANT = "tenant-milestones";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Mock private ServiceRequestMilestoneRepository milestoneRepository;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private ServiceExecutionRepository executionRepository;
    @Mock private AuditService auditService;
    @Mock private ServiceReviewHistoryService historyService;

    private ServiceRequestMilestoneService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        service = new ServiceRequestMilestoneService(
            milestoneRepository, requestRepository, executionRepository, auditService, historyService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void persistsOrderedQuantityMilestonesWithRequestScopeAndSequence() {
        ServiceRequest request = quantityRequest(BigDecimal.TEN);
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));
        when(milestoneRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ServiceRequestMilestone> created = service.createForRequest(request.getId(), List.of(
            milestone("Foundation", LocalDate.of(2026, 8, 10), "4", null),
            milestone("Commissioning", LocalDate.of(2026, 8, 25), "6", null)
        ));

        assertThat(created).hasSize(2);
        assertThat(created).extracting(ServiceRequestMilestone::getSequenceNumber).containsExactly(1, 2);
        assertThat(created).extracting(ServiceRequestMilestone::getStatus).containsOnly("PENDING");
        assertThat(created).allSatisfy(item -> {
            assertThat(item.getTenantId()).isEqualTo(TENANT);
            assertThat(item.getCompanyId()).isEqualTo(COMPANY);
            assertThat(item.getServiceRequestId()).isEqualTo(request.getId());
            assertThat(item.getAcceptanceMode()).isEqualTo("QUANTITY");
            assertThat(item.getUomCode()).isEqualTo("HOUR");
            assertThat(item.getAcceptedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(item.getRejectedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(item.getOrderedAmount()).isNull();
        });
        verify(auditService).logInCurrentTransaction(
            "ServiceRequest", request.getId(), "CREATE_MILESTONES", "count=2", null, null
        );
    }

    @Test
    void rejectsMilestonesWhoseAllocationDoesNotEqualRequestBasis() {
        ServiceRequest request = amountRequest(new BigDecimal("100.00"));
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.createForRequest(request.getId(), List.of(
            milestone("Stage one", request.getPlannedEndDate(), null, "70")
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must equal ordered amount");

        verify(milestoneRepository, never()).saveAll(any());
    }

    @Test
    void rejectsWrongBasisAndDueDateOutsidePlannedPeriod() {
        ServiceRequest request = quantityRequest(BigDecimal.TEN);
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.createForRequest(request.getId(), List.of(
            milestone("Wrong basis", request.getPlannedEndDate(), null, "10")
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("quantity allocation");

        assertThatThrownBy(() -> service.createForRequest(request.getId(), List.of(
            milestone("Too late", request.getPlannedEndDate().plusDays(1), "10", null)
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("within the request planned period");
    }

    @Test
    void partialAcceptanceUpdatesMilestoneAndRecomputesRequestAggregate() {
        ServiceRequest request = amountRequest(new BigDecimal("100"));
        request.setStatus("WAITING_ACCEPTANCE");
        ServiceRequestMilestone first = amountMilestone(request, "Stage one", "40", 3L);
        ServiceRequestMilestone second = amountMilestone(request, "Stage two", "60", 1L);
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));
        when(milestoneRepository.findScoped(TENANT, COMPANY, request.getId(), first.getId()))
            .thenReturn(Optional.of(first));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(List.of(acceptedExecution()));
        when(milestoneRepository.saveAndFlush(first)).thenReturn(first);
        when(milestoneRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc(
            TENANT, COMPANY, request.getId()
        )).thenReturn(List.of(first, second));
        when(requestRepository.saveAndFlush(request)).thenReturn(request);

        ServiceRequestMilestone accepted = service.accept(
            request.getId(), first.getId(), 3L,
            new ServiceRequestMilestoneService.Acceptance(new BigDecimal("25"), BigDecimal.ZERO, false, "Stage checked")
        );

        assertThat(accepted.getAcceptedAmount()).isEqualByComparingTo("25");
        assertThat(accepted.getRemainingAmount()).isEqualByComparingTo("15");
        assertThat(accepted.getStatus()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(request.getAcceptedAmount()).isEqualByComparingTo("25");
        assertThat(request.getRejectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getStatus()).isEqualTo("PARTIALLY_ACCEPTED");
        verify(historyService).record(request.getId(), null, "MILESTONE_ACCEPTANCE", "PARTIALLY_ACCEPTED", "Stage checked");
    }

    @Test
    void finalAcceptanceRequiresFullMilestoneDispositionAndNeverOverAccepts() {
        ServiceRequest request = amountRequest(new BigDecimal("100"));
        request.setStatus("PARTIALLY_ACCEPTED");
        ServiceRequestMilestone milestone = amountMilestone(request, "Stage", "100", 8L);
        milestone.setAcceptedAmount(new BigDecimal("60"));
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));
        when(milestoneRepository.findScoped(TENANT, COMPANY, request.getId(), milestone.getId()))
            .thenReturn(Optional.of(milestone));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(List.of(acceptedExecution()));

        assertThatThrownBy(() -> service.accept(
            request.getId(), milestone.getId(), 8L,
            new ServiceRequestMilestoneService.Acceptance(new BigDecimal("20"), BigDecimal.ZERO, true, "Final")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("full remaining milestone basis");

        assertThatThrownBy(() -> service.accept(
            request.getId(), milestone.getId(), 8L,
            new ServiceRequestMilestoneService.Acceptance(new BigDecimal("41"), BigDecimal.ZERO, false, "Too much")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("exceed ordered amount");

        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void finalMilestoneAcceptanceCompletesTheRequestOnlyWhenEveryMilestoneIsAccepted() {
        ServiceRequest request = amountRequest(new BigDecimal("100"));
        request.setStatus("PARTIALLY_ACCEPTED");
        ServiceRequestMilestone first = amountMilestone(request, "Stage one", "40", 2L);
        first.setAcceptedAmount(new BigDecimal("40"));
        first.setStatus("ACCEPTED");
        ServiceRequestMilestone finalStage = amountMilestone(request, "Stage two", "60", 4L);
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));
        when(milestoneRepository.findScoped(TENANT, COMPANY, request.getId(), finalStage.getId()))
            .thenReturn(Optional.of(finalStage));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(List.of(acceptedExecution()));
        when(milestoneRepository.saveAndFlush(finalStage)).thenReturn(finalStage);
        when(milestoneRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc(
            TENANT, COMPANY, request.getId()
        )).thenReturn(List.of(first, finalStage));
        when(requestRepository.saveAndFlush(request)).thenReturn(request);

        ServiceRequestMilestone accepted = service.accept(
            request.getId(), finalStage.getId(), 4L,
            new ServiceRequestMilestoneService.Acceptance(new BigDecimal("60"), BigDecimal.ZERO, true, "Final stage")
        );

        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        assertThat(accepted.getAcceptedAt()).isNotNull();
        assertThat(request.getAcceptedAmount()).isEqualByComparingTo("100");
        assertThat(request.getRemainingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void milestoneAcceptanceWaitsForEveryActiveExecutionToFinish() {
        ServiceRequest request = quantityRequest(BigDecimal.TEN);
        request.setStatus("WAITING_ACCEPTANCE");
        ServiceRequestMilestone milestone = quantityMilestone(request, "Stage", "10", 1L);
        ServiceExecution inProgress = new ServiceExecution();
        inProgress.setStatus("IN_PROGRESS");
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));
        when(milestoneRepository.findScoped(TENANT, COMPANY, request.getId(), milestone.getId()))
            .thenReturn(Optional.of(milestone));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(List.of(inProgress));

        assertThatThrownBy(() -> service.accept(
            request.getId(), milestone.getId(), 1L,
            new ServiceRequestMilestoneService.Acceptance(BigDecimal.ONE, BigDecimal.ZERO, false, "")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("all active executions");

        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsStaleMilestoneVersionBeforeMutation() {
        ServiceRequest request = quantityRequest(BigDecimal.TEN);
        request.setStatus("WAITING_ACCEPTANCE");
        ServiceRequestMilestone milestone = quantityMilestone(request, "Stage", "10", 5L);
        when(requestRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, request.getId()))
            .thenReturn(Optional.of(request));
        when(milestoneRepository.findScoped(TENANT, COMPANY, request.getId(), milestone.getId()))
            .thenReturn(Optional.of(milestone));

        assertThatThrownBy(() -> service.accept(
            request.getId(), milestone.getId(), 4L,
            new ServiceRequestMilestoneService.Acceptance(BigDecimal.ONE, BigDecimal.ZERO, false, "")
        )).isInstanceOf(ConflictException.class).hasMessageContaining("refresh");

        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    private IntakeMilestone milestone(String name, LocalDate dueDate, String quantity, String amount) {
        return new IntakeMilestone(
            name, dueDate,
            quantity == null ? null : new BigDecimal(quantity),
            amount == null ? null : new BigDecimal(amount)
        );
    }

    private ServiceRequest amountRequest(BigDecimal ordered) {
        ServiceRequest request = baseRequest();
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(ordered);
        request.setCurrencyCode("UZS");
        return request;
    }

    private ServiceRequest quantityRequest(BigDecimal ordered) {
        ServiceRequest request = baseRequest();
        request.setAcceptanceMode("QUANTITY");
        request.setOrderedQuantity(ordered);
        request.setUomCode("HOUR");
        return request;
    }

    private ServiceRequest baseRequest() {
        ServiceRequest request = new ServiceRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId(TENANT);
        request.setCompanyId(COMPANY);
        request.setCode("SR-M-1");
        request.setName("Maintenance scope");
        request.setStatus("DRAFT");
        request.setPlannedStartDate(LocalDate.of(2026, 8, 1));
        request.setPlannedEndDate(LocalDate.of(2026, 8, 31));
        return request;
    }

    private ServiceRequestMilestone amountMilestone(ServiceRequest request, String name, String ordered, long version) {
        ServiceRequestMilestone milestone = baseMilestone(request, name, version);
        milestone.setAcceptanceMode("AMOUNT");
        milestone.setOrderedAmount(new BigDecimal(ordered));
        milestone.setAcceptedAmount(BigDecimal.ZERO);
        milestone.setRejectedAmount(BigDecimal.ZERO);
        milestone.setCurrencyCode("UZS");
        return milestone;
    }

    private ServiceRequestMilestone quantityMilestone(ServiceRequest request, String name, String ordered, long version) {
        ServiceRequestMilestone milestone = baseMilestone(request, name, version);
        milestone.setAcceptanceMode("QUANTITY");
        milestone.setOrderedQuantity(new BigDecimal(ordered));
        milestone.setAcceptedQuantity(BigDecimal.ZERO);
        milestone.setRejectedQuantity(BigDecimal.ZERO);
        milestone.setUomCode("HOUR");
        return milestone;
    }

    private ServiceRequestMilestone baseMilestone(ServiceRequest request, String name, long version) {
        ServiceRequestMilestone milestone = new ServiceRequestMilestone();
        milestone.setId(UUID.randomUUID());
        milestone.setTenantId(TENANT);
        milestone.setCompanyId(COMPANY);
        milestone.setServiceRequestId(request.getId());
        milestone.setCode("SR-M-1-M01");
        milestone.setName(name);
        milestone.setStatus("PENDING");
        milestone.setSequenceNumber(1);
        milestone.setVersion(version);
        return milestone;
    }

    private ServiceExecution acceptedExecution() {
        ServiceExecution execution = new ServiceExecution();
        execution.setStatus("ACCEPTED");
        return execution;
    }
}
