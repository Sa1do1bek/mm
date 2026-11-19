package com.enterprise.mm.security;

import lombok.RequiredArgsConstructor;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private static final String[] MASTER_DATA = {
        "/api/materials/**", "/api/material-groups/**", "/api/uom/**", "/api/currencies/**", "/api/currency-rates/**", "/api/exchange-rates/**",
        "/api/classifiers/**", "/api/classifier-types/**", "/api/valuation-methods/**", "/api/ikpu-codes/**", "/api/mxik/**", "/api/tnved-codes/**",
        "/api/incoterms/**",
        "/api/equipment/**", "/api/material-analogs/**", "/api/material-equipment-compatibilities/**", "/api/material-consumption-norms/**",
        "/api/material-master-requests/**", "/api/material-quality-issues/**", "/api/material-duplicate-candidates/**", "/api/material-attribute-templates/**",
        "/api/departments/**", "/api/department-types/**", "/api/positions/**", "/api/employees/**"
    };
    private static final String[] REFERENCE_GOVERNANCE = {
        "/api/manufacturers/**", "/api/reference-mappings/**", "/api/chart-of-accounts/**"
    };
    private static final String[] QMS = {"/api/qms/**", "/api/v1/qms/**"};
    private static final String[] BUDGET = {"/api/budget-items/**"};
    private static final String[] PROCUREMENT = {
        "/api/mrp/**", "/api/demand-planning-cycles/**", "/api/demand-plans/**", "/api/demand-plan-items/**", "/api/consolidated-demands/**",
        "/api/procurement/category-strategies/**", "/api/procurement/plans/**", "/api/procurement/campaigns/**",
        "/api/procurement/method-decisions/**", "/api/procurement/procedures/**",
        "/api/procurement/method-capabilities/**",
        "/api/purchase-requests/**", "/api/rfq/**", "/api/quotations/**",
        "/api/purchase-orders/**", "/api/contracts/**", "/api/services/**",
        "/api/delivery-schedules/**", "/api/purchase-order-revisions/**",
        "/api/asn/**", "/api/asn-items/**", "/api/import-deliveries/**", "/api/import-freight-bookings/**",
        "/api/import-insurance-policies/**", "/api/customs-declarations/**",
        "/api/landed-cost-components/**", "/api/landed-logistics-events/**",
        "/api/service-acts/**", "/api/supplier-certificates/**", "/api/quotation-items/**",
        "/api/rfq-items/**", "/api/purchase-request-items/**", "/api/purchase-order-items/**",
        "/api/contract-specifications/**", "/api/contract-addendums/**",
        "/api/contract-powers-of-attorney/**", "/api/contract-reconciliation-acts/**",
        "/api/service-acceptance-acts/**",
        "/api/approval-workflows/**", "/api/approval-steps/**",
        "/api/approval-flows/**", "/api/approval-flow-steps/**", "/api/approval-tasks/**", "/api/approval-instances/**"
    };
    private static final String[] PAYABLES = {
        "/api/contract-invoices/**", "/api/contract-payments/**"
    };
    private static final String[] PAYABLES_READ_ROLES = {
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
        "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER"
    };
    private static final String[] PAYABLES_DRAFT_ROLES = {
        "ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"
    };
    private static final String[] PAYABLES_FINANCE_CONTROLLER_ROLES = {
        "ADMIN", "FINANCE_CONTROLLER"
    };
    private static final String[] PURCHASE_ORDER_READ = {
        "/api/purchase-orders/**", "/api/purchase-order-items/**", "/api/purchase-order-revisions/**"
    };
    // Department demand owners follow their requests, not the company sourcing registries.
    private static final String[] DEPARTMENT_PROCUREMENT_READ = {
        "/api/demand-planning-cycles/**", "/api/demand-plans/**", "/api/demand-plan-items/**",
        "/api/purchase-requests/**", "/api/purchase-request-items/**",
        "/api/services/**", "/api/service-acts/**", "/api/service-acceptance-acts/**"
    };
    private static final String[] DEPARTMENT_SCOPED_CRUD = {
        "/api/purchase-requests/**",
        "/api/demand-plans/**",
        "/api/demand-plan-items/**",
        "/api/service-requests/**"
    };
    private static final String[] SCOPED_REFERENCE_CRUD = {
        "/api/equipment/**",
        "/api/positions/**",
        "/api/material-consumption-norms/**"
    };
    private static final String[] DEPARTMENT_SCOPED_CRUD_ROLES = {
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
        "DEPARTMENT_HEAD", "DEPARTMENT_WORKER"
    };
    private static final String[] TENDER = {"/api/tender/**"};
    private static final String[] SUPPLIERS = {
        "/api/suppliers/**", "/api/supplier-evaluations/**", "/api/supplier-letters/**", "/api/supplier-licenses/**",
        "/api/supplier-blacklist/**", "/api/supplier-portal/**", "/api/supplier-portal-accounts/**",
        "/api/supplier-portal-submissions/**", "/api/supplier-kyc-checks/**", "/api/supplier-related-parties/**",
        "/api/supplier-corrective-actions/**", "/api/supplier-accreditations/**",
        "/api/supplier-accreditation-documents/**", "/api/supplier-accreditation-decisions/**"
    };
    private static final String[] WAREHOUSE = {
        "/api/stock/**", "/api/inventory/**", "/api/warehouses/**", "/api/storage-locations/**", "/api/storage-bins/**",
        "/api/inventory-items/**", "/api/warehouse-identification/**", "/api/rfid-tags/**", "/api/mobile/warehouse/**",
        "/api/material-reservations/**", "/api/workspaces/warehouse-execution/**", "/api/wms/**"
    };
    private static final String[] DEPARTMENT_WAREHOUSE_READ = {
        "/api/material-reservations/**",
        "/api/inventory", "/api/inventory/{id:[0-9a-fA-F-]{36}}/**", "/api/inventory-items/**",
        "/api/inventory/commissions/*",
        "/api/warehouses", "/api/warehouses/*", "/api/storage-locations", "/api/storage-locations/*",
        "/api/storage-bins", "/api/storage-bins/*",
        "/api/workspaces/warehouse-execution/selectors/**",
        "/api/workspaces/warehouse-execution/issue-requests/*",
        "/api/workspaces/warehouse-execution/reservations/*",
        "/api/workspaces/warehouse-execution/returns/*"
    };
    private static final String[] WAREHOUSE_SOURCE_ACTIVATION_APPLY = {
        "/api/warehouse/source-activations/apply",
        "/api/warehouse/source-activations/approve",
        "/api/warehouse/source-activations/plans",
        "/api/warehouse/source-activations/plans/**"
    };
    private static final String[] WAREHOUSE_SOURCE_ACTIVATION_READ = {
        "/api/warehouse/source-activations/preview",
        "/api/warehouse/source-activations/preview/**",
        "/api/warehouse/source-activations/plans",
        "/api/warehouse/source-activations/plans/**"
    };
    private static final String[] WAREHOUSE_OPERATIONS = {
        "/api/warehouse/**"
    };
    private static final String[] ASSET = {
        "/api/asset-management/settings/**", "/api/asset-acceptances/**", "/api/fixed-assets/**",
        "/api/asset-handovers/**", "/api/asset-verifications/**", "/api/asset-opening-imports/**"
    };
    private static final String[] ASSET_READ_ROLES = {
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
        "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER",
        "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER"
    };
    private static final String[] ASSET_ACCEPTANCE_ROLES = {
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER"
    };
    private static final String[] ASSET_QUALITY_DECISION_ROLES = {
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD"
    };
    private static final String[] ASSET_NUMBERING_ROLES = {
        "ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER"
    };
    private static final String[] ASSET_HANDOVER_REQUEST_ROLES = {
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD"
    };
    private static final String[] ASSET_HANDOVER_TRANSFER_ROLES = {
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER",
        "DEPARTMENT_HEAD", "DEPARTMENT_WORKER"
    };
    private static final String[] ASSET_COMMISSION_REPAIR_ROLES = {
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "DEPARTMENT_HEAD"
    };
    private static final String[] ASSET_VERIFICATION_ROLES = {
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "AUDITOR"
    };
    private static final String[] ASSET_DISPOSAL_IMPORT_ROLES = {
        "ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER"
    };
    private static final String[] QUALITY = {"/api/quality/**"};
    private static final String[] FINANCE = {"/api/valuation/**", "/api/valuation-documents/**", "/api/inventory-finance/**", "/api/inventory-valuation/**", "/api/v1/inventory-valuation/**", "/api/market-prices/**"};
    private static final String[] NON_MOVING = {"/api/non-moving-stock/**", "/api/non-moving-stock-cases/**"};
    private static final String[] ILLIQUID_STOCK = {"/api/illiquid-stock/**"};
    private static final String[] REPORTS = {"/api/reports/**"};
    private static final String[] DATA_MIGRATION = {"/api/migration/**"};
    private static final String[] INTEGRATIONS = {"/api/integrations/**"};
    private static final String[] HR = {"/api/hr/**"};
    private static final String[] NOTIFICATIONS = {"/api/notifications/**", "/api/v1/notifications/**"};
    private static final String[] NOTIFICATION_DEVICES = {
        "/api/notification-devices/**",
        "/api/v1/notification-devices/**"
    };
    private static final String[] ATTACHMENTS = {"/api/attachments/**"};
    private static final String[] DOCUMENT_AI = {"/api/document-ai/**"};
    private static final String[] PRICE_AI = {"/api/price-ai/**"};
    private static final String[] AI_ANALYSIS_JOBS = {"/api/ai-analysis-jobs/**"};
    private static final String[] AI_COPILOT = {"/api/mm/ai/copilot/**"};
    private static final String[] SUPPLY_WORKSPACE_READ_ROLES = {
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
        "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER",
        "SUPPLIER_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER", "EXECUTIVE",
        "AUDITOR", "VIEWER"
    };
    private static final String[] SUPPLY_WORKSPACE_MANAGER_ROLES = {
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER"
    };
    private static final String SUPPLY_WORKSPACE_CLAIM =
        "/api/workspaces/supply/commitments/*/claim";
    private static final String[] SUPPLY_WORKSPACE_MANAGER_MUTATIONS = {
        "/api/workspaces/supply/commitments/*/assign",
        "/api/workspaces/supply/commitments/*/escalate"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final TenantFilter tenantFilter;
    private final ApiSecurityErrorHandler securityErrorHandler;
    private final UserDetailsService userDetailsService;
    private final com.enterprise.mm.erp.OneCApiAccess oneCApiAccess;

    
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(securityErrorHandler)
                .accessDeniedHandler(securityErrorHandler)
            )
            .authorizeHttpRequests(auth -> auth
                // The authenticated REQUEST has already authorized and produced this stream.
                // Stateless JWT filters do not reauthenticate the container's ASYNC redispatch.
                .requestMatchers(request -> request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC
                    && "GET".equals(request.getMethod())
                    && (request.getRequestURI().equals(request.getContextPath() + "/api/stock/balances/export")
                    || request.getRequestURI().equals(request.getContextPath() + "/api/erp-history-archive/export"))).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
                .requestMatchers("/api/auth/me", "/api/auth/me/**", "/api/auth/refresh", "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/integrations/one-c/events", "/api/integrations/one-c/tasks/poll", "/api/integrations/one-c/tasks/*/result", "/api/integrations/one-c/warehouse/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/integrations/treasury/tasks/poll", "/api/integrations/treasury/tasks/*/result", "/api/integrations/treasury/events").permitAll()
                .requestMatchers("/api/treasury/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/integrations/one-c/health").permitAll()
                .requestMatchers("/api/auth/**", "/api/public/supplier-portal/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/deployment").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/enums", "/api/*/enums", "/api/*/enums/*").permitAll()
                .requestMatchers("/api/analytics/summary").authenticated()
                .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/companies/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/audit/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/commissions/**").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers("/api/commissions/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.GET, "/api/segregation-of-duty-violations/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/segregation-of-duty-violations/**").hasRole("ADMIN")
                .requestMatchers("/api/users/**", "/api/roles/**", "/api/segregation-of-duty-rules/**").hasRole("ADMIN")
                .requestMatchers("/api/settings/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/production-evidence/**").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/production-evidence/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/reports/import/stock-balances")
                    .hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/reports/import/**")
                    .hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.GET, "/api/exchange-rates/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/exchange-rates/**").hasAnyRole("ADMIN", "MM_MANAGER")

                .requestMatchers(HttpMethod.POST, SUPPLY_WORKSPACE_CLAIM).hasRole("PROCUREMENT_SPECIALIST")
                .requestMatchers(HttpMethod.POST, SUPPLY_WORKSPACE_MANAGER_MUTATIONS).hasAnyRole(SUPPLY_WORKSPACE_MANAGER_ROLES)
                .requestMatchers(HttpMethod.GET, "/api/workspaces/supply/**").hasAnyRole(SUPPLY_WORKSPACE_READ_ROLES)
                .requestMatchers("/api/workspaces/supply/**").denyAll()

                .requestMatchers(HttpMethod.POST, "/api/demand-plans").hasAnyRole("ADMIN", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                // Reserving stock is a warehouse command, even when entered from a department demand line.
                .requestMatchers(HttpMethod.POST, "/api/demand-plan-items/*/reserve")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")
                .requestMatchers(HttpMethod.POST, "/api/demand-plan-items/*/approve", "/api/demand-plan-items/*/reject")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "DEPARTMENT_HEAD")
                .requestMatchers(HttpMethod.POST, DEPARTMENT_SCOPED_CRUD).hasAnyRole(DEPARTMENT_SCOPED_CRUD_ROLES)
                .requestMatchers(HttpMethod.PUT, DEPARTMENT_SCOPED_CRUD).hasAnyRole(DEPARTMENT_SCOPED_CRUD_ROLES)
                .requestMatchers(HttpMethod.DELETE, DEPARTMENT_SCOPED_CRUD).hasAnyRole(DEPARTMENT_SCOPED_CRUD_ROLES)
                .requestMatchers(HttpMethod.POST, SCOPED_REFERENCE_CRUD).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST")
                .requestMatchers(HttpMethod.PUT, SCOPED_REFERENCE_CRUD).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST")
                .requestMatchers(HttpMethod.DELETE, SCOPED_REFERENCE_CRUD).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST")
                .requestMatchers(HttpMethod.GET, "/api/uom/**").hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.GET, "/api/materials/*/command-view/stock-detail",
                    "/api/materials/*/command-view/receipt-origins",
                    "/api/materials/*/command-view/movements/*/allocations")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD",
                        "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.GET, MASTER_DATA).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(MASTER_DATA).hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.GET, REFERENCE_GOVERNANCE).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.POST, "/api/chart-of-accounts/**").hasAnyRole("ADMIN", "FINANCE_CONTROLLER")
                .requestMatchers(HttpMethod.PUT, "/api/chart-of-accounts/**").hasAnyRole("ADMIN", "FINANCE_CONTROLLER")
                .requestMatchers(HttpMethod.DELETE, "/api/chart-of-accounts/**").hasAnyRole("ADMIN", "FINANCE_CONTROLLER")
                .requestMatchers(REFERENCE_GOVERNANCE).hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.GET, QMS).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "SUPPLIER_MANAGER", "FINANCE_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers(QMS).hasAnyRole("ADMIN", "MM_MANAGER", "SUPPLIER_MANAGER", "PROCUREMENT_MANAGER")
                .requestMatchers(HttpMethod.GET, BUDGET).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(BUDGET).hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER")

                .requestMatchers(HttpMethod.GET, PURCHASE_ORDER_READ).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.POST, "/api/purchase-orders/*/receive").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")
                // The worklist and detail queries are assignment-scoped by ApprovalEngineService.
                // Do not reject a specifically assigned approver merely because their role is
                // absent from a global management allow-list.
                .requestMatchers(HttpMethod.GET, "/api/approval-tasks/**").authenticated()
                // Approval timelines are embedded in both procurement and warehouse workspaces.
                // Keep this rule ahead of the broader PROCUREMENT matcher so warehouse users
                // can read timelines for movements they are already authorized to view.
                .requestMatchers(HttpMethod.GET, "/api/approval-instances/**").hasAnyRole(
                    "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
                    "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER",
                    "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER",
                    "DEPARTMENT_HEAD", "DEPARTMENT_WORKER"
                )
                // Mutations still require exact assignment and current-step authorization in the
                // domain service; authentication here only lets the assignee reach that check.
                .requestMatchers(HttpMethod.POST, "/api/approval-tasks/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/approval-flows/**", "/api/approval-flow-steps/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/approval-flows/**", "/api/approval-flow-steps/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/approval-flows/**", "/api/approval-flow-steps/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, PAYABLES).hasAnyRole(PAYABLES_READ_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/contract-invoices/*/issue").hasAnyRole(PAYABLES_FINANCE_CONTROLLER_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/contract-payments/*/submit-to-treasury").hasAnyRole(PAYABLES_FINANCE_CONTROLLER_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/contract-payments/*/mark-paid").hasAnyRole(PAYABLES_FINANCE_CONTROLLER_ROLES)
                .requestMatchers(HttpMethod.POST, PAYABLES).hasAnyRole(PAYABLES_DRAFT_ROLES)
                .requestMatchers(HttpMethod.PUT, PAYABLES).hasAnyRole(PAYABLES_DRAFT_ROLES)
                .requestMatchers(HttpMethod.DELETE, PAYABLES).hasAnyRole(PAYABLES_DRAFT_ROLES)
                .requestMatchers(HttpMethod.GET, DEPARTMENT_PROCUREMENT_READ).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.GET, PROCUREMENT).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers(PROCUREMENT).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST")
                .requestMatchers(HttpMethod.GET, TENDER).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers(TENDER).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST")
                // The supplier directory remains a lookup for department documents; governance does not.
                .requestMatchers(HttpMethod.GET, "/api/suppliers", "/api/suppliers/*").hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "SUPPLIER_MANAGER", "COMPLIANCE_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.GET, SUPPLIERS).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "SUPPLIER_MANAGER", "COMPLIANCE_MANAGER", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/api/supplier-accreditations/*/run-compliance-check")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "SUPPLIER_MANAGER", "COMPLIANCE_MANAGER")
                .requestMatchers(SUPPLIERS).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "SUPPLIER_MANAGER")

                // Order extraction is available to authenticated users; its draft, job and order
                // services enforce company scope and owner access. Keep this ahead of WAREHOUSE.
                .requestMatchers("/api/inventory/ai-drafts", "/api/inventory/ai-drafts/**",
                    "/api/inventory/orders", "/api/inventory/orders/**").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/inventory/commissions/*").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/inventory/*/counts", "/api/inventory/*/scan-count", "/api/inventory-items/*/count", "/api/inventory-items/*/count-evidence")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.POST, "/api/warehouse-reports/overview/approve-all", "/api/warehouse-reports/overview/*/approve")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD")
                .requestMatchers(HttpMethod.GET, "/api/warehouse-reports/**", "/api/warehouse-reports").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.GET, "/api/erp-exchange/**", "/api/unit-marks/**", "/api/warehouse-identification/**").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/api/erp-exchange/**", "/api/unit-marks/**", "/api/warehouse-identification/**").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.PUT, "/api/erp-exchange/**").hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers("/api/rfid-tags/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/inventory/optimization/*/apply-to-material").hasAnyRole("ADMIN", "MM_MANAGER")
                // Warehouse master setup and responsibility assignment are administrative duties.
                .requestMatchers(HttpMethod.POST, "/api/warehouses", "/api/warehouses/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/warehouses/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/warehouses/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.GET, DEPARTMENT_WAREHOUSE_READ).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.GET, WAREHOUSE).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER")
                .requestMatchers("/api/inventory/vmi-agreements/**", "/api/inventory/consignment-transactions/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(WAREHOUSE).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER")
                .requestMatchers(HttpMethod.POST, WAREHOUSE_SOURCE_ACTIVATION_APPLY)
                    .hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.GET, WAREHOUSE_SOURCE_ACTIVATION_READ)
                    .hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "AUDITOR", "VIEWER")
                // Saving one's display threshold grants no stock or company-policy mutation rights.
                .requestMatchers(HttpMethod.PUT, "/api/warehouse/non-moving/preferences/me")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.GET, WAREHOUSE_OPERATIONS)
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER")
                .requestMatchers(WAREHOUSE_OPERATIONS)
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.GET, "/api/asset-opening-imports/**").hasAnyRole(ASSET_DISPOSAL_IMPORT_ROLES)
                .requestMatchers(HttpMethod.GET, "/api/fixed-assets/**", "/api/asset-handovers/**").hasAnyRole(
                    "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
                    "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER",
                    "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(HttpMethod.GET, ASSET).hasAnyRole(ASSET_READ_ROLES)
                .requestMatchers("/api/asset-management/settings/**").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/asset-acceptances/*/quality-decisions").hasAnyRole(ASSET_QUALITY_DECISION_ROLES)
                .requestMatchers("/api/asset-acceptances/**").hasAnyRole(ASSET_ACCEPTANCE_ROLES)
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/fixed-assets/*/inventory-number",
                    "/api/fixed-assets/*/inventory-number/correction",
                    "/api/fixed-assets/inventory-numbers/allocate"
                ).hasAnyRole(ASSET_NUMBERING_ROLES)
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/fixed-assets/*/commission",
                    "/api/fixed-assets/bulk-commission",
                    "/api/fixed-assets/*/mark-idle",
                    "/api/fixed-assets/*/reactivate",
                    "/api/fixed-assets/*/send-to-repair",
                    "/api/fixed-assets/*/complete-repair",
                    "/api/fixed-assets/*/link-equipment",
                    "/api/fixed-assets/*/unlink-equipment"
                ).hasAnyRole(ASSET_COMMISSION_REPAIR_ROLES)
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/fixed-assets/*/return-to-supplier",
                    "/api/fixed-assets/*/dispose"
                ).hasAnyRole(ASSET_DISPOSAL_IMPORT_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/asset-handovers/*/dispatch", "/api/asset-handovers/*/acknowledge")
                    .hasAnyRole(ASSET_HANDOVER_TRANSFER_ROLES)
                .requestMatchers("/api/asset-handovers/**").hasAnyRole(ASSET_HANDOVER_REQUEST_ROLES)
                .requestMatchers("/api/asset-verifications/**").hasAnyRole(ASSET_VERIFICATION_ROLES)
                .requestMatchers("/api/asset-opening-imports/**").hasAnyRole(ASSET_DISPOSAL_IMPORT_ROLES)
                .requestMatchers(HttpMethod.GET, QUALITY).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "SUPPLIER_MANAGER", "FINANCE_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers("/api/quality/claims/**").hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "SUPPLIER_MANAGER")
                .requestMatchers("/api/quality/laboratory-tests/**").hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "SUPPLIER_MANAGER")
                .requestMatchers(QUALITY).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "SUPPLIER_MANAGER")
                .requestMatchers(HttpMethod.GET, FINANCE).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/api/market-prices/ai/research/jobs").hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER")
                .requestMatchers(HttpMethod.POST, "/api/inventory-finance/period-closes/*/reopen").hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")
                .requestMatchers(FINANCE).hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.POST, "/api/inventory-impairments/detect", "/api/v1/inventory-impairments/detect", "/api/inventory-impairments/*/propose", "/api/v1/inventory-impairments/*/propose").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.POST, "/api/inventory-impairments/*/review", "/api/inventory-impairments/*/approve", "/api/inventory-impairments/*/reject", "/api/inventory-impairments/*/post", "/api/v1/inventory-impairments/*/review", "/api/v1/inventory-impairments/*/approve", "/api/v1/inventory-impairments/*/reject", "/api/v1/inventory-impairments/*/post").hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.POST, "/api/non-moving-stock-cases/*/reopen").hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/non-moving-stock-cases/*/assign", "/api/non-moving-stock-cases/*/review-due-date", "/api/non-moving-stock-cases/*/start-review").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD")
                .requestMatchers(HttpMethod.POST, "/api/non-moving-stock-cases/*/decisions").hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.POST, "/api/non-moving-stock-cases/*/decisions/*/execute").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.POST, "/api/non-moving-stock-cases/workspace/saved-views").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.DELETE, "/api/non-moving-stock-cases/workspace/saved-views/*").hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(HttpMethod.GET, NON_MOVING).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(NON_MOVING).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.GET, ILLIQUID_STOCK).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(ILLIQUID_STOCK).hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT")

                .requestMatchers(HttpMethod.GET, REPORTS).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER", "PROCUREMENT_MANAGER", "EXECUTIVE", "AUDITOR", "VIEWER")
                .requestMatchers(REPORTS).hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.GET, "/api/migration/finished-goods-opening/preview")
                    .hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")
                .requestMatchers(HttpMethod.GET, DATA_MIGRATION).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers(DATA_MIGRATION).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")
                .requestMatchers(HttpMethod.GET, "/api/integrations/preflight").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/settings/integrations/**")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "INTEGRATION_OPERATOR")
                .requestMatchers(HttpMethod.PUT, "/api/settings/integrations/**")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "INTEGRATION_OPERATOR")
                .requestMatchers(HttpMethod.POST, "/api/settings/integrations/**")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "INTEGRATION_OPERATOR")
                .requestMatchers(HttpMethod.GET, "/api/integrations/faktura/**")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR",
                        "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "PROCUREMENT_MANAGER", "FINANCE_CONTROLLER", "AUDITOR")
                .requestMatchers(HttpMethod.POST, "/api/integrations/faktura/**")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "INTEGRATION_OPERATOR", "FINANCE_CONTROLLER")
                .requestMatchers(HttpMethod.POST, "/api/integrations/callbacks/rfq-mail/events").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/integrations/kimyosanoat/events").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/integrations/callbacks/**").hasAnyRole("ADMIN", "INTEGRATION_OPERATOR")
                .requestMatchers(HttpMethod.POST, "/api/integrations/wms/material-baseline")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "INTEGRATION_OPERATOR")
                .requestMatchers(HttpMethod.POST, "/api/integrations/wms/material-snapshots")
                    .hasAnyRole("ADMIN", "MM_MANAGER", "INTEGRATION_OPERATOR")
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/integrations/events",
                    "/api/integrations/events/**"
                ).hasAnyRole("ADMIN", "INTEGRATION_OPERATOR")
                .requestMatchers(
                    HttpMethod.PUT,
                    "/api/integrations/events/**"
                ).hasAnyRole("ADMIN", "INTEGRATION_OPERATOR")
                .requestMatchers(
                    HttpMethod.DELETE,
                    "/api/integrations/events/**"
                ).hasAnyRole("ADMIN", "INTEGRATION_OPERATOR")
                .requestMatchers(HttpMethod.GET, INTEGRATIONS).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers(INTEGRATIONS).hasAnyRole("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER")
                .requestMatchers(HttpMethod.GET, HR).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "WAREHOUSE_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER")
                .requestMatchers(HR).hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(HttpMethod.GET, ATTACHMENTS).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(ATTACHMENTS).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "SUPPLIER_MANAGER")
                .requestMatchers(DOCUMENT_AI).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(PRICE_AI).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(AI_ANALYSIS_JOBS).hasAnyRole("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
                .requestMatchers(AI_COPILOT).authenticated()
                .requestMatchers(HttpMethod.GET, NOTIFICATIONS).authenticated()
                .requestMatchers(HttpMethod.POST, "/api/notifications/read-all", "/api/v1/notifications/read-all").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/notifications/*/read", "/api/v1/notifications/*/read").authenticated()
                .requestMatchers(NOTIFICATIONS).hasAnyRole("ADMIN", "MM_MANAGER")
                .requestMatchers(NOTIFICATION_DEVICES).authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new OneCMachineAuthenticationFilter(oneCApiAccess, securityErrorHandler), JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${app.cors.allowed-origins}") String allowedOrigins,
        @Value("${app.cors.allowed-origin-patterns:}") String allowedOriginPatterns
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toList());
        configuration.setAllowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Tenant-Id", "X-Company-Id", "X-Warehouse-Id", "X-Supplier-Portal-Token", "Idempotency-Key", "X-Request-Id"));
        configuration.setExposedHeaders(Arrays.asList("Content-Disposition", "X-Request-Id"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
