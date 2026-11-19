package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierUnblockRequest;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierUnblockDecisionRequest;
import com.enterprise.mm.payload.SupplierUnblockRequestDto;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierUnblockRequestRepository;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierUnblockWorkflowTest {
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierAccreditationApplicationRepository applicationRepository;
    @Mock private SupplierAccreditationDecisionRepository decisionRepository;
    @Mock private SupplierUnblockRequestRepository unblockRequestRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private SupplierAdmissionService admissionService;

    private SupplierQualificationService service;
    private Supplier supplier;
    private User actor;
    private String tenantId;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        tenantId = "supplier-unblock-test";
        companyId = UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("supplier.manager", "n/a", java.util.List.of()));

        service = new SupplierQualificationService(
            supplierRepository,
            applicationRepository,
            decisionRepository,
            unblockRequestRepository,
            purchaseOrderRepository,
            userRepository,
            auditService,
            idempotencyService,
            org.mockito.Mockito.mock(com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy.class),
            admissionService
        );
        supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(tenantId);
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-001");
        supplier.setStatus("BLOCKED");
        supplier.setBlockedReason("Manual blacklist decision BL-42");
        actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setTenantId(tenantId);
        actor.setCompanyId(companyId);
        actor.setUsername("supplier.manager");

        lenient().when(supplierRepository.findByIdAndIsDeletedFalse(supplier.getId())).thenReturn(Optional.of(supplier));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void activeSupplierCannotRequestUnblock() {
        executeIdempotently();
        supplier.setStatus("ACTIVE");
        supplier.setBlockedReason(null);

        assertThatThrownBy(() -> service.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("No block exists", null), "active-supplier"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not blocked");

        verify(unblockRequestRepository, never()).save(any());
    }

    @Test
    void requestUsesAuthenticatedActorAndRejectsDuplicateOpenCase() {
        executeIdempotently();
        stubActor();
        stubUnblockSave();
        when(unblockRequestRepository.existsByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalse(
            tenantId, companyId, supplier.getId(), java.util.List.of("PENDING", "UNDER_REVIEW")))
            .thenReturn(false, true);

        var created = service.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("Evidence remediated", null), "request-one");

        assertThat(created.requestedBy()).isEqualTo(actor.getId());
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.version()).isZero();
        assertThatThrownBy(() -> service.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("Duplicate request", null), "request-two"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("open unblock request");
    }

    @Test
    void everyDirectCommandRejectsBlankIdempotencyKeysBeforeDomainAccess() {
        SupplierUnblockDecisionRequest command = new SupplierUnblockDecisionRequest(1L, "Evidence checked");
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("Evidence remediated", null), "  "))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.review(supplier.getId(), requestId, command, ""))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.approve(supplier.getId(), requestId, command, null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.reject(supplier.getId(), requestId, command, "\t"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> service.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("No bypass", null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");

        verify(idempotencyService, never()).execute(anyString(), any(), any(), any(), any(), any());
        verify(unblockRequestRepository, never()).save(any());
    }

    @Test
    void mutationNeverFallsBackToAnotherCompanyEvenForAVisibleSupplierId() {
        executeIdempotently();
        supplier.setCompanyId(UUID.randomUUID());

        assertThatThrownBy(() -> service.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("Evidence remediated", null), "wrong-company"))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class)
            .hasMessageContaining("Supplier not found");

        verify(unblockRequestRepository, never())
            .existsByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalse(
                anyString(), any(), any(), any());
        verify(unblockRequestRepository, never()).save(any());
    }

    @Test
    void reviewThenApproveClearsManualBlockAndRecalculatesAdmission() {
        executeIdempotently();
        stubActor();
        stubUnblockSave();
        when(supplierRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SupplierUnblockRequest request = request("PENDING", 4L);
        request.setRequestedBy(UUID.randomUUID());
        when(unblockRequestRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIdAndIsDeletedFalse(
            tenantId, companyId, supplier.getId(), request.getId())).thenReturn(Optional.of(request));

        var reviewed = service.review(supplier.getId(), request.getId(),
            new SupplierUnblockDecisionRequest(4L, "Evidence checked"), "review-key");
        assertThat(reviewed.status()).isEqualTo("UNDER_REVIEW");
        assertThat(reviewed.reviewedBy()).isEqualTo(actor.getId());

        request.setVersion(5L);
        var approved = service.approve(supplier.getId(), request.getId(),
            new SupplierUnblockDecisionRequest(5L, "Blacklist block released"), "approve-key");

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.decidedBy()).isEqualTo(actor.getId());
        assertThat(approved.decisionComment()).isEqualTo("Blacklist block released");
        assertThat(supplier.getStatus()).isEqualTo("NEW");
        assertThat(supplier.getBlockedReason()).isNull();
        verify(admissionService).recalculate(supplier.getId());
        verify(auditService).logInCurrentTransaction(
            "SupplierUnblockRequest", request.getId(), "APPROVE", "Blacklist block released", null, null);
    }

    @Test
    void requesterCannotApproveOrRejectOwnUnblockRequest() {
        executeIdempotently();
        stubActor();
        SupplierUnblockRequest request = request("UNDER_REVIEW", 8L);
        when(unblockRequestRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIdAndIsDeletedFalse(
            tenantId, companyId, supplier.getId(), request.getId())).thenReturn(Optional.of(request));
        SupplierUnblockDecisionRequest command = new SupplierUnblockDecisionRequest(8L, "Decision attempted");

        assertThatThrownBy(() -> service.approve(
            supplier.getId(), request.getId(), command, "self-approve"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requester cannot approve or reject");
        assertThatThrownBy(() -> service.reject(
            supplier.getId(), request.getId(), command, "self-reject"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("requester cannot approve or reject");

        assertThat(request.getStatus()).isEqualTo("UNDER_REVIEW");
        verify(unblockRequestRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
        verify(admissionService, never()).recalculate(any());
    }

    @Test
    void rejectRequiresUnderReviewAndVersionConflictIsFailClosed() {
        executeIdempotently();
        SupplierUnblockRequest pending = request("PENDING", 2L);
        when(unblockRequestRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIdAndIsDeletedFalse(
            tenantId, companyId, supplier.getId(), pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.reject(supplier.getId(), pending.getId(),
            new SupplierUnblockDecisionRequest(2L, "Still blocked"), "reject-pending"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("UNDER_REVIEW");

        pending.setStatus("UNDER_REVIEW");
        assertThatThrownBy(() -> service.reject(supplier.getId(), pending.getId(),
            new SupplierUnblockDecisionRequest(1L, "Stale decision"), "reject-stale"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(unblockRequestRepository, never()).save(any());
        verify(auditService, never()).logInCurrentTransaction(anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void idempotentReplayReturnsOriginalResponseWithoutCreatingAnotherOpenRequest() {
        stubActor();
        stubUnblockSave();
        IdempotencyRecordRepository records = org.mockito.Mockito.mock(IdempotencyRecordRepository.class);
        AtomicReference<IdempotencyRecord> stored = new AtomicReference<>();
        when(records.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(), anyString(), any(), anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(records.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            stored.set(record);
            return record;
        });
        SupplierQualificationService replayService = new SupplierQualificationService(
            supplierRepository,
            applicationRepository,
            decisionRepository,
            unblockRequestRepository,
            purchaseOrderRepository,
            userRepository,
            auditService,
            new IdempotencyService(records, new ObjectMapper().findAndRegisterModules()),
            org.mockito.Mockito.mock(com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy.class),
            admissionService
        );
        SupplierUnblockRequestDto command = new SupplierUnblockRequestDto("Evidence remediated", null);

        var first = replayService.requestUnblock(supplier.getId(), command, "same-request-key");
        var replay = replayService.requestUnblock(supplier.getId(), command, "same-request-key");

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.status()).isEqualTo("PENDING");
        verify(unblockRequestRepository).save(any());
        verify(userRepository).findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            tenantId, companyId, actor.getUsername());

        assertThatThrownBy(() -> replayService.requestUnblock(
            supplier.getId(), new SupplierUnblockRequestDto("Changed evidence", null), "same-request-key"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("different request payload");
        verify(unblockRequestRepository).save(any());
    }

    private SupplierUnblockRequest request(String status, long version) {
        SupplierUnblockRequest request = new SupplierUnblockRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId(tenantId);
        request.setCompanyId(companyId);
        request.setSupplierId(supplier.getId());
        request.setRequestedBy(actor.getId());
        request.setReason("Evidence remediated");
        request.setStatus(status);
        request.setVersion(version);
        return request;
    }

    private void stubActor() {
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            tenantId, companyId, actor.getUsername())).thenReturn(Optional.of(actor));
    }

    private void stubUnblockSave() {
        when(unblockRequestRepository.save(any())).thenAnswer(invocation -> {
            SupplierUnblockRequest entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(UUID.randomUUID());
            if (entity.getVersion() == null) entity.setVersion(0L);
            return entity;
        });
    }

    @SuppressWarnings("unchecked")
    private void executeIdempotently() {
        when(idempotencyService.execute(
            anyString(),
            any(UUID.class),
            anyString(),
            any(),
            eq(com.enterprise.mm.payload.SupplierUnblockResponse.class),
            any()
        )).thenAnswer(invocation ->
            ((java.util.function.Supplier<com.enterprise.mm.payload.SupplierUnblockResponse>) invocation.getArgument(5)).get()
        );
    }
}
