package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.service.transfer.TransferActionPolicy;
import com.enterprise.mm.service.transfer.TransferActionPolicy.Action;
import com.enterprise.mm.service.approval.ApprovedOperationContext;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class TransferCommandSafetyTest {
    private final TransferDocumentRepository transfers = mock(TransferDocumentRepository.class);
    private final RoleDataScopeService scopes = mock(RoleDataScopeService.class);
    private final TransferActionPolicy policy = new TransferActionPolicy(transfers, scopes);
    private TransferDocument transfer;
    @BeforeEach void setUp() {
        TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        transfer = new TransferDocument(); transfer.setId(UUID.randomUUID()); transfer.setStatus("IN_TRANSIT");
        transfer.setSourceWarehouseId(UUID.randomUUID()); transfer.setDestinationWarehouseId(UUID.randomUUID());
        when(transfers.findScopedForUpdate(transfer.getId(), "default", CompanyContext.DEFAULT_COMPANY_ID)).thenReturn(Optional.of(transfer));
        authenticate("operator", "WAREHOUSE_OPERATOR");
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }
    @Test void sourceAuthorityCannotReceiveAtDestination() {
        when(scopes.canAccessWarehouse(transfer.getSourceWarehouseId())).thenReturn(true);
        assertThat(policy.require(transfer.getId(), Action.DISPATCH)).isSameAs(transfer);
        assertThatThrownBy(() -> policy.require(transfer.getId(), Action.RECEIVE)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void destinationAuthorityCannotDispatchSourceStock() {
        when(scopes.canAccessWarehouse(transfer.getDestinationWarehouseId())).thenReturn(true);
        assertThat(policy.require(transfer.getId(), Action.RECEIVE)).isSameAs(transfer);
        assertThatThrownBy(() -> policy.require(transfer.getId(), Action.DISPATCH)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void viewerCannotUseWarehouseActions() {
        authenticate("viewer", "VIEWER");
        assertThatThrownBy(() -> policy.require(transfer.getId(), Action.DISPATCH)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void creatorCannotApproveEvenWithManagerRole() {
        authenticate("creator", "MM_MANAGER"); transfer.setCreatedBy("creator"); transfer.setStatus("SUBMITTED");
        when(scopes.canAccessWarehouse(transfer.getSourceWarehouseId())).thenReturn(true);
        assertThatThrownBy(() -> policy.require(transfer.getId(), Action.APPROVE)).isInstanceOf(AccessDeniedException.class).hasMessageContaining("own transfer");
    }
    @Test void closedTransferRejectsNewPostingButAuthorizedReplayCanReadIt() {
        transfer.setStatus("CLOSED"); when(scopes.canAccessWarehouse(transfer.getDestinationWarehouseId())).thenReturn(true);
        assertThat(policy.lockAuthorized(transfer.getId(), Action.RECEIVE)).isSameAs(transfer);
        assertThatThrownBy(() -> policy.require(transfer.getId(), Action.RECEIVE)).isInstanceOf(com.enterprise.mm.error.ConflictException.class);
    }
    @Test void completedWorkflowAllowsApprovalWithoutWarehouseAuthority() {
        transfer.setStatus("SUBMITTED");
        ApprovedOperationContext.run(TransferApproveOperationHandler.MODULE_KEY, transfer.getId(), () -> {
            assertThat(policy.requireApprovedOperation(transfer.getId())).isSameAs(transfer);
            assertThatThrownBy(() -> policy.require(transfer.getId(), Action.APPROVE))
                .isInstanceOf(AccessDeniedException.class);
        });
        verifyNoInteractions(scopes);
    }
    @Test void approvedOperationRequiresPermitForThisExactModuleAndTransfer() {
        assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId())).hasMessageContaining("approved operation");
        ApprovedOperationContext.run(TransferApproveOperationHandler.MODULE_KEY, UUID.randomUUID(), () ->
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId())).hasMessageContaining("approved operation"));
        ApprovedOperationContext.run("INVENTORY_PERIOD_CLOSE", transfer.getId(), () ->
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId())).hasMessageContaining("approved operation"));
        verifyNoInteractions(transfers);
    }
    @Test void approvedOperationStillRejectsThePreparer() {
        transfer.setStatus("SUBMITTED"); transfer.setCreatedBy("operator");
        ApprovedOperationContext.run(TransferApproveOperationHandler.MODULE_KEY, transfer.getId(), () -> {
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId()))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("own transfer");
            transfer.setCreatedBy("creator"); transfer.setUpdatedBy("operator");
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId()))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("own transfer");
        });
    }
    @Test void approvedOperationStillRequiresSubmittedState() {
        ApprovedOperationContext.run(TransferApproveOperationHandler.MODULE_KEY, transfer.getId(), () ->
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId()))
                .isInstanceOf(com.enterprise.mm.error.ConflictException.class));
    }
    @Test void approvedOperationCannotCrossCompanyOrTenant() {
        ApprovedOperationContext.run(TransferApproveOperationHandler.MODULE_KEY, transfer.getId(), () -> {
            CompanyContext.setCompany(UUID.randomUUID());
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId()))
                .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
            CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
            TenantContext.setTenant("another-tenant");
            assertThatThrownBy(() -> policy.requireApprovedOperation(transfer.getId()))
                .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        });
    }
    private void authenticate(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
