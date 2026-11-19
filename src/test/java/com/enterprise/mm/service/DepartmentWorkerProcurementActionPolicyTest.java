package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPlanWorkspaceActionKey;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class DepartmentWorkerProcurementActionPolicyTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void departmentWorkerCanStartCollectionForDraftDemandPlan() {
        authenticateDepartmentWorker();
        DemandPlan plan = new DemandPlan();
        plan.setStatus(DemandPlanStatus.DRAFT.name());

        assertDoesNotThrow(() -> new DemandPlanActionPolicy()
            .requireActionAllowed(plan, DemandPlanWorkspaceActionKey.START_COLLECTION));
    }

    @Test
    void departmentWorkerCanSubmitDraftPurchaseRequest() {
        authenticateDepartmentWorker();
        PurchaseRequest request = new PurchaseRequest();
        request.setStatus(PurchaseRequestStatus.DRAFT.name());

        assertDoesNotThrow(() -> new PurchaseRequestActionPolicy()
            .requireActionAllowed(request, PurchaseRequestWorkspaceActionKey.SUBMIT));
    }

    @Test
    void departmentWorkerCannotActivateApprovedDemandPlan() {
        authenticateDepartmentWorker();
        DemandPlan plan = new DemandPlan();
        plan.setStatus(DemandPlanStatus.APPROVED.name());

        assertThrows(BusinessException.class, () -> new DemandPlanActionPolicy()
            .requireActionAllowed(plan, DemandPlanWorkspaceActionKey.ACTIVATE));
    }

    @Test
    void departmentWorkerCannotCreateProcurementMethodDecision() {
        authenticateDepartmentWorker();
        PurchaseRequest request = new PurchaseRequest();
        request.setStatus(PurchaseRequestStatus.APPROVED.name());

        assertThrows(BusinessException.class, () -> new PurchaseRequestActionPolicy()
            .requireActionAllowed(request, PurchaseRequestWorkspaceActionKey.CREATE_PMD));
    }

    @Test
    void departmentHeadCannotCreateProcurementMethodDecisionWithoutProcurementRole() {
        PurchaseRequest request = new PurchaseRequest();
        request.setStatus(PurchaseRequestStatus.APPROVED.name());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "department-head", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_HEAD"))));
        assertThrows(BusinessException.class, () -> new PurchaseRequestActionPolicy()
            .requireActionAllowed(request, PurchaseRequestWorkspaceActionKey.CREATE_PMD));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "department-head", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_HEAD"),
                new SimpleGrantedAuthority("ROLE_PROCUREMENT_MANAGER"))));
        assertDoesNotThrow(() -> new PurchaseRequestActionPolicy()
            .requireActionAllowed(request, PurchaseRequestWorkspaceActionKey.CREATE_PMD));
    }

    private void authenticateDepartmentWorker() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "department-worker",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_WORKER"))
            )
        );
    }
}
