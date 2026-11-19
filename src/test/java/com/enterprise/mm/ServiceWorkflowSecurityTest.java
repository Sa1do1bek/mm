package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.payload.ServiceAcceptanceActCreateRequest;
import com.enterprise.mm.payload.ServiceClaimCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionCreateRequest;
import com.enterprise.mm.payload.ServiceOrderCreateRequest;
import com.enterprise.mm.payload.ServiceRequestCreateRequest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ServiceWorkflowSecurityTest {
    @Autowired MockMvc mockMvc;

    @Test
    void viewerCanReadServiceOptions() throws Exception {
        mockMvc.perform(get("/api/service-acts/options")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void viewerCanReadOverdueExecutions() throws Exception {
        mockMvc.perform(get("/api/service-executions/overdue")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void viewerCannotSendServiceActToPayment() throws Exception {
        mockMvc.perform(post("/api/service-acts/" + UUID.randomUUID() + "/send-to-payment")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentReference\":\"PAY-1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateServiceOrder() throws Exception {
        mockMvc.perform(post("/api/services")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "SEC-SVC-1",
                      "name": "Security validation",
                      "supplierId": "%s",
                      "serviceType": "WORK",
                      "totalAmount": 10,
                      "currencyCode": "UZS"
                    }
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateServiceOrderViaAlias() throws Exception {
        mockMvc.perform(post("/api/service-orders")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "SEC-SVC-2",
                      "name": "Security validation alias",
                      "supplierId": "%s",
                      "serviceType": "WORK",
                      "totalAmount": 10,
                      "currencyCode": "UZS"
                    }
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateServiceRequest() throws Exception {
        mockMvc.perform(post("/api/service-requests")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Blocked request"
                    }
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateServiceExecution() throws Exception {
        mockMvc.perform(post("/api/service-executions")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "serviceRequestId": "%s"
                    }
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateServiceClaim() throws Exception {
        mockMvc.perform(post("/api/service-claims")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "serviceRequestId": "%s",
                      "supplierId": "%s",
                      "assignedTo": "%s"
                    }
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void linkedSupplierCannotRaiseOrEditClaimAgainstItselfAndGenericLifecycleRoutesAreAbsent() throws Exception {
        UUID claimId = UUID.randomUUID();
        String body = """
            {
              "serviceRequestId": "%s",
              "supplierId": "%s",
              "assignedTo": "%s",
              "claimType": "OTHER"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/service-claims")
                .with(user("supplier").roles("SUPPLIER_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/service-claims/" + claimId)
                .with(user("supplier").roles("SUPPLIER_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/service-claims/" + claimId + "/send")
                .with(user("supplier").roles("SUPPLIER_MANAGER")))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/service-claims/" + claimId + "/close")
                .with(user("supplier").roles("SUPPLIER_MANAGER")))
            .andExpect(status().isNotFound());
    }

    @Test
    void genericClaimLifecycleRoutesAreAbsentEvenForAdministrators() throws Exception {
        UUID claimId = UUID.randomUUID();

        for (String transition : List.of("send", "start-resolution", "resolve", "reject", "close")) {
            mockMvc.perform(post("/api/service-claims/" + claimId + "/" + transition)
                    .with(user("admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void supplierRoleCanReachOnlyCanonicalClaimResponseTransitions() throws Exception {
        UUID claimId = UUID.randomUUID();
        String command = """
            {
              "expectedVersion": 0,
              "reason": "Supplier response"
            }
            """;

        mockMvc.perform(post("/api/workspaces/services-acceptance/claims/" + claimId + "/start-resolution")
                .with(user("supplier").roles("SUPPLIER_MANAGER"))
                .header("Idempotency-Key", "claim-start-security")
                .contentType(MediaType.APPLICATION_JSON)
                .content(command))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/services-acceptance/claims/" + claimId + "/resolve")
                .with(user("supplier").roles("SUPPLIER_MANAGER"))
                .header("Idempotency-Key", "claim-resolve-security")
                .contentType(MediaType.APPLICATION_JSON)
                .content(command))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/services-acceptance/claims/" + claimId + "/reject")
                .with(user("supplier").roles("SUPPLIER_MANAGER"))
                .header("Idempotency-Key", "claim-reject-security")
                .contentType(MediaType.APPLICATION_JSON)
                .content(command))
            .andExpect(status().isNotFound());
    }

    @Test
    void serviceWorkflowCreateDtosDoNotExposeWritableStatus() {
        assertFalse(hasStatusField(ServiceRequestCreateRequest.class));
        assertFalse(hasStatusField(ServiceExecutionCreateRequest.class));
        assertFalse(hasStatusField(ServiceClaimCreateRequest.class));
        assertFalse(hasStatusField(ServiceAcceptanceActCreateRequest.class));
        assertFalse(hasStatusField(ServiceOrderCreateRequest.class));
    }

    private boolean hasStatusField(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .anyMatch(field -> "status".equals(field.getName()));
    }
}
