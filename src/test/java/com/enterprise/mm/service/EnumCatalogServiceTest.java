package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.EnumTranslation;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.payload.EnumOptionResponse;
import com.enterprise.mm.repository.EnumTranslationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

@ExtendWith(MockitoExtension.class)
class EnumCatalogServiceTest {

    @Mock
    EnumTranslationRepository translationRepository;

    @Test
    void inventorySubtypeLabelsAreScopedAndLocalized() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());
        var options = service.enumValues("materials", "material-subtypes");
        assertThat(options).extracting(EnumOptionResponse::code).containsExactly(
            "MATERIAL", "EQUIPMENT", "ANIMALS", "FINISHED_GOODS", "GOODS", "SEMI_FINISHED_GOODS");
        assertThat(options).extracting(option -> option.labels().get("ru"))
            .startsWith("Материалы", "Оборудование", "Животные");
        assertThat(options.get(1).labels().get("en")).isEqualTo("Equipment");
        assertThat(options.get(2).labels().get("uz")).isEqualTo("Hayvonlar");
    }

    @Test
    void exposesDirectAwardModulesInBulkAndDedicatedApprovalCatalogs() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        for (List<EnumOptionResponse> options : List.of(
            service.allEnums().get("approval-workflows").get("module-keys"),
            service.moduleEnums("approval-workflows").get("module-keys"),
            service.enumValues("approval-workflows", "module-keys")
        )) {
            assertThat(options).extracting(EnumOptionResponse::code)
                .contains("PROCUREMENT_DIRECT_AWARD", "PROCUREMENT_SINGLE_SOURCE_AWARD")
                .doesNotHaveDuplicates();
        }
    }

    @Test
    void returnsAllStageOneEnumsGroupedByModuleAndType() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(service.registeredEnumNames())).thenReturn(List.of());

        Map<String, Map<String, List<EnumOptionResponse>>> catalog = service.allEnums();

        assertThat(catalog.keySet()).contains(
            "global",
            "purchase-orders",
            "purchase-requests",
            "stock-movements",
            "materials"
        );
        assertThat(catalog.get("purchase-orders")).containsKeys("statuses", "types");
        assertThat(catalog.get("purchase-orders").get("statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "APPROVAL_PENDING", "APPROVED", "REJECTED", "RETURNED", "SENT",
                "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED");
        assertThat(catalog.get("purchase-orders").get("types")).extracting(EnumOptionResponse::code)
            .containsExactly("GOODS", "SERVICE");
        assertThat(catalog.get("approval-workflows").get("request-types")).extracting(EnumOptionResponse::code)
            .containsExactly("GOODS", "SERVICE", "BOTH");
        assertThat(catalog).containsKeys("quality", "suppliers", "users", "non-moving-stock", "logistics");
        assertThat(catalog.get("quality")).containsKeys("inspection-types", "severities", "dispositions");
        assertThat(catalog.get("suppliers")).containsKeys("statuses", "kyc-check-types", "risk-levels");
        assertThat(catalog.get("users")).containsKeys("roles", "employee-statuses");
        assertThat(catalog.get("non-moving-stock")).containsKeys("case-statuses", "decision-types");
        assertThat(catalog.get("purchase-requests")).containsKeys("statuses", "request-types");
        assertThat(catalog.get("purchase-requests").get("statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "APPROVAL_PENDING", "APPROVED", "REJECTED", "RETURNED", "RFQ_CREATED",
                "PARTIALLY_SENT_TO_TENDER", "FULLY_SENT_TO_TENDER", "PO_CREATED", "CANCELLED", "CONVERTED_TO_PO");
        assertThat(catalog.get("stock-movements")).containsKeys("statuses", "movement-types");
        assertThat(catalog.get("materials")).containsKeys("statuses", "valuation-methods", "hazard-classes");
    }


    @Test
    void discoversAllBackendEnumsForGlobalCatalogAndPerModuleStatusEndpoints() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        Map<String, Map<String, List<EnumOptionResponse>>> catalog = service.allEnums();

        assertThat(catalog.get("global")).containsKeys("supplier-statuses", "approval-assignee-types");
        assertThat(catalog.get("global").get("supplier-statuses")).extracting(EnumOptionResponse::code)
            .contains("NEW", "ACTIVE", "LIMITED", "BLOCKED", "ARCHIVED");
        assertThat(service.enumValues("suppliers", "statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("NEW", "ACTIVE", "LIMITED", "BLOCKED", "ARCHIVED");
        assertThat(service.enumValues("any-module", "approval-assignee-types")).extracting(EnumOptionResponse::code)
            .contains("ROLE", "USER");
    }

    @Test
    void returnsModuleBulkEnumsFromExplicitAndInferredDefinitions() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        Map<String, List<EnumOptionResponse>> supplierEnums = service.moduleEnums("suppliers");
        Map<String, List<EnumOptionResponse>> purchaseOrderEnums = service.moduleEnums("purchase-orders");

        assertThat(supplierEnums).containsKey("statuses");
        assertThat(supplierEnums.get("statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("NEW", "ACTIVE", "LIMITED", "BLOCKED", "ARCHIVED");
        assertThat(purchaseOrderEnums).containsKeys("statuses", "types");
    }

    @Test
    void returnsDomainAliasesForAllModuleEnumApis() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        Map<String, List<EnumOptionResponse>> qualityEnums = service.moduleEnums("quality");
        Map<String, List<EnumOptionResponse>> supplierEnums = service.moduleEnums("suppliers");
        Map<String, List<EnumOptionResponse>> nonMovingStockEnums = service.moduleEnums("non-moving-stock");
        Map<String, List<EnumOptionResponse>> userEnums = service.moduleEnums("users");
        Map<String, List<EnumOptionResponse>> logisticsEnums = service.moduleEnums("logistics");

        assertThat(qualityEnums).containsKeys("inspection-types", "severities", "dispositions", "quarantine-statuses");
        assertThat(qualityEnums.get("inspection-types")).extracting(EnumOptionResponse::code)
            .containsExactly("INCOMING", "RANDOM", "CLAIM", "RETURN", "LAB_ONLY");
        assertThat(supplierEnums).containsKeys("statuses", "kyc-check-types", "risk-levels", "related-party-types");
        assertThat(supplierEnums.get("kyc-check-types")).extracting(EnumOptionResponse::code)
            .contains("TAX_REGISTRATION", "SANCTIONS", "ESG");
        assertThat(nonMovingStockEnums).containsKeys("statuses", "case-statuses", "decision-types", "execution-statuses");
        assertThat(nonMovingStockEnums.get("case-statuses")).extracting(EnumOptionResponse::code)
            .contains("DETECTED", "REVIEW_DEFERRED", "DECISION_APPROVED");
        assertThat(userEnums).containsKeys("roles", "employee-statuses");
        assertThat(userEnums.get("roles")).extracting(EnumOptionResponse::code)
            .doesNotContain("INTEGRATION_OPERATOR")
            .contains("ADMIN", "VIEWER");
        assertThat(logisticsEnums).containsKeys(
            "transport-modes",
            "landed-cost-component-types",
            "allocation-methods",
            "event-types"
        );
    }

    @Test
    void discoversEnumsFromNonFlatSubPackages() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        Map<String, List<EnumOptionResponse>> procurementCommandEnums = service.moduleEnums("procurement-command");
        Map<String, List<EnumOptionResponse>> supplyControlEnums = service.moduleEnums("supply-control");
        Map<String, List<EnumOptionResponse>> inventoryAvailabilityEnums = service.moduleEnums("inventory-availability");
        Map<String, List<EnumOptionResponse>> supplierGovernanceEnums = service.moduleEnums("supplier-governance");

        assertThat(procurementCommandEnums).containsKeys(
            "queue-keys", "stages", "blocker-severities", "allocation-modes"
        );
        assertThat(procurementCommandEnums.get("queue-keys")).extracting(EnumOptionResponse::code)
            .contains("BUYER_PREPARATION", "TENDER_BLOCKED", "PO_DISPATCH_BLOCKED", "OVERDUE_WORK");
        assertThat(procurementCommandEnums.get("blocker-severities")).extracting(EnumOptionResponse::code)
            .containsExactly("INFO", "WARNING", "BLOCKING");

        assertThat(supplyControlEnums).containsKeys("lenses", "queue-keys", "risk-keys");
        assertThat(supplyControlEnums.get("lenses")).extracting(EnumOptionResponse::code)
            .containsExactly("MY_WORK", "TEAM", "MANAGEMENT");
        assertThat(supplyControlEnums.get("queue-keys")).extracting(EnumOptionResponse::code)
            .contains("OVERDUE_COMMITMENTS", "INTEGRATION_FAILED", "UNASSIGNED");

        assertThat(inventoryAvailabilityEnums).containsKey("queue-keys");
        assertThat(inventoryAvailabilityEnums.get("queue-keys")).extracting(EnumOptionResponse::code)
            .containsExactly("SHORTAGE", "QUARANTINE_BLOCKED", "BELOW_SAFETY", "REORDER_NEEDED", "EXCESS", "HEALTHY");

        assertThat(supplierGovernanceEnums).containsKey("queue-keys");
        assertThat(supplierGovernanceEnums.get("queue-keys")).extracting(EnumOptionResponse::code)
            .contains("ACCREDITATION_REVIEW", "EXPIRING_EVIDENCE", "KYC_COMPLIANCE", "LICENSE_RISK");
    }

    @Test
    void returnsSupplementalFrontendEnumsForListsWithoutJavaEnumCodeSet() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("material-master-requests", "request-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("CREATE", "CHANGE", "MERGE", "OBSOLETE", "REACTIVATE");
        assertThat(service.enumValues("purchase-orders", "statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "APPROVAL_PENDING", "APPROVED", "REJECTED", "RETURNED", "SENT",
                "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED");
        assertThat(service.enumValues("users", "roles"))
            .extracting(EnumOptionResponse::code)
            .doesNotContain("INTEGRATION_OPERATOR")
            .contains("ADMIN", "VIEWER", "DEPARTMENT_WORKER");
        assertThat(service.enumValues("supplier-licenses", "renewal-required"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("true", "false");
        assertThat(service.allEnums().get("global").values()).anySatisfy(options ->
            assertThat(options).extracting(EnumOptionResponse::code)
                .containsExactly("CREATE", "CHANGE", "MERGE", "OBSOLETE", "REACTIVATE")
        );
    }

    @Test
    void keepsRfqAndExternalSystemSupplementalCatalogsInSyncWithRealEnums() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("rfq", "statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "AMENDMENT_DRAFT", "PUBLISHING", "OPEN", "BIDS_OPENED",
                "CONFLICT_CHECKED", "EVALUATED", "AWARDED", "CLOSED", "CANCELLED", "ARCHIVED");

        assertThat(service.enumValues("reference-mappings", "external-systems"))
            .extracting(EnumOptionResponse::code)
            .containsExactlyInAnyOrder("ONE_C", "FI", "TREASURY", "BUDGETING", "HR",
                "E_IMZO", "ETTN", "SOLIQ", "DIDOX", "FAKTURA_UZ", "KIMYOSANOAT", "CHATGPT", "CLAUDE", "GEMINI",
                "EXCHANGE", "WMS", "SCADA", "CUSTOM");
    }

    @Test
    void rfqProcedureCatalogMatchesTheMethodsRfqServiceActuallyAccepts() {
        // RFQService.normalizeTenderProcedure rejects any method where usesRfqEngine() is false.
        // Publishing anything else would offer options the server refuses.
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        List<String> expected = java.util.Arrays.stream(ProcurementMethodType.values())
            .filter(ProcurementMethodType::usesRfqEngine)
            .map(ProcurementMethodType::name)
            .toList();

        assertThat(service.enumValues("rfq", "tender-procedure-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactlyInAnyOrderElementsOf(expected);

        // Campaigns store procedure.getMethod() verbatim, so they carry the whole vocabulary.
        assertThat(service.enumValues("procurement-campaigns", "procedure-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(ProcurementMethodType.values()).map(ProcurementMethodType::name).toList()
            );
    }

    @Test
    void publishesServiceIntakeVocabularyInsteadOfLeavingItHardcodedInTheForm() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("services", "acceptance-modes")).extracting(EnumOptionResponse::code)
            .containsExactly("AMOUNT", "QUANTITY");
        assertThat(service.enumValues("services", "categories")).extracting(EnumOptionResponse::code)
            .containsExactly("REPAIR", "MAINTENANCE", "CONSULTING");
    }

    @Test
    void returnsSupplementalEnumsForCustomRuntimePages() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("purchase-orders", "receiving-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("SENT", "PARTIALLY_RECEIVED");
        assertThat(service.enumValues("non-moving-stock", "blocker-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("QUANTITY_REDUCED", "LOCATION_CHANGED", "BATCH_CHANGED", "STOCK_BLOCKED",
                "PERIOD_CLOSED", "INSUFFICIENT_STOCK", "ALREADY_EXECUTED");
        assertThat(service.enumValues("non-moving-stock", "integration-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("NEW", "PUBLISHED", "FAILED", "RETRY", "DEAD", "CANCELLED");
        assertThat(service.enumValues("non-moving-stock", "evidence-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("COMMISSION_PROTOCOL", "INVESTIGATION_REPORT", "PHOTO", "OTHER");
        assertThat(service.enumValues("non-moving-stock", "exclusion-results"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("INCLUDED");
        assertThat(service.enumValues("goods-receipt", "quality-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("PENDING_INSPECTION", "ACCEPTED", "HOLD", "REJECTED");
        assertThat(service.enumValues("reference-mappings", "ai-provider-systems"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("CHATGPT", "CLAUDE", "GEMINI");
        assertThat(service.enumValues("approval-workflows", "flow-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("ACTIVE", "INACTIVE", "DRAFT");
        assertThat(service.enumValues("notifications", "entity-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("APPROVAL_WORKFLOW", "PURCHASE_ORDER", "CONTRACT_INVOICE", "CONTRACT_PAYMENT");
    }

    @Test
    void appliesDatabaseTranslationsAndUsesBuiltInLabelsForMissingLocales() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(Set.of("FrontendPurchaseOrdersStatus"))).thenReturn(List.of(
            translation("FrontendPurchaseOrdersStatus", "DRAFT", "ru", "Черновик"),
            translation("FrontendPurchaseOrdersStatus", "DRAFT", "en", "Draft")
        ));

        EnumOptionResponse option = service.enumValues("purchase-orders", "statuses").getFirst();

        assertThat(option.code()).isEqualTo("DRAFT");
        assertThat(option.labels()).containsEntry("ru", "Черновик");
        assertThat(option.labels()).containsEntry("en", "Draft");
        assertThat(option.labels()).containsEntry("uz", "Qoralama");
        assertThat(option.active()).isTrue();
        assertThat(option.sortOrder()).isEqualTo(1);
        assertThat(option.meta()).containsEntry("color", "default");
    }

    @Test
    void returnsBuiltInTranslationsForPeriodTypeWhenDatabaseRowsAreMissing() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(Set.of("DemandPeriodType"))).thenReturn(List.of());

        List<EnumOptionResponse> options = service.enumValues("demand-plans", "period-types");

        assertThat(options).extracting(EnumOptionResponse::code)
            .containsExactly("YEAR", "QUARTER");
        assertThat(options.get(0).labels())
            .containsEntry("ru", "Год")
            .containsEntry("uz", "Yil")
            .containsEntry("en", "Year");
        assertThat(options.get(1).labels())
            .containsEntry("ru", "Четверть")
            .containsEntry("uz", "Chorak")
            .containsEntry("en", "Quarter");
    }

    @Test
    void usesLifecycleSortOrderAndStatusColors() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(Set.of("StockMovementStatus"))).thenReturn(List.of());

        List<EnumOptionResponse> options = service.enumValues("stock-movements", "statuses");

        assertThat(options).extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "PENDING", "APPROVED", "RETURNED", "POSTED", "CANCELLED");
        assertThat(options.get(2).sortOrder()).isEqualTo(3);
        assertThat(options.get(2).meta()).containsEntry("color", "blue");
        assertThat(options.get(4).meta()).containsEntry("color", "green");
        assertThat(options.get(5).meta()).containsEntry("color", "red");
    }

    @Test
    void combinedApiEnumsExposesEveryModuleWithRegisteredAliases() {
        // Regression test: catalogModuleKeys() previously only exposed a hand-maintained subset of
        // module keys through GET /api/enums (the only endpoint the frontend actually calls), even
        // though dozens of module keys had working domainEnumAliases()/supplementalModuleAliases()
        // entries reachable only via the direct per-module endpoints. Those modules' catalog data
        // was silently unreachable from the frontend. Every module key exercised below must appear
        // as a top-level key in the combined /api/enums response.
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        Map<String, Map<String, List<EnumOptionResponse>>> catalog = service.allEnums();

        assertThat(catalog.keySet()).contains(
            "rfq", "goods-receipt", "material-master-requests", "material-quality-issues",
            "material-duplicate-candidates", "material-attribute-templates", "equipment", "employees",
            "quotations", "non-moving-stock-cases", "valuation-documents", "chart-of-accounts",
            "consignment-transactions", "contract-addendums", "delivery-schedules", "demand-forecasts",
            "demand-plan-items", "integration-systems", "inventory-accounting-reconciliations",
            "inventory-fx-differences", "inventory-optimization", "inventory-period-closes",
            "landed-cost-allocation-lines", "landed-cost-allocations", "landed-cost-components",
            "landed-logistics-events", "production-evidence", "segregation-of-duty-rules",
            "segregation-of-duty-violations", "supplier-accreditation-decisions",
            "supplier-accreditation-documents", "supplier-accreditations", "supplier-blacklist",
            "supplier-kyc-checks", "supplier-licenses", "supplier-portal-submissions",
            "supplier-related-parties", "tender-bid-opening-lines", "tender-bid-opening-protocols",
            "tender-conflict-checks", "tender-evaluation-protocols",
            "issue-requests", "demand-planning-cycles", "consolidated-demands", "import-deliveries",
            "import-freight-bookings", "import-insurance-policies", "customs-declarations",
            "work-completion-acts", "commissions", "ai-analysis", "ai-providers", "data-migration",
            "feedback", "mxik-codes", "departments", "management-opportunities", "management-overview",
            "analytics-center"
        );
        assertThat(catalog.get("approval-workflows").get("module-keys"))
            .extracting(EnumOptionResponse::code)
            .contains("PROCUREMENT_METHOD_DECISION", "ASSET_ACCEPTANCE", "ASSET_HANDOVER");
        assertThat(catalog.get("approval-workflows").get("module-keys").stream()
            .filter(option -> "ASSET_ACCEPTANCE".equals(option.code()))
            .findFirst()
            .orElseThrow()
            .labels())
            .containsEntry("ru", "Приемка основного средства")
            .containsEntry("uz", "Asosiy vosita qabuli")
            .containsEntry("en", "Asset acceptance");
        assertThat(catalog.get("approval-workflows").get("module-keys").stream()
            .filter(option -> "ASSET_HANDOVER".equals(option.code()))
            .findFirst()
            .orElseThrow()
            .labels())
            .containsEntry("ru", "Передача основного средства")
            .containsEntry("uz", "Asosiy vosita topshirish")
            .containsEntry("en", "Asset handover");
        assertThat(catalog.get("approval-workflows").get("module-keys").stream()
            .filter(option -> "PROCUREMENT_METHOD_DECISION".equals(option.code()))
            .findFirst()
            .orElseThrow()
            .labels())
            .containsEntry("en", "Procurement method decision");
        assertThat(catalog.get("rfq").get("statuses")).extracting(EnumOptionResponse::code)
            .contains("DRAFT", "OPEN", "AWARDED", "CLOSED");
    }

    @Test
    void materialReturnAndStockBatchStatusesUseDistinctEnums() {
        // stock.return-statuses is the document-level material-return workflow (MaterialReturnStatus);
        // stock.batch-availability-statuses is the resulting stock-batch flag (ReturnStockStatus).
        // These were previously aliased to the same (wrong) enum under "return-statuses".
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("stock", "return-statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "SUBMITTED", "INSPECTION", "ACCEPTED", "PARTIALLY_ACCEPTED",
                "REJECTED", "POSTED", "CANCELLED");
        assertThat(service.enumValues("stock", "batch-availability-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("AVAILABLE", "BLOCKED", "QUALITY_INSPECTION");
    }

    @Test
    void newlyRegisteredSupplementalModulesResolve() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("commissions", "member-roles")).extracting(EnumOptionResponse::code)
            .containsExactly("CHAIRMAN", "MEMBER", "COUNTER", "CHECKER", "ACCOUNTANT", "STOREKEEPER",
                "SECRETARY", "AUDITOR");
        assertThat(service.enumValues("commissions", "types")).extracting(EnumOptionResponse::code)
            .contains("INVENTORY", "TENDER", "NON_MOVING_STOCK");
        assertThat(service.enumValues("quotations", "source")).extracting(EnumOptionResponse::code)
            .contains("SUPPLIER_PORTAL", "EMAIL", "PAPER", "OTHER");
        assertThat(service.enumValues("rfq", "delivery-statuses")).extracting(EnumOptionResponse::code)
            .containsExactly("PENDING", "SENT", "DELIVERED", "FAILED");
        assertThat(service.enumValues("services", "inspection-results")).extracting(EnumOptionResponse::code)
            .containsExactly("PASSED", "PASSED_WITH_REMARKS", "FAILED");
        assertThat(service.enumValues("issue-requests", "statuses")).extracting(EnumOptionResponse::code)
            .contains("DRAFT", "RESERVED", "ISSUED", "CLOSED");
    }

    @Test
    void rfqPublicationIssueCodesAreExposedWithLabels() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        List<EnumOptionResponse> options = service.enumValues("rfq", "publication-issue-codes");

        assertThat(options).extracting(EnumOptionResponse::code)
            .contains("RFQ_DUE_DATE_EXPIRED", "RFQ_SUPPLIERS_REQUIRED", "RFQ_PACKAGE_RENDER_FAILED");
        assertThat(options.stream()
            .filter(option -> "RFQ_DUE_DATE_EXPIRED".equals(option.code()))
            .findFirst()
            .orElseThrow()
            .labels())
            .containsEntry("en", "Response due date has expired")
            .containsEntry("ru", "Срок ответа по RFQ истёк")
            .containsEntry("uz", "RFQ javob muddati tugagan");
    }

    @Test
    void combinedApiEnumsCoversEverySectionTheFrontendRequests() {
        // Contract with the frontend enum de-hardcoding effort: every (moduleKey, enumType) pair the
        // frontend passes to enumOptionsWithFallback/enumValuesWithFallback must be present in the
        // combined GET /api/enums response with a non-empty option list — the frontend no longer
        // ships hardcoded fallback arrays, so a missing section here means an empty dropdown.
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        Map<String, Map<String, List<EnumOptionResponse>>> catalog = service.allEnums();

        Map<String, List<String>> requiredSections = Map.ofEntries(
            Map.entry("global", List.of("request-types", "period-close-modes", "revaluation-posting-modes",
                "issue-request-cost-object-types", "management-opportunity-types",
                "management-opportunity-statuses", "management-opportunity-data-completenesses",
                "management-opportunity-confidences")),
            Map.entry("approval-workflows", List.of("module-keys", "flow-statuses", "task-statuses")),
            Map.entry("goods-receipt", List.of("quality-statuses")),
            Map.entry("inventory", List.of("statuses", "rounding-modes")),
            Map.entry("materials", List.of("valuation-methods", "return-conditions")),
            Map.entry("material-master-requests", List.of("statuses")),
            Map.entry("material-duplicate-candidates", List.of("statuses")),
            Map.entry("material-quality-issues", List.of("statuses")),
            Map.entry("non-moving-stock", List.of("case-statuses", "execution-statuses", "blocker-types",
                "integration-statuses", "exclusion-results", "evidence-types")),
            Map.entry("non-moving-stock-cases", List.of("decision-types")),
            Map.entry("procurement-command", List.of("stages", "blocker-severities")),
            Map.entry("procurement-method-decisions", List.of("statuses", "methods")),
            Map.entry("purchase-orders", List.of("receiving-statuses")),
            Map.entry("reference-mappings", List.of("external-systems", "accounting-areas", "ai-provider-systems")),
            Map.entry("reservations", List.of("types", "priorities")),
            Map.entry("rfq", List.of("statuses", "selection-rules", "tender-procedure-types",
                "delivery-statuses", "publication-issue-codes")),
            Map.entry("services", List.of("categories", "acceptance-modes")),
            Map.entry("stock", List.of("return-statuses", "movement-statuses")),
            Map.entry("supplier-accreditation-documents", List.of("document-types")),
            Map.entry("supply-control", List.of("queue-keys", "risk-keys", "lenses")),
            Map.entry("users", List.of("roles"))
        );

        requiredSections.forEach((moduleKey, enumTypes) -> {
            assertThat(catalog).as("module %s", moduleKey).containsKey(moduleKey);
            for (String enumType : enumTypes) {
                assertThat(catalog.get(moduleKey))
                    .as("section %s.%s", moduleKey, enumType)
                    .containsKey(enumType);
                assertThat(catalog.get(moduleKey).get(enumType))
                    .as("options of %s.%s", moduleKey, enumType)
                    .isNotEmpty();
            }
        });
    }

    @Test
    void exposesPurchaseRequestWorkspaceEnums() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("purchase-requests", "workspace-views"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("ALL", "MY", "DRAFT", "ON_APPROVAL", "IN_SOURCING", "IN_PROCUREMENT", "FULFILLED");
        assertThat(service.enumValues("purchase-requests", "process-stages"))
            .extracting(EnumOptionResponse::code)
            .contains("DEMAND_PLAN", "PURCHASE_REQUEST", "FULFILLED");
        assertThat(service.enumValues("purchase-requests", "workspace-action-keys"))
            .extracting(EnumOptionResponse::code)
            .contains("EDIT", "SUBMIT", "OPEN_DEMAND_PLAN")
            .doesNotContain("OPEN_PROCUREMENT_COMMAND");
        assertThat(service.enumValues("purchase-requests", "coverage-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("NOT_STARTED", "ORDERED", "PARTIAL", "SUPPLIED");
    }

    @Test
    void exposesPurchaseOrderWorkspaceEnums() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("purchase-orders", "workspace-views"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("ALL", "MY", "DRAFT", "ON_APPROVAL", "READY_TO_SEND", "IN_EXECUTION", "FULFILLED");
        assertThat(service.enumValues("purchase-orders", "process-stages"))
            .extracting(EnumOptionResponse::code)
            .contains("DEMAND_PLAN", "PURCHASE_ORDER", "INVOICING", "PAYMENT", "FULFILLED");
        assertThat(service.enumValues("purchase-orders", "workspace-action-keys"))
            .extracting(EnumOptionResponse::code)
            .contains("EDIT", "SUBMIT", "SEND", "OPEN_GOODS_RECEIPT", "OPEN_PURCHASE_REQUEST", "CREATE_INVOICE", "OPEN_INVOICE", "SUBMIT_PAYMENT");
        assertThat(service.enumValues("purchase-orders", "coverage-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("NOT_STARTED", "ORDERED", "PARTIAL", "SUPPLIED", "UNAVAILABLE");
    }

    @Test
    void exposesDemandPlanWorkspaceEnums() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("demand-plans", "workspace-views"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("ALL", "MY", "DRAFT", "ON_APPROVAL", "IN_PROCUREMENT", "PARTIALLY_SUPPLIED", "SUPPLIED");
        assertThat(service.enumValues("demand-plans", "process-stages"))
            .extracting(EnumOptionResponse::code)
            .contains("DEMAND_PLAN", "PURCHASE_REQUEST", "DEPARTMENT_SUPPLY");
        assertThat(service.enumValues("demand-plans", "workspace-action-keys"))
            .extracting(EnumOptionResponse::code)
            .contains("EDIT", "CREATE_PURCHASE_REQUEST", "OPEN_PURCHASE_REQUEST");
        assertThat(service.enumValues("demand-plans", "coverage-statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("NOT_STARTED", "ORDERED", "PARTIAL", "SUPPLIED", "UNAVAILABLE");
    }

    @Test
    void exposesContractPayablesEnums() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);
        when(translationRepository.findByEnumNameIn(anyCollection())).thenReturn(List.of());

        assertThat(service.enumValues("contract-invoices", "statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "ISSUED", "PARTIALLY_PAID", "PAID", "CANCELLED");
        assertThat(service.enumValues("contract-payments", "statuses"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("DRAFT", "SUBMITTED", "SENT_TO_TREASURY", "PAID", "CANCELLED");
        assertThat(service.enumValues("contract-payments", "source-document-types"))
            .extracting(EnumOptionResponse::code)
            .containsExactly("GOODS_RECEIPT", "ASSET_ACCEPTANCE", "SERVICE_ACCEPTANCE_ACT", "WORK_COMPLETION_ACT");
    }

    @Test
    void rejectsUnknownModuleOrEnumType() {
        EnumCatalogService service = new EnumCatalogService(translationRepository);

        assertThatThrownBy(() -> service.enumValues("unknown", "statuses"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown enum source");
        assertThatThrownBy(() -> service.enumValues("materials", "unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown enum source");
    }

    private EnumTranslation translation(String enumName, String code, String locale, String label) {
        EnumTranslation translation = new EnumTranslation();
        translation.setEnumName(enumName);
        translation.setCode(code);
        translation.setLocale(Locale.forLanguageTag(locale));
        translation.setLabel(label);
        return translation;
    }
}
