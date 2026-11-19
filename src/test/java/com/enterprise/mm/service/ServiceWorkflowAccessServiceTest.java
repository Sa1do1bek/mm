package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ServiceRequestApprovalAdapter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ServiceWorkflowAccessServiceTest {
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ApprovalInstanceRepository approvalInstanceRepository;
    @Mock private ApprovalTaskRepository approvalTaskRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierPortalAccountRepository supplierPortalAccountRepository;

    private ServiceWorkflowAccessService service;

    @BeforeEach
    void setUp() {
        service = new ServiceWorkflowAccessService(
            roleDataScopeService,
            approvalInstanceRepository,
            approvalTaskRepository,
            userRepository,
            supplierPortalAccountRepository
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void procurementManagerHasFullAccess() {
        authenticate("manager", "PROCUREMENT_MANAGER");

        assertTrue(service.hasFullAccess());
    }

    @Test
    void responsibleUserCanAccessServiceRequest() {
        UUID responsibleUserId = UUID.randomUUID();
        authenticate("specialist", "PROCUREMENT_SPECIALIST");
        mockCurrentUser(responsibleUserId, "specialist");
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.restricted(true, false));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(false);

        ServiceRequest request = new ServiceRequest();
        request.setId(UUID.randomUUID());
        request.setResponsibleUserId(responsibleUserId);

        assertTrue(service.canAccessServiceRequest(request));
    }

    @Test
    void pendingApproverRoleCanAccessServiceRequest() {
        authenticate("approver", "PROCUREMENT_SPECIALIST");
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.restricted(true, false));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(false);

        UUID requestId = UUID.randomUUID();
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(UUID.randomUUID());
        instance.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        instance.setCurrentStepOrder(2);
        ApprovalTask task = new ApprovalTask();
        task.setStepOrder(2);
        task.setTaskStatus(ApprovalTaskStatus.PENDING);
        task.setAssigneeType(ApprovalAssigneeType.ROLE);
        task.setAssigneeRole("PROCUREMENT_SPECIALIST");

        when(approvalInstanceRepository.findFirstByTenantIdAndModuleKeyAndEntityIdAndApprovalStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            "default",
            ServiceRequestApprovalAdapter.MODULE_KEY,
            requestId,
            ApprovalInstanceStatus.PENDING
        )).thenReturn(Optional.of(instance));
        when(approvalTaskRepository.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            "default",
            instance.getId()
        )).thenReturn(List.of(task));

        ServiceRequest request = new ServiceRequest();
        request.setId(requestId);

        assertTrue(service.canAccessServiceRequest(request));
    }

    @Test
    void unrelatedScopedUserCannotAccessServiceRequest() {
        authenticate("viewer", "VIEWER");
        mockCurrentUser(UUID.randomUUID(), "viewer");
        when(roleDataScopeService.currentScope()).thenReturn(new RoleDataScopeService.DataScope(true, false, false, Set.of("viewer"), Set.of(), Set.of(), null, null));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(false);
        when(approvalInstanceRepository.findFirstByTenantIdAndModuleKeyAndEntityIdAndApprovalStatusAndIsDeletedFalseOrderByCreatedAtDesc(
            org.mockito.ArgumentMatchers.eq("default"),
            org.mockito.ArgumentMatchers.eq(ServiceRequestApprovalAdapter.MODULE_KEY),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.empty());

        ServiceRequest request = new ServiceRequest();
        request.setId(UUID.randomUUID());
        request.setResponsibleUserId(UUID.randomUUID());

        assertFalse(service.canAccessServiceRequest(request));
    }

    @Test
    void supplierPortalSessionCanAccessLinkedServiceAcceptanceActWithoutServiceRequest() {
        UUID supplierId = UUID.randomUUID();
        String loginEmail = "portal-vendor@example.test";
        authenticateSupplierPortal(loginEmail);
        when(supplierPortalAccountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, loginEmail
        )).thenReturn(Optional.of(portalAccount(supplierId, loginEmail)));

        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(supplierId);

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setServiceOrderId(order.getId());
        act.setStatus("DRAFT");

        assertTrue(service.canAccessServiceAcceptanceAct(act, null, order));
    }

    @Test
    void supplierPortalSessionCannotAccessUnlinkedServiceAcceptanceAct() {
        UUID supplierId = UUID.randomUUID();
        String loginEmail = "portal-vendor@example.test";
        authenticateSupplierPortal(loginEmail);
        when(supplierPortalAccountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, loginEmail
        )).thenReturn(Optional.of(portalAccount(supplierId, loginEmail)));

        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(UUID.randomUUID());

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setServiceOrderId(order.getId());
        act.setStatus("DRAFT");

        assertFalse(service.canAccessServiceAcceptanceAct(act, null, order));
    }

    private com.enterprise.mm.entity.SupplierPortalAccount portalAccount(UUID supplierId, String loginEmail) {
        com.enterprise.mm.entity.SupplierPortalAccount account = new com.enterprise.mm.entity.SupplierPortalAccount();
        account.setId(UUID.randomUUID());
        account.setSupplierId(supplierId);
        account.setLoginEmail(loginEmail);
        account.setStatus("ACTIVE");
        return account;
    }

    private void authenticateSupplierPortal(String loginEmail) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("supplier:" + loginEmail, "n/a", List.of())
        );
    }

    private void authenticate(String username, String role) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private void mockCurrentUser(UUID userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        when(userRepository.findByTenantIdAndUsernameAndIsDeletedFalse("default", username)).thenReturn(Optional.of(user));
    }
}
