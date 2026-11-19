package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.controller.ContractInvoiceController;
import com.enterprise.mm.controller.ContractPaymentController;
import com.enterprise.mm.payload.ContractInvoiceSearchRequest;
import com.enterprise.mm.payload.ContractPaymentSearchRequest;
import com.enterprise.mm.service.ContractInvoiceService;
import com.enterprise.mm.service.ContractPaymentService;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:contract_payables_http_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ContractPayablesHttpSecurityTest {
    private static final String TENANT = "tenant-payables-security";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired MockMvc mvc;
    @MockBean ContractInvoiceService invoiceService;
    @MockBean ContractPaymentService paymentService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(invoiceService.search(any(ContractInvoiceSearchRequest.class), any(Pageable.class)))
            .thenReturn(Page.empty());
        org.mockito.Mockito.when(paymentService.search(any(ContractPaymentSearchRequest.class), any(Pageable.class)))
            .thenReturn(Page.empty());
    }

    @Test
    void accountantCanReadAndCreateInvoiceDraft() throws Exception {
        mvc.perform(get("/api/contract-invoices")
                .with(user("accountant").roles("ACCOUNTANT"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID))
            .andExpect(status().isOk());

        mvc.perform(post("/api/contract-invoices")
                .with(user("accountant").roles("ACCOUNTANT"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated());
    }

    @Test
    void financeControllerCanIssueInvoiceAndSubmitPayment() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/contract-invoices/{id}/issue", id)
                .with(user("finance").roles("FINANCE_CONTROLLER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID))
            .andExpect(status().isOk());

        mvc.perform(post("/api/contract-payments/{id}/submit-to-treasury", id)
                .with(user("finance").roles("FINANCE_CONTROLLER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{\"paymentReference\":\"TR-1\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void procurementSpecialistCanReadButCannotMutatePayables() throws Exception {
        mvc.perform(get("/api/contract-payments")
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID))
            .andExpect(status().isOk());

        mvc.perform(post("/api/contract-invoices")
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/contract-payments/{id}/submit-to-treasury", UUID.randomUUID())
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{\"paymentReference\":\"TR-1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void managementCanReadButCannotPerformFinanceMutations() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/contract-invoices/{id}", id)
                .with(user("manager").roles("MM_MANAGER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID))
            .andExpect(status().isOk());

        mvc.perform(post("/api/contract-invoices/{id}/issue", id)
                .with(user("manager").roles("MM_MANAGER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/contract-payments/{id}/mark-paid", id)
                .with(user("manager").roles("MM_MANAGER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{\"paymentReference\":\"TR-1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void accountantCannotSubmitOrConfirmTreasuryPayment() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/contract-payments/{id}/submit-to-treasury", id)
                .with(user("accountant").roles("ACCOUNTANT"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{\"paymentReference\":\"TR-1\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/contract-payments/{id}/mark-paid", id)
                .with(user("accountant").roles("ACCOUNTANT"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{\"paymentReference\":\"TR-1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void controllersDeclarePayablesOwnershipRules() {
        assertClassRule(ContractInvoiceController.class, ContractInvoiceController.READ_AUTHORIZATION);
        assertMethodRule(ContractInvoiceController.class, "create", ContractInvoiceController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractInvoiceController.class, "update", ContractInvoiceController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractInvoiceController.class, "delete", ContractInvoiceController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractInvoiceController.class, "restore", ContractInvoiceController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractInvoiceController.class, "issue", ContractInvoiceController.ISSUE_AUTHORIZATION);

        assertClassRule(ContractPaymentController.class, ContractPaymentController.READ_AUTHORIZATION);
        assertMethodRule(ContractPaymentController.class, "create", ContractPaymentController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractPaymentController.class, "update", ContractPaymentController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractPaymentController.class, "delete", ContractPaymentController.DRAFT_AUTHORIZATION);
        assertMethodRule(ContractPaymentController.class, "restore", ContractPaymentController.DRAFT_AUTHORIZATION);
        assertMethodRule(
            ContractPaymentController.class,
            "submitToTreasury",
            ContractPaymentController.TREASURY_SUBMIT_AUTHORIZATION
        );
        assertMethodRule(
            ContractPaymentController.class,
            "markPaid",
            ContractPaymentController.PAYMENT_CONFIRMATION_AUTHORIZATION
        );
    }

    private void assertClassRule(Class<?> controller, String expected) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo(expected);
    }

    private void assertMethodRule(Class<?> controller, String name, String expected) {
        Method method = java.util.Arrays.stream(controller.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo(expected);
    }
}
