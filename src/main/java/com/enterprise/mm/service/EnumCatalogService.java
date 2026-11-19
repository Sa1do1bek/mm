package com.enterprise.mm.service;

import com.enterprise.mm.entity.EnumTranslation;
import com.enterprise.mm.payload.EnumOptionResponse;
import com.enterprise.mm.repository.EnumTranslationRepository;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnumCatalogService {
    private static final List<String> ENUM_PACKAGES = List.of(
        "com.enterprise.mm.enums",
        "com.enterprise.mm.procurement.command.enums",
        "com.enterprise.mm.supply.workspace.enums",
        "com.enterprise.mm.inventory.availability.enums",
        "com.enterprise.mm.supplier.governance.enums"
    );
    private static final List<String> LOCALES = List.of("ru", "uz", "en");
    private static final Map<String, Map<String, String>> BUILT_IN_LABELS = builtInLabels();

    private final EnumTranslationRepository translationRepository;
    private final Map<String, EnumDefinition> globalDefinitions;
    private final Map<String, Map<String, EnumDefinition>> definitions;

    public EnumCatalogService(EnumTranslationRepository translationRepository) {
        this.translationRepository = translationRepository;
        this.globalDefinitions = discoverGlobalDefinitions();
        this.definitions = buildDefinitions(globalDefinitions);
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, List<EnumOptionResponse>>> allEnums() {
        Map<String, Map<String, Map<String, String>>> translations =
            translationsByEnumName(registeredEnumNames());
        Map<String, Map<String, List<EnumOptionResponse>>> result = new LinkedHashMap<>();
        result.put("global", optionsByType(globalDefinitions, translations));
        for (String moduleKey : catalogModuleKeys()) {
            Map<String, EnumDefinition> moduleDefinitions = moduleDefinitions(moduleKey);
            if (!moduleDefinitions.isEmpty()) {
                result.put(moduleKey, optionsByType(moduleDefinitions, translations));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, List<EnumOptionResponse>> moduleEnums(String moduleKey) {
        Map<String, EnumDefinition> moduleDefinitions = moduleDefinitions(moduleKey);
        if (moduleDefinitions.isEmpty()) {
            throw new IllegalArgumentException("Unknown enum module: " + moduleKey);
        }
        return optionsByType(moduleDefinitions, translationsByEnumName(enumNames(moduleDefinitions.values())));
    }

    @Transactional(readOnly = true)
    public List<EnumOptionResponse> enumValues(String moduleKey, String enumType) {
        EnumDefinition definition = definition(moduleKey, enumType);
        return options(definition, translationsByEnumName(Set.of(definition.enumName())));
    }

    public Set<String> registeredEnumNames() {
        Set<String> names = enumNames(globalDefinitions.values());
        definitions.values().stream()
            .map(Map::values)
            .flatMap(Collection::stream)
            .map(EnumDefinition::enumName)
            .forEach(names::add);
        return names;
    }

    private Set<String> enumNames(Collection<EnumDefinition> definitions) {
        return definitions.stream()
            .map(EnumDefinition::enumName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, List<EnumOptionResponse>> optionsByType(
        Map<String, EnumDefinition> definitions,
        Map<String, Map<String, Map<String, String>>> translations
    ) {
        Map<String, List<EnumOptionResponse>> result = new LinkedHashMap<>();
        for (Map.Entry<String, EnumDefinition> entry : definitions.entrySet()) {
            result.put(entry.getKey(), options(entry.getValue(), translations));
        }
        return result;
    }

    private Map<String, EnumDefinition> moduleDefinitions(String moduleKey) {
        Map<String, EnumDefinition> moduleDefinitions = new LinkedHashMap<>(
            definitions.getOrDefault(moduleKey, Map.of())
        );
        inferredStatusDefinition(moduleKey).ifPresent(definition ->
            moduleDefinitions.putIfAbsent("statuses", definition)
        );
        addSupplementalModuleAliases(moduleDefinitions, moduleKey);
        addPrefixAliases(moduleDefinitions, moduleKey);
        addDomainAliases(moduleDefinitions, moduleKey);
        return moduleDefinitions;
    }

    private EnumDefinition definition(String moduleKey, String enumType) {
        EnumDefinition moduleDefinition = moduleDefinitions(moduleKey).get(normalizeEnumType(enumType));
        if (moduleDefinition != null) {
            return moduleDefinition;
        }
        EnumDefinition global = globalDefinitions.get(normalizeEnumType(enumType));
        if (global != null) {
            return global;
        }
        throw new IllegalArgumentException("Unknown enum source: " + moduleKey + "/" + enumType);
    }

    private Set<String> catalogModuleKeys() {
        Set<String> keys = new LinkedHashSet<>(definitions.keySet());
        keys.addAll(domainModuleKeys());
        for (EnumDefinition definition : uniqueGlobalDefinitions()) {
            if (definition.enumName().endsWith("Status")) {
                String baseName = definition.enumName().substring(0, definition.enumName().length() - "Status".length());
                keys.add(pluralizeLastSegment(camelToKebab(baseName)));
            }
        }
        return keys;
    }

    private void addPrefixAliases(Map<String, EnumDefinition> moduleDefinitions, String moduleKey) {
        String prefix = singularizeLastSegment(normalizeEnumType(moduleKey));
        if (prefix.isBlank()) {
            return;
        }
        for (EnumDefinition definition : uniqueGlobalDefinitions()) {
            for (String alias : aliases(definition.enumName())) {
                if (alias.startsWith(prefix + "-")) {
                    moduleDefinitions.putIfAbsent(alias.substring(prefix.length() + 1), definition);
                }
            }
        }
    }

    private List<EnumDefinition> uniqueGlobalDefinitions() {
        Map<String, EnumDefinition> unique = new LinkedHashMap<>();
        for (EnumDefinition definition : globalDefinitions.values()) {
            unique.putIfAbsent(definition.enumName(), definition);
        }
        return List.copyOf(unique.values());
    }

    private void addDomainAliases(Map<String, EnumDefinition> moduleDefinitions, String moduleKey) {
        for (Map.Entry<String, String> entry : domainEnumAliases(moduleKey).entrySet()) {
            globalDefinitionByEnumName(entry.getValue()).ifPresent(definition ->
                moduleDefinitions.putIfAbsent(entry.getKey(), definition)
            );
        }
    }

    private Optional<EnumDefinition> globalDefinitionByEnumName(String enumName) {
        return uniqueGlobalDefinitions().stream()
            .filter(definition -> definition.enumName().equals(enumName))
            .findFirst();
    }

    private Set<String> domainModuleKeys() {
        return Set.of(
            "ai-analysis",
            "ai-providers",
            "analytics-center",
            "approval-workflows",
            "asn",
            "chart-of-accounts",
            "commissions",
            "consignment",
            "consignment-transactions",
            "consolidated-demands",
            "contract-addendums",
            "contract-invoices",
            "contract-payments",
            "contracts",
            "cross-docking",
            "customs-declarations",
            "data-migration",
            "delivery-schedules",
            "demand-forecasts",
            "demand-plan-items",
            "demand-planning-cycles",
            "demand-plans",
            "departments",
            "employees",
            "equipment",
            "feedback",
            "forecasts",
            "goods-receipt",
            "import-deliveries",
            "import-freight-bookings",
            "import-insurance-policies",
            "integration-systems",
            "inventory",
            "inventory-accounting-reconciliations",
            "inventory-availability",
            "inventory-fx-differences",
            "inventory-optimization",
            "inventory-period-closes",
            "issue-requests",
            "landed-cost-allocation-lines",
            "landed-cost-allocations",
            "landed-cost-components",
            "landed-logistics-events",
            "logistics",
            "management-opportunities",
            "management-overview",
            "market-prices",
            "material-attribute-templates",
            "material-duplicate-candidates",
            "material-master-requests",
            "material-quality-issues",
            "materials",
            "mobile",
            "mxik-codes",
            "non-moving-stock",
            "non-moving-stock-cases",
            "procurement-campaigns",
            "procurement-category-strategies",
            "procurement-command",
            "procurement-method-decisions",
            "procurement-plans",
            "procurement-procedures",
            "procurement-strategies",
            "production-evidence",
            "quality",
            "quotations",
            "reference-data",
            "reference-mappings",
            "reservations",
            "rfq",
            "segregation-of-duty-rules",
            "segregation-of-duty-violations",
            "services",
            "stock",
            "supplier-accreditation-decisions",
            "supplier-accreditation-documents",
            "supplier-accreditations",
            "supplier-blacklist",
            "supplier-governance",
            "supplier-kyc-checks",
            "supplier-licenses",
            "supplier-portal-submissions",
            "supplier-related-parties",
            "suppliers",
            "supply-control",
            "tender-bid-opening-lines",
            "tender-bid-opening-protocols",
            "tender-conflict-checks",
            "tender-evaluation-protocols",
            "users",
            "valuation-documents",
            "vmi",
            "warehouses",
            "notifications",
            "wms-cross-docking",
            "work-completion-acts"
        );
    }

    private Map<String, String> domainEnumAliases(String moduleKey) {
        return switch (normalizeEnumType(moduleKey)) {
            case "ai-analysis" -> Map.ofEntries(
                Map.entry("severities", "FrontendAiAnalysisSeverity"),
                Map.entry("priorities", "FrontendAiAnalysisPriority")
            );
            case "ai-providers" -> Map.of("auth-types", "FrontendAiProviderAuthType");
            case "analytics-center" -> Map.of("health-statuses", "FrontendAnalyticsCenterHealthStatus");
            case "approval-workflows" -> Map.ofEntries(
                Map.entry("statuses", "ApprovalInstanceStatus"),
                Map.entry("request-types", "ApprovalRequestType"),
                Map.entry("assignee-types", "ApprovalAssigneeType"),
                Map.entry("history-actions", "ApprovalHistoryAction"),
                Map.entry("step-types", "ApprovalStepType"),
                Map.entry("task-statuses", "ApprovalTaskStatus"),
                Map.entry("severities", "FrontendApprovalWorkflowsSeverity")
            );
            case "notifications" -> Map.ofEntries(
                Map.entry("entity-types", "NotificationEntityType")
            );
            case "asn" -> Map.ofEntries(
                Map.entry("statuses", "AdvanceShippingNoticeStatus"),
                Map.entry("transport-modes", "TransportMode")
            );
            case "commissions" -> Map.ofEntries(
                Map.entry("member-roles", "CommissionMemberRole"),
                Map.entry("types", "CommissionType")
            );
            case "consignment" -> Map.of("transaction-types", "ConsignmentTransactionType");
            case "consolidated-demands" -> Map.of("statuses", "ConsolidatedDemandStatus");
            case "contracts" -> Map.of("statuses", "ContractStatus");
            case "contract-invoices" -> Map.of("statuses", "ContractInvoiceStatus");
            case "contract-payments" -> Map.ofEntries(
                Map.entry("statuses", "ContractPaymentStatus"),
                Map.entry("source-document-types", "ProcurementSourceDocumentType")
            );
            case "cross-docking", "wms-cross-docking" -> Map.ofEntries(
                Map.entry("statuses", "CrossDockingTaskStatus"),
                Map.entry("outbound-reference-types", "CrossDockingOutboundReferenceType")
            );
            case "customs-declarations" -> Map.of("statuses", "CustomsDeclarationStatus");
            case "data-migration" -> Map.of("row-statuses", "FrontendDataMigrationRowStatus");
            case "demand-planning-cycles" -> Map.of("statuses", "DemandPlanningCycleStatus");
            case "departments" -> Map.of("node-statuses", "FrontendOrgStructureNodeStatus");
            case "feedback" -> Map.of("statuses", "FrontendUserFeedbackStatus");
            case "import-deliveries" -> Map.of("statuses", "ImportDeliveryStatus");
            case "import-freight-bookings" -> Map.of("statuses", "ImportFreightStatus");
            case "import-insurance-policies" -> Map.of("statuses", "ImportInsuranceStatus");
            case "issue-requests" -> Map.of("statuses", "IssueRequestStatus");
            case "management-opportunities" -> Map.ofEntries(
                Map.entry("types", "ManagementOpportunityType"),
                Map.entry("statuses", "ManagementOpportunityStatus"),
                Map.entry("data-completenesses", "ManagementOpportunityDataCompleteness"),
                Map.entry("confidences", "ManagementOpportunityConfidence"),
                Map.entry("aging-buckets", "FrontendManagementOpportunityAgingBucket")
            );
            case "management-overview" -> Map.of("decision-severities", "FrontendManagementOverviewDecisionSeverity");
            case "market-prices" -> Map.of("statuses", "FrontendMarketPricesStatus");
            case "procurement-category-strategies" -> Map.of("sourcing-approaches", "FrontendProcurementCategoryStrategiesSourcingApproach");
            case "mxik-codes" -> Map.of("types", "FrontendMxikType");
            // Shared Active/Inactive status for plain reference/master-data catalogs (currencies,
            // classifiers, departments, positions, storage locations, etc.) that have no richer
            // workflow of their own — avoids inventing a near-duplicate enum per reference table.
            case "reference-data" -> Map.of("statuses", "FrontendReferenceDataStatus");
            case "work-completion-acts" -> Map.of("statuses", "WorkCompletionActStatus");
            case "demand-plans" -> Map.ofEntries(
                Map.entry("statuses", "DemandPlanStatus"),
                Map.entry("periods", "DemandPeriodType"),
                Map.entry("period-types", "DemandPeriodType"),
                Map.entry("priorities", "DemandPriority"),
                Map.entry("demand-types", "DemandType"),
                Map.entry("request-types", "RequestType"),
                Map.entry("workspace-views", "DemandPlanWorkspaceView"),
                Map.entry("process-stages", "DemandPlanProcessStage"),
                Map.entry("workspace-action-keys", "DemandPlanWorkspaceActionKey"),
                Map.entry("coverage-statuses", "DemandPlanCoverageStatus")
            );
            case "forecasts" -> Map.ofEntries(
                Map.entry("methods", "DemandForecastMethod"),
                Map.entry("model-qualities", "ManagementOpportunityConfidence")
            );
            case "inventory" -> Map.ofEntries(
                Map.entry("statuses", "InventoryStatus"),
                Map.entry("types", "InventoryType"),
                Map.entry("cost-layer-types", "InventoryCostLayerType"),
                Map.entry("discrepancy-types", "InventoryDiscrepancyType"),
                Map.entry("difference-statuses", "InventoryExplanationStatus"),
                Map.entry("explanation-statuses", "InventoryExplanationStatus"),
                Map.entry("line-statuses", "InventoryLineStatus"),
                Map.entry("rounding-modes", "InventoryRoundingMode"),
                Map.entry("valuation-job-statuses", "StockValuationCostJobStatus"),
                Map.entry("valuation-run-statuses", "StockValuationRunStatus"),
                Map.entry("valuation-run-item-statuses", "StockValuationRunItemStatus"),
                Map.entry("valuation-job-phases", "StockValuationCostJobPhase"),
                Map.entry("valuation-run-scopes", "StockValuationRunScope"),
                Map.entry("period-close-modes", "PeriodCloseMode"),
                Map.entry("revaluation-posting-modes", "RevaluationPostingMode"),
                Map.entry("abc-classes", "FrontendInventoryAbcClass"),
                Map.entry("xyz-classes", "FrontendInventoryXyzClass"),
                Map.entry("period-close-readiness-codes", "FrontendInventoryPeriodCloseReadinessCode")
            );
            case "inventory-availability" -> Map.of("queue-keys", "InventoryAvailabilityQueueKey");
            case "logistics" -> Map.ofEntries(
                Map.entry("transport-modes", "TransportMode"),
                Map.entry("landed-cost-component-types", "LandedCostComponentType"),
                Map.entry("allocation-methods", "LandedCostAllocationMethod"),
                Map.entry("event-types", "LandedLogisticsEventType")
            );
            case "materials" -> Map.ofEntries(
                Map.entry("statuses", "MaterialStatus"),
                Map.entry("material-types", "MaterialType"),
                Map.entry("material-subtypes", "MaterialSubtype"),
                Map.entry("tracking-modes", "TrackingMode"),
                Map.entry("batch-tracking-policies", "BatchTrackingPolicy"),
                Map.entry("serial-tracking-policies", "SerialTrackingPolicy"),
                Map.entry("valuation-methods", "ValuationMethod"),
                Map.entry("hazard-classes", "HazardClass"),
                Map.entry("analog-types", "MaterialAnalogType"),
                Map.entry("compatibility-types", "MaterialCompatibilityType"),
                Map.entry("advice-statuses", "MaterialAdviceStatus"),
                Map.entry("consumption-norm-types", "ConsumptionNormType"),
                Map.entry("group-statuses", "MaterialGroupStatus")
            );
            case "mobile" -> Map.ofEntries(
                Map.entry("warehouse-operations", "MobileWarehouseOperationType"),
                Map.entry("tsd-offline-statuses", "TsdOfflineOperationStatus")
            );
            case "non-moving-stock" -> Map.ofEntries(
                Map.entry("statuses", "NonMovingStockStatus"),
                Map.entry("case-statuses", "NonMovingStockCaseStatus"),
                Map.entry("decision-statuses", "NonMovingStockDecisionStatus"),
                Map.entry("decision-types", "NonMovingStockDecisionType"),
                Map.entry("execution-statuses", "NonMovingStockExecutionStatus"),
                Map.entry("execution-output-statuses", "NonMovingStockExecutionOutputStatus"),
                Map.entry("execution-output-types", "NonMovingStockExecutionOutputType"),
                Map.entry("reconciliation-statuses", "NonMovingStockReconciliationStatus"),
                Map.entry("integration-statuses", "IntegrationEventStatus")
            );
            case "procurement-campaigns" -> Map.ofEntries(
                Map.entry("statuses", "ProcurementCampaignStatus"),
                Map.entry("types", "ProcurementCampaignType"),
                Map.entry("methods", "ProcurementMethodType"),
                Map.entry("line-types", "ProcurementLineType"),
                Map.entry("risk-levels", "ProcurementRiskLevel")
            );
            case "procurement-command" -> Map.ofEntries(
                Map.entry("queue-keys", "ProcurementQueueKey"),
                Map.entry("stages", "ProcurementCommandStage"),
                Map.entry("blocker-severities", "ProcurementBlockerSeverity"),
                Map.entry("allocation-modes", "ProcurementAllocationMode")
            );
            case "procurement-method-decisions" -> Map.ofEntries(
                Map.entry("statuses", "ProcurementMethodDecisionStatus"),
                Map.entry("methods", "ProcurementMethodType")
            );
            case "procurement-plans" -> Map.ofEntries(
                Map.entry("statuses", "ProcurementPlanStatus"),
                Map.entry("periods", "DemandPeriodType"),
                Map.entry("period-types", "DemandPeriodType")
            );
            case "procurement-procedures" -> Map.ofEntries(
                Map.entry("statuses", "ProcurementProcedureStatus"),
                Map.entry("methods", "ProcurementMethodType")
            );
            case "procurement-strategies" -> Map.ofEntries(
                Map.entry("statuses", "ProcurementStrategyStatus"),
                Map.entry("types", "ProcurementStrategyType"),
                Map.entry("sourcing-approaches", "ProcurementMethodType"),
                Map.entry("risk-levels", "ProcurementRiskLevel")
            );
            case "quality" -> Map.ofEntries(
                Map.entry("inspection-types", "QualityInspectionType"),
                Map.entry("inspection-statuses", "QualityInspectionStatus"),
                Map.entry("severities", "QualitySeverity"),
                Map.entry("dispositions", "QualityDisposition"),
                Map.entry("quarantine-statuses", "QualityQuarantineStatus"),
                Map.entry("nonconformance-statuses", "QualityNonconformanceStatus"),
                Map.entry("claim-statuses", "QualityClaimStatus"),
                Map.entry("laboratory-test-statuses", "LaboratoryTestStatus"),
                Map.entry("assessment-statuses", "QmsAssessmentStatus"),
                Map.entry("queue-keys", "FrontendQualityQueueKey")
            );
            case "reference-mappings" -> Map.ofEntries(
                Map.entry("external-systems", "ExternalIntegrationSystem"),
                Map.entry("systems", "ExternalIntegrationSystem")
            );
            case "reservations" -> Map.ofEntries(
                Map.entry("statuses", "MaterialReservationStatus"),
                Map.entry("types", "MaterialReservationType"),
                Map.entry("priorities", "DemandPriority")
            );
            case "services" -> Map.ofEntries(
                Map.entry("request-statuses", "ServiceRequestStatus"),
                Map.entry("acceptance-act-statuses", "ServiceAcceptanceActStatus"),
                Map.entry("claim-statuses", "ServiceClaimStatus"),
                Map.entry("claim-types", "ServiceClaimType"),
                Map.entry("claim-origin-types", "ServiceClaimOriginType"),
                Map.entry("execution-statuses", "ServiceExecutionStatus"),
                Map.entry("review-types", "ServiceReviewType"),
                Map.entry("review-decisions", "ServiceReviewDecision"),
                Map.entry("severities", "ServiceSeverity"),
                Map.entry("acceptance-stages", "FrontendServicesAcceptanceStage"),
                Map.entry("acceptance-modes", "FrontendServicesAcceptanceMode"),
                Map.entry("categories", "FrontendServicesCategory"),
                Map.entry("inspection-results", "WorkInspectionResult")
            );
            case "stock" -> Map.ofEntries(
                Map.entry("movement-statuses", "StockMovementStatus"),
                Map.entry("movement-types", "MovementType"),
                Map.entry("cost-movement-types", "StockCostMovementType"),
                Map.entry("risk-severities", "StockRiskSeverity"),
                Map.entry("transfer-statuses", "TransferStatus"),
                // NOTE: the document-level material-return workflow status is MaterialReturnStatus
                // (DRAFT/SUBMITTED/INSPECTION/ACCEPTED/.../POSTED) — ReturnStockStatus below is the
                // batch-level stock availability flag (AVAILABLE/BLOCKED/QUALITY_INSPECTION), a
                // different concept that was previously mis-aliased under this same key.
                Map.entry("return-statuses", "MaterialReturnStatus"),
                Map.entry("return-conditions", "MaterialReturnCondition"),
                Map.entry("batch-availability-statuses", "ReturnStockStatus")
            );
            case "supplier-governance" -> Map.of("queue-keys", "SupplierGovernanceQueueKey");
            case "supply-control" -> Map.ofEntries(
                Map.entry("lenses", "SupplyLens"),
                Map.entry("queue-keys", "SupplyQueueKey"),
                Map.entry("risk-keys", "SupplyRiskKey"),
                Map.entry("severities", "SupplySeverity"),
                Map.entry("availabilities", "SupplyAvailability"),
                Map.entry("data-qualities", "SupplyDataQuality"),
                Map.entry("source-states", "SupplySourceState"),
                Map.entry("escalation-states", "SupplyEscalationState"),
                Map.entry("assignment-roles", "SupplyAssignmentRole"),
                Map.entry("commitment-kinds", "SupplyCommitmentKind"),
                Map.entry("commitment-sorts", "SupplyCommitmentSort"),
                Map.entry("calendar-lanes", "SupplyCalendarLane"),
                Map.entry("date-bases", "SupplyDateBasis"),
                Map.entry("due-date-sources", "SupplyDueDateSource"),
                Map.entry("performance-metric-keys", "SupplyPerformanceMetricKey")
            );
            case "suppliers" -> Map.ofEntries(
                Map.entry("statuses", "SupplierStatus"),
                Map.entry("portal-statuses", "SupplierPortalStatus"),
                Map.entry("portal-submission-statuses", "SupplierPortalSubmissionStatus"),
                Map.entry("accreditation-statuses", "SupplierAccreditationStatus"),
                Map.entry("qualification-statuses", "SupplierQualificationStatus"),
                Map.entry("kyc-check-types", "SupplierKycCheckType"),
                Map.entry("kyc-statuses", "SupplierKycStatus"),
                Map.entry("kyc-risk-levels", "SupplierKycRiskLevel"),
                Map.entry("risk-levels", "SupplierKycRiskLevel"),
                Map.entry("relationship-types", "SupplierRelationshipType"),
                Map.entry("related-party-types", "SupplierRelatedPartyType"),
                Map.entry("corrective-action-statuses", "SupplierCorrectiveActionStatus"),
                Map.entry("corrective-action-severities", "SupplierCorrectiveActionSeverity")
            );
            case "users" -> Map.ofEntries(
                Map.entry("roles", "RoleName"),
                Map.entry("employee-statuses", "EmployeeStatus")
            );
            case "vmi" -> Map.ofEntries(
                Map.entry("stock-types", "VmiStockType"),
                Map.entry("agreement-statuses", "VmiAgreementStatus")
            );
            case "warehouses" -> Map.ofEntries(
                Map.entry("storage-zone-types", "StorageZoneType"),
                Map.entry("mobile-operations", "MobileWarehouseOperationType"),
                Map.entry("tsd-offline-statuses", "TsdOfflineOperationStatus")
            );
            default -> Map.of();
        };
    }

    private List<EnumDefinition> supplementalDefinitions() {
        return List.of(
            EnumDefinition.of("FrontendEquipmentStatus", "ACTIVE", "INACTIVE", "RETIRED"),
            EnumDefinition.of("FrontendMaterialMasterRequestsStatus", "DRAFT", "SUBMITTED", "IN_REVIEW", "APPROVED", "REJECTED", "APPLIED", "CANCELLED"),
            EnumDefinition.of("FrontendMaterialMasterRequestsRequestType", "CREATE", "CHANGE", "MERGE", "OBSOLETE", "REACTIVATE"),
            EnumDefinition.of("FrontendMaterialQualityIssuesStatus", "OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "IGNORED"),
            EnumDefinition.of("FrontendMaterialQualityIssuesIssueType", "MISSING_REQUIRED_FIELD", "DUPLICATE_CODE", "DUPLICATE_NAME", "INVALID_UOM", "MISSING_IKPU", "MISSING_TNVED", "MISSING_BARCODE", "INVALID_VALUATION_METHOD", "MISSING_MIN_MAX_STOCK", "OBSOLETE_WITHOUT_REPLACEMENT"),
            EnumDefinition.of("FrontendMaterialDuplicateCandidatesStatus", "OPEN", "CONFIRMED", "REJECTED", "MERGED"),
            EnumDefinition.of("FrontendMaterialAttributeTemplatesDataType", "STRING", "NUMBER", "DATE", "BOOLEAN", "ENUM", "TEXT"),
            EnumDefinition.of("FrontendSuppliersCategory", "A", "B", "C", "D"),
            EnumDefinition.of("SupplierRelationshipType", "INTERNAL", "EXTERNAL"),
            EnumDefinition.of("FrontendSupplierBlacklistStatus", "BLOCKED"),
            EnumDefinition.of("FrontendSupplierBlacklistCategory", "A", "B", "C", "D"),
            EnumDefinition.of("FrontendSupplierAccreditationsStatus", "DRAFT", "SUBMITTED", "UNDER_REVIEW", "NEEDS_CLARIFICATION", "COMPLIANCE_CHECK", "APPROVED", "CONDITIONALLY_APPROVED", "REJECTED", "SUSPENDED", "EXPIRED", "RENEWAL_REQUIRED"),
            EnumDefinition.of("FrontendSupplierAccreditationsAccreditationType", "GOODS", "SERVICES", "LOGISTICS", "FRAME_AGREEMENT"),
            EnumDefinition.of("FrontendServicesCategory", "REPAIR", "MAINTENANCE", "CONSULTING"),

            EnumDefinition.of("FrontendSupplierAccreditationsComplianceRecommendation", "ACTIVE", "LIMITED", "BLOCKED"),
            EnumDefinition.of("FrontendSupplierAccreditationDocumentsStatus", "PENDING_VERIFICATION", "VERIFIED", "REJECTED"),
            EnumDefinition.of("FrontendSupplierAccreditationDecisionsStatus", "RECORDED"),
            EnumDefinition.of("FrontendSupplierLicensesStatus", "ACTIVE", "EXPIRED", "SUSPENDED", "REVOKED"),
            EnumDefinition.of("FrontendSupplierLicensesRenewalRequired", "true", "false"),
            EnumDefinition.of("FrontendSupplierPortalSubmissionsSubmissionType", "REGISTRATION", "KYC_DOCUMENT", "CERTIFICATE", "LICENSE", "QUOTATION", "CONTRACT_DOCUMENT", "BANK_DETAILS", "CAPA_RESPONSE"),
            EnumDefinition.of("FrontendSupplierKycChecksRemediationRequired", "true", "false"),
            EnumDefinition.of("FrontendSupplierRelatedPartiesPepFlag", "true", "false"),
            EnumDefinition.of("FrontendSupplierRelatedPartiesSanctionsMatch", "true", "false"),
            EnumDefinition.of("FrontendDemandPlanItemsDemandType", "OPERATIONS", "PROJECT", "REPAIR", "CAPEX", "OPEX", "EMERGENCY"),
            EnumDefinition.of("FrontendProcurementCampaignsProcedureType", "OPEN_TENDER", "CLOSED_TENDER", "OPEN_COMPETITION", "CLOSED_COMPETITION", "REQUEST_FOR_QUOTATION", "DIRECT_PURCHASE", "SINGLE_SOURCE", "E_AUCTION", "FRAME_AGREEMENT"),
            EnumDefinition.of("FrontendProcurementExecutionPath", "RFQ", "TENDER_WITH_PRICE_DISCOVERY", "DIRECT_AWARD", "AUCTION", "FRAMEWORK_AGREEMENT"),
            EnumDefinition.of("FrontendPurchaseRequestsStatus", "DRAFT", "APPROVAL_PENDING", "APPROVED", "REJECTED", "RETURNED", "RFQ_CREATED", "PARTIALLY_SENT_TO_TENDER", "FULLY_SENT_TO_TENDER", "PO_CREATED", "CANCELLED", "CONVERTED_TO_PO"),
            EnumDefinition.of("FrontendApprovalWorkflowsModuleKey", "PURCHASE_REQUEST", "INVENTORY_DOCUMENT", "SUPPLIER_ACCREDITATION", "SUPPLIER_ACCREDITATION_DOCUMENT", "SUPPLIER_PORTAL_SUBMISSION", "SUPPLIER_CORRECTIVE_ACTION", "DEMAND_PLANNING_CYCLE", "DEMAND_PLAN", "DEMAND_PLAN_ITEM", "PROCUREMENT_PURCHASE_ORDER", "PROCUREMENT_CATEGORY_STRATEGY", "PROCUREMENT_PLAN", "PROCUREMENT_METHOD_DECISION", "PROCUREMENT_DIRECT_AWARD", "PROCUREMENT_SINGLE_SOURCE_AWARD", "ASN", "CUSTOMS_DECLARATION", "QUALITY_INSPECTION", "QUALITY_QUARANTINE_LOT", "QUALITY_NONCONFORMANCE", "QUALITY_CLAIM", "SERVICE", "SERVICE_ACCEPTANCE_ACT", "SERVICE_FINANCE_APPROVAL", "WORK_COMPLETION_ACT", "MATERIAL_RESERVATION", "STOCK_MOVEMENT", "ASSET_ACCEPTANCE", "ASSET_HANDOVER", "TRANSFER_DOCUMENT_APPROVE", "INVENTORY_PERIOD_CLOSE"),
            EnumDefinition.of("FrontendRfqStatus", "DRAFT", "AMENDMENT_DRAFT", "PUBLISHING", "OPEN", "BIDS_OPENED", "CONFLICT_CHECKED", "EVALUATED", "AWARDED", "CLOSED", "CANCELLED", "ARCHIVED"),
            // Keep in sync with RFQService.normalizeTenderProcedure: only ProcurementMethodType
            // values where usesRfqEngine() is true. SINGLE_SOURCE follows Direct Award.
            EnumDefinition.of("FrontendRfqTenderProcedureType", "OPEN_TENDER", "CLOSED_TENDER", "OPEN_COMPETITION", "CLOSED_COMPETITION", "REQUEST_FOR_QUOTATION"),
            EnumDefinition.of("FrontendRfqPurpose", "PROCUREMENT", "PRICE_DISCOVERY"),
            EnumDefinition.of("FrontendRfqSelectionRule", "LOWEST_PRICE", "BEST_SCORE", "SINGLE_SUPPLIER"),
            EnumDefinition.of("FrontendRfqPublicationIssueCode",
                "RFQ_NOT_FOUND",
                "RFQ_STATUS_NOT_PUBLISHABLE",
                "RFQ_DUE_DATE_REQUIRED",
                "RFQ_DUE_DATE_EXPIRED",
                "RFQ_PACKAGE_LANGUAGE_INVALID",
                "RFQ_ITEMS_REQUIRED",
                "RFQ_SUPPLIERS_REQUIRED",
                "RFQ_SUPPLIER_NOT_FOUND",
                "RFQ_SUPPLIER_EMAIL_INVALID",
                "RFQ_SUPPLIER_PORTAL_EMAIL_CONFLICT",
                "RFQ_NO_ELIGIBLE_SUPPLIERS",
                "RFQ_DOCUMENT_MISSING",
                "RFQ_MAIL_NOT_CONFIGURED",
                "RFQ_PROCUREMENT_PROCEDURE_REQUIRED",
                "RFQ_TENDER_PRICE_BASIS_REQUIRED",
                "RFQ_PACKAGE_STALE",
                "RFQ_PACKAGE_RENDER_FAILED"
            ),
            EnumDefinition.of("FrontendInventoryStatus", "DRAFT", "PLANNED", "IN_PROGRESS", "COUNTING_COMPLETED", "DISCREPANCY_REVIEW", "WAITING_APPROVAL", "APPROVED", "ADJUSTMENT_POSTED", "CLOSED"),
            EnumDefinition.of("FrontendTenderBidOpeningProtocolsStatus", "POSTED", "CANCELLED"),
            EnumDefinition.of("FrontendTenderBidOpeningLinesStatus", "ACCEPTED", "REJECTED"),
            EnumDefinition.of("FrontendTenderConflictChecksStatus", "CLEAR", "UNRESOLVED", "RESOLVED"),
            EnumDefinition.of("FrontendTenderEvaluationProtocolsStatus", "APPROVED", "POSTED", "CANCELLED"),
            EnumDefinition.of("FrontendTenderEvaluationProtocolsSelectionRule", "LOWEST_PRICE", "BEST_SCORE", "SINGLE_SUPPLIER"),
            EnumDefinition.of("FrontendQuotationsStatus", "DRAFT", "SUBMITTED", "SELECTED", "REJECTED"),
            EnumDefinition.of("FrontendPurchaseOrdersStatus", "DRAFT", "APPROVAL_PENDING", "APPROVED", "REJECTED", "RETURNED", "SENT", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED"),
            EnumDefinition.of("FrontendPurchaseOrdersReceivingStatus", "SENT", "PARTIALLY_RECEIVED"),
            EnumDefinition.of("FrontendGoodsReceiptStatus", "CONFIRMED", "DISPATCHED", "ARRIVED", "READY_TO_RECEIVE", "DISCREPANCY", "SENT", "PARTIALLY_RECEIVED", "RECEIVED"),
            EnumDefinition.of("FrontendDeliverySchedulesStatus", "PLANNED", "PARTIALLY_RECEIVED", "RECEIVED", "OVERDUE", "CANCELLED"),
            EnumDefinition.of("FrontendLandedCostComponentsStatus", "DRAFT", "POSTED"),
            EnumDefinition.of("FrontendLandedLogisticsEventsStatus", "RECORDED"),
            EnumDefinition.of("FrontendLandedLogisticsEventsExternalSystem", "FI", "TREASURY", "BUDGETING", "PRODUCTION", "HR", "EDO", "E_IMZO", "ETTN", "SOLIQ", "DIDOX", "FAKTURA_UZ", "CHATGPT", "CLAUDE", "GEMINI", "EXCHANGE", "WMS", "SCADA", "LIMS", "ONE_C"),
            EnumDefinition.of("FrontendContractAddendumsStatus", "DRAFT", "POSTED"),
            EnumDefinition.of("FrontendContractAddendumsAddendumType", "PROLONGATION", "LIMIT_CHANGE", "SPECIFICATION_CHANGE", "OTHER"),
            EnumDefinition.of("FrontendReferenceMappingsReferenceEntityType", "MATERIAL", "EQUIPMENT", "MATERIAL_ANALOG", "MATERIAL_EQUIPMENT_COMPATIBILITY", "MATERIAL_CONSUMPTION_NORM", "MANUFACTURER", "MATERIAL_GROUP", "UNIT_OF_MEASURE", "WAREHOUSE", "STORAGE_LOCATION", "STOCK_BATCH", "CURRENCY", "CURRENCY_RATE", "CLASSIFIER", "CLASSIFIER_TYPE", "VALUATION_METHOD", "INCOTERM", "MXIK_CODE", "TNVED_CODE", "BUDGET_ITEM", "DEPARTMENT", "DEPARTMENT_TYPE", "POSITION", "EMPLOYEE", "SUPPLIER", "BANK", "RFID_TAG", "TSD_OFFLINE_OPERATION", "CROSS_DOCKING_TASK", "PUTAWAY_RULE"),
            EnumDefinition.of("FrontendReferenceMappingsExternalSystem", "ONE_C", "FI", "TREASURY", "BUDGETING", "HR", "WMS", "SCADA", "SOLIQ", "DIDOX", "FAKTURA_UZ", "KIMYOSANOAT", "CHATGPT", "CLAUDE", "GEMINI", "E_IMZO", "ETTN", "EXCHANGE", "CUSTOM"),
            EnumDefinition.of("FrontendReferenceMappingsAccountingArea", "MANAGEMENT", "FINANCIAL", "TAX", "WAREHOUSE", "HR", "BUDGET", "PROCUREMENT"),
            EnumDefinition.of("FrontendReferenceMappingsAiProviderSystem", "CHATGPT", "CLAUDE", "GEMINI"),
            EnumDefinition.of("FrontendChartOfAccountsAccountType", "ACTIVE", "PASSIVE", "ACTIVE_PASSIVE"),
            EnumDefinition.of("FrontendEmployeesStatus", "ACTIVE", "INACTIVE", "DISMISSED"),
            EnumDefinition.of("FrontendEmployeesRoles", "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "ACCOUNTANT", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "EXECUTIVE", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER"),
            EnumDefinition.of("FrontendDemandForecastsStatus", "GENERATED", "APPROVED", "ARCHIVED"),
            EnumDefinition.of("FrontendInventoryOptimizationStatus", "GENERATED", "APPLIED", "ARCHIVED"),
            EnumDefinition.of("FrontendConsignmentTransactionsStatus", "OPEN", "SETTLED", "CANCELLED"),
            EnumDefinition.of("FrontendValuationDocumentsStatus", "DRAFT", "POSTED"),
            EnumDefinition.of("FrontendValuationDocumentsDocumentType", "REVALUATION", "IMPAIRMENT_RESERVE"),
            EnumDefinition.of("FrontendLandedCostAllocationsStatus", "DRAFT", "POSTED"),
            EnumDefinition.of("FrontendLandedCostAllocationLinesStatus", "ACTIVE"),
            EnumDefinition.of("FrontendInventoryFxDifferencesStatus", "CALCULATED", "POSTED"),
            EnumDefinition.of("FrontendInventoryPeriodClosesStatus", "DRAFT", "CALCULATED", "CLOSED", "REOPENED"),
            EnumDefinition.of("FrontendInventoryPeriodClosesAccountingReconciliationStatus", "NOT_CHECKED", "MATCHED", "DIFFERENCE"),
            EnumDefinition.of("FrontendInventoryAccountingReconciliationsStatus", "DRAFT", "RECONCILED", "POSTED"),
            EnumDefinition.of("FrontendInventoryAccountingReconciliationsDifferenceStatus", "NOT_CHECKED", "MATCHED", "DIFFERENCE"),
            EnumDefinition.of("FrontendNonMovingStockStatus", "DETECTED"),
            EnumDefinition.of("FrontendNonMovingStockCasesStatus", "DETECTED", "CASE_CREATED", "UNDER_REVIEW", "COMMISSION_REVIEW", "DECISION_APPROVED", "IN_EXECUTION", "EXECUTED", "CLOSED", "CANCELLED"),
            EnumDefinition.of("FrontendNonMovingStockCasesDecisionType", "TRANSFER", "WRITE_OFF", "SCRAP", "IMPAIRMENT_RESERVE", "KEEP_WITH_REVIEW_DATE"),
            EnumDefinition.of("FrontendNonMovingStockBlockerType", "QUANTITY_REDUCED", "LOCATION_CHANGED", "BATCH_CHANGED", "STOCK_BLOCKED", "PERIOD_CLOSED", "INSUFFICIENT_STOCK", "ALREADY_EXECUTED"),
            EnumDefinition.of("FrontendNonMovingStockEvidenceType", "COMMISSION_PROTOCOL", "INVESTIGATION_REPORT", "PHOTO", "OTHER"),
            EnumDefinition.of("FrontendNonMovingStockExclusionResult", "INCLUDED"),
            EnumDefinition.of("FrontendIntegrationSystemsStatus", "DISABLED", "MOCK", "MOCK_CONFIGURED", "LIVE_CONFIGURED", "NEW", "PUBLISHED", "FAILED", "RETRY"),
            EnumDefinition.of("FrontendUsersRoles", "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "ACCOUNTANT", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "EXECUTIVE", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER"),
            EnumDefinition.of("FrontendSegregationOfDutyRulesRuleType", "CREATOR_CANNOT_APPROVE", "SAME_USER_FIELD", "ACTION_ACTOR_CANNOT_PERFORM"),
            EnumDefinition.of("FrontendSegregationOfDutyRulesSeverity", "BLOCK", "WARN"),
            EnumDefinition.of("FrontendSegregationOfDutyViolationsStatus", "BLOCKED", "WARNED", "OVERRIDDEN"),
            EnumDefinition.of("FrontendSegregationOfDutyViolationsDecision", "BLOCKED", "WARNED", "OVERRIDDEN"),
            EnumDefinition.of("FrontendProductionEvidenceStatus", "DRAFT", "PASS", "WARN", "FAIL", "ACCEPTED"),
            EnumDefinition.of("FrontendGoodsReceiptQualityStatus", "PENDING_INSPECTION", "ACCEPTED", "HOLD", "REJECTED"),
            EnumDefinition.of("FrontendApprovalWorkflowFlowStatus", "ACTIVE", "INACTIVE", "DRAFT"),
            EnumDefinition.of("FrontendApprovalWorkflowsSeverity", "CRITICAL", "WARNING", "INFO"),
            EnumDefinition.of("FrontendAiAnalysisSeverity", "CRITICAL", "WARNING", "INFO"),
            EnumDefinition.of("FrontendAiAnalysisPriority", "HIGH", "MEDIUM", "LOW"),
            EnumDefinition.of("FrontendAiProviderAuthType", "BEARER", "API_KEY"),
            EnumDefinition.of("FrontendAnalyticsCenterHealthStatus", "HEALTHY", "READY", "DEGRADED", "PARTIAL", "CHECKING", "UNAVAILABLE"),
            EnumDefinition.of("FrontendDataMigrationRowStatus", "OK", "WARNING", "ERROR"),
            EnumDefinition.of("FrontendOrgStructureNodeStatus", "ACTIVE", "INACTIVE", "BLOCKED", "ARCHIVED"),
            EnumDefinition.of("FrontendUserFeedbackStatus", "NEW", "DONE", "CLOSED", "REJECTED"),
            EnumDefinition.of("FrontendManagementOpportunityAgingBucket", "0_7_DAYS", "8_30_DAYS", "31_60_DAYS", "61_PLUS_DAYS"),
            EnumDefinition.of("FrontendManagementOverviewDecisionSeverity", "CRITICAL", "HIGH", "WARNING", "MEDIUM", "INFO", "LOW"),
            EnumDefinition.of("FrontendMxikType", "PRODUCT", "SERVICE"),
            EnumDefinition.of("FrontendInventoryAbcClass", "A", "B", "C", "UNCLASSIFIED"),
            EnumDefinition.of("FrontendInventoryXyzClass", "X", "Y", "Z", "UNCLASSIFIED"),
            EnumDefinition.of("FrontendInventoryPeriodCloseReadinessCode", "OPEN_MOVEMENTS", "MISSING_COST_LAYERS", "ZERO_COST_INBOUND", "UNPOSTED_VALUATIONS", "OPEN_LANDED_COST", "VALUATION_RECONCILIATION", "FI_RECONCILIATION_EXISTS", "FI_RECONCILIATION_POSTED", "FI_RECONCILIATION_MATCHED", "FINANCE_INTEGRATIONS", "WORKSPACE_DATA_LOAD", "PERIOD_CLOSED", "CLOSE_PERIOD", "VERIFY_DOWNSTREAM_OR_REOPEN"),
            EnumDefinition.of("FrontendQualityQueueKey", "OPEN", "AWAITING_QUARANTINE", "AWAITING_SAMPLING", "LAB_PENDING", "DECISION_REQUIRED", "OVERDUE", "COMPLETED"),
            EnumDefinition.of("FrontendServicesAcceptanceStage", "PARTIAL", "FINAL"),
            // Enforced by ServiceRequestMilestoneService ("acceptanceMode must be AMOUNT or QUANTITY").
            EnumDefinition.of("FrontendServicesAcceptanceMode", "AMOUNT", "QUANTITY"),

            EnumDefinition.of("FrontendSupplierAccreditationDocumentsDocumentType", "REGISTRATION", "TAX_CERTIFICATE", "LICENSE", "QUALITY_CERTIFICATE", "BANK_DETAILS", "OTHER"),
            EnumDefinition.of("FrontendSupplierLicensesLicenseType", "LICENSE", "OPERATING_LICENSE", "INDUSTRIAL_LICENSE", "IMPORT_LICENSE", "EXPORT_LICENSE", "CONSTRUCTION_LICENSE", "TRANSPORT_LICENSE", "ENVIRONMENTAL_PERMIT", "SAFETY_PERMIT", "QUALITY_CERTIFICATE"),
            EnumDefinition.of("FrontendIntegrationDispatchAction", "CONNECTIVITY_TEST", "TAX_INVOICE", "DOCUMENT", "SUPPLIER_STATUS", "DOCUMENT_REGISTER", "PAYMENT_REQUEST", "BUDGET_CHECK_REQUESTED", "MATERIAL_DEMAND", "EMPLOYEE_SYNC", "MASTER_DATA_SYNC", "RECEIPT_TASK", "TRANSFER_TASK", "CONSUMPTION_SIGNAL", "TRANSPORT_DOCUMENT", "RFQ_PUBLISHED"),
            EnumDefinition.of("FrontendSmsProvider", "CUSTOM", "PLAYMOBILE", "ESKIZ", "TWILIO"),
            EnumDefinition.of("FrontendSsoProviderType", "OIDC", "SAML"),
            EnumDefinition.of("FrontendIntegrationAuthType", "NONE", "BASIC", "BEARER", "API_KEY", "OAUTH2"),
            EnumDefinition.of("FrontendIntegrationSystemsReadinessStatus", "LIVE_CONFIGURED", "MOCK_OUTBOX", "DISABLED", "NOT_CONFIGURED", "CONFIGURATION_ERROR", "UNKNOWN"),
            EnumDefinition.of("FrontendReferenceDataStatus", "ACTIVE", "INACTIVE"),
            EnumDefinition.of("FrontendMarketPricesStatus", "DRAFT", "APPROVED"),
            EnumDefinition.of("FrontendTenderConflictSeverity", "LOW", "MEDIUM", "HIGH", "CRITICAL"),
            // Legacy free-text sourcing-approach values stored on ProcurementCategoryStrategy —
            // distinct from the current ProcurementMethodType catalog (see procurement-strategies
            // .sourcing-approaches / procurement-campaigns.methods); kept as its own supplemental
            // enum rather than remapped, since the stored data was never migrated to the new codes.
            EnumDefinition.of("FrontendProcurementCategoryStrategiesSourcingApproach", "COMPETITIVE_TENDER", "FRAME_AGREEMENT", "SINGLE_SOURCE", "SPOT_BUY", "E_AUCTION")
        );
    }

    private void addSupplementalDefinitions(Map<String, EnumDefinition> definitions) {
        for (EnumDefinition definition : supplementalDefinitions()) {
            for (String alias : aliases(definition.enumName())) {
                definitions.putIfAbsent(alias, definition);
            }
        }
    }

    private void addSupplementalModuleAliases(Map<String, EnumDefinition> moduleDefinitions, String moduleKey) {
        for (Map.Entry<String, String> entry : supplementalModuleAliases(moduleKey).entrySet()) {
            globalDefinitionByEnumName(entry.getValue()).ifPresent(definition ->
                moduleDefinitions.put(entry.getKey(), definition)
            );
        }
    }

    private Map<String, String> supplementalModuleAliases(String moduleKey) {
        return switch (normalizeEnumType(moduleKey)) {
            case "approval-workflows" -> Map.ofEntries(
                Map.entry("module-keys", "FrontendApprovalWorkflowsModuleKey"),
                Map.entry("flow-statuses", "FrontendApprovalWorkflowFlowStatus")
            );
            case "chart-of-accounts" -> Map.ofEntries(
                Map.entry("account-types", "FrontendChartOfAccountsAccountType")
            );
            case "consignment-transactions" -> Map.ofEntries(
                Map.entry("statuses", "FrontendConsignmentTransactionsStatus")
            );
            case "contract-addendums" -> Map.ofEntries(
                Map.entry("statuses", "FrontendContractAddendumsStatus"),
                Map.entry("addendum-types", "FrontendContractAddendumsAddendumType")
            );
            case "delivery-schedules" -> Map.ofEntries(
                Map.entry("statuses", "FrontendDeliverySchedulesStatus")
            );
            case "demand-forecasts" -> Map.ofEntries(
                Map.entry("statuses", "FrontendDemandForecastsStatus")
            );
            case "demand-plan-items" -> Map.ofEntries(
                Map.entry("statuses", "DemandPlanItemStatus"),
                Map.entry("demand-types", "FrontendDemandPlanItemsDemandType")
            );
            case "employees" -> Map.ofEntries(
                Map.entry("statuses", "FrontendEmployeesStatus"),
                Map.entry("roles", "FrontendEmployeesRoles")
            );
            case "equipment" -> Map.ofEntries(
                Map.entry("statuses", "FrontendEquipmentStatus")
            );
            case "goods-receipt" -> Map.ofEntries(
                Map.entry("statuses", "FrontendGoodsReceiptStatus"),
                Map.entry("quality-statuses", "FrontendGoodsReceiptQualityStatus")
            );
            case "integration-systems" -> Map.ofEntries(
                Map.entry("statuses", "FrontendIntegrationSystemsStatus"),
                Map.entry("dispatch-actions", "FrontendIntegrationDispatchAction"),
                Map.entry("sms-providers", "FrontendSmsProvider"),
                Map.entry("sso-provider-types", "FrontendSsoProviderType"),
                Map.entry("auth-types", "FrontendIntegrationAuthType"),
                Map.entry("readiness-statuses", "FrontendIntegrationSystemsReadinessStatus")
            );
            case "inventory" -> Map.ofEntries(
                Map.entry("statuses", "FrontendInventoryStatus")
            );
            case "inventory-accounting-reconciliations" -> Map.ofEntries(
                Map.entry("statuses", "FrontendInventoryAccountingReconciliationsStatus"),
                Map.entry("difference-statuses", "FrontendInventoryAccountingReconciliationsDifferenceStatus")
            );
            case "inventory-fx-differences" -> Map.ofEntries(
                Map.entry("statuses", "FrontendInventoryFxDifferencesStatus")
            );
            case "inventory-optimization" -> Map.ofEntries(
                Map.entry("statuses", "FrontendInventoryOptimizationStatus")
            );
            case "inventory-period-closes" -> Map.ofEntries(
                Map.entry("statuses", "FrontendInventoryPeriodClosesStatus"),
                Map.entry("accounting-reconciliation-statuses", "FrontendInventoryPeriodClosesAccountingReconciliationStatus")
            );
            case "landed-cost-allocation-lines" -> Map.ofEntries(
                Map.entry("statuses", "FrontendLandedCostAllocationLinesStatus")
            );
            case "landed-cost-allocations" -> Map.ofEntries(
                Map.entry("statuses", "FrontendLandedCostAllocationsStatus")
            );
            case "landed-cost-components" -> Map.ofEntries(
                Map.entry("statuses", "FrontendLandedCostComponentsStatus")
            );
            case "landed-logistics-events" -> Map.ofEntries(
                Map.entry("statuses", "FrontendLandedLogisticsEventsStatus"),
                Map.entry("external-systems", "FrontendLandedLogisticsEventsExternalSystem")
            );
            case "material-attribute-templates" -> Map.ofEntries(
                Map.entry("data-types", "FrontendMaterialAttributeTemplatesDataType")
            );
            case "material-duplicate-candidates" -> Map.ofEntries(
                Map.entry("statuses", "FrontendMaterialDuplicateCandidatesStatus")
            );
            case "material-master-requests" -> Map.ofEntries(
                Map.entry("statuses", "FrontendMaterialMasterRequestsStatus"),
                Map.entry("request-types", "FrontendMaterialMasterRequestsRequestType")
            );
            case "material-quality-issues" -> Map.ofEntries(
                Map.entry("statuses", "FrontendMaterialQualityIssuesStatus"),
                Map.entry("issue-types", "FrontendMaterialQualityIssuesIssueType")
            );
            case "non-moving-stock" -> Map.ofEntries(
                Map.entry("statuses", "FrontendNonMovingStockStatus"),
                Map.entry("blocker-types", "FrontendNonMovingStockBlockerType"),
                Map.entry("evidence-types", "FrontendNonMovingStockEvidenceType"),
                Map.entry("exclusion-results", "FrontendNonMovingStockExclusionResult")
            );
            case "non-moving-stock-cases" -> Map.ofEntries(
                Map.entry("statuses", "FrontendNonMovingStockCasesStatus"),
                Map.entry("decision-types", "FrontendNonMovingStockCasesDecisionType")
            );
            case "procurement-campaigns" -> Map.ofEntries(
                // V202 sets procurement_campaigns.procedure_type = procedure.method, so this
                // column now carries ProcurementMethodType codes too.
                Map.entry("procedure-types", "ProcurementMethodType")
            );
            case "production-evidence" -> Map.ofEntries(
                Map.entry("statuses", "FrontendProductionEvidenceStatus")
            );
            case "purchase-orders" -> Map.ofEntries(
                Map.entry("statuses", "FrontendPurchaseOrdersStatus"),
                Map.entry("receiving-statuses", "FrontendPurchaseOrdersReceivingStatus")
            );
            case "purchase-requests" -> Map.ofEntries(
                Map.entry("statuses", "FrontendPurchaseRequestsStatus")
            );
            case "quotations" -> Map.ofEntries(
                Map.entry("statuses", "FrontendQuotationsStatus"),
                Map.entry("source", "QuotationSource")
            );
            case "reference-mappings" -> Map.ofEntries(
                Map.entry("reference-entity-types", "FrontendReferenceMappingsReferenceEntityType"),
                Map.entry("external-systems", "FrontendReferenceMappingsExternalSystem"),
                Map.entry("accounting-areas", "FrontendReferenceMappingsAccountingArea"),
                Map.entry("ai-provider-systems", "FrontendReferenceMappingsAiProviderSystem")
            );
            case "rfq" -> Map.ofEntries(
                Map.entry("statuses", "FrontendRfqStatus"),
                // V202 migrated rfqs.tender_procedure_type onto the ProcurementMethodType
                // vocabulary, but RFQService.normalizeTenderProcedure only accepts methods that
                // drive the RFQ engine — publishing the full enum would offer options the server
                // rejects, so this mirrors exactly that admissible subset.
                Map.entry("tender-procedure-types", "FrontendRfqTenderProcedureType"),
                Map.entry("selection-rules", "FrontendRfqSelectionRule"),
                Map.entry("publication-issue-codes", "FrontendRfqPublicationIssueCode")
            );
            case "segregation-of-duty-rules" -> Map.ofEntries(
                Map.entry("rule-types", "FrontendSegregationOfDutyRulesRuleType"),
                Map.entry("severities", "FrontendSegregationOfDutyRulesSeverity")
            );
            case "segregation-of-duty-violations" -> Map.ofEntries(
                Map.entry("statuses", "FrontendSegregationOfDutyViolationsStatus"),
                Map.entry("decisions", "FrontendSegregationOfDutyViolationsDecision")
            );
            case "supplier-accreditation-decisions" -> Map.ofEntries(
                Map.entry("statuses", "FrontendSupplierAccreditationDecisionsStatus")
            );
            case "supplier-accreditation-documents" -> Map.ofEntries(
                Map.entry("statuses", "FrontendSupplierAccreditationDocumentsStatus"),
                Map.entry("document-types", "FrontendSupplierAccreditationDocumentsDocumentType")
            );
            case "supplier-accreditations" -> Map.ofEntries(
                Map.entry("statuses", "FrontendSupplierAccreditationsStatus"),
                Map.entry("accreditation-types", "FrontendSupplierAccreditationsAccreditationType"),
                Map.entry("compliance-recommendations", "FrontendSupplierAccreditationsComplianceRecommendation")
            );
            case "supplier-blacklist" -> Map.ofEntries(
                Map.entry("statuses", "FrontendSupplierBlacklistStatus"),
                Map.entry("categories", "FrontendSupplierBlacklistCategory")
            );
            case "supplier-kyc-checks" -> Map.ofEntries(
                Map.entry("remediation-required", "FrontendSupplierKycChecksRemediationRequired")
            );
            case "supplier-licenses" -> Map.ofEntries(
                Map.entry("statuses", "FrontendSupplierLicensesStatus"),
                Map.entry("renewal-required", "FrontendSupplierLicensesRenewalRequired"),
                Map.entry("license-types", "FrontendSupplierLicensesLicenseType")
            );
            case "supplier-portal-submissions" -> Map.ofEntries(
                Map.entry("submission-types", "FrontendSupplierPortalSubmissionsSubmissionType")
            );
            case "supplier-related-parties" -> Map.ofEntries(
                Map.entry("pep-flag", "FrontendSupplierRelatedPartiesPepFlag"),
                Map.entry("sanctions-match", "FrontendSupplierRelatedPartiesSanctionsMatch")
            );
            case "suppliers" -> Map.ofEntries(
                Map.entry("categories", "FrontendSuppliersCategory")
            );
            case "tender-bid-opening-lines" -> Map.ofEntries(
                Map.entry("statuses", "FrontendTenderBidOpeningLinesStatus")
            );
            case "tender-bid-opening-protocols" -> Map.ofEntries(
                Map.entry("statuses", "FrontendTenderBidOpeningProtocolsStatus")
            );
            case "tender-conflict-checks" -> Map.ofEntries(
                Map.entry("statuses", "FrontendTenderConflictChecksStatus"),
                Map.entry("severities", "FrontendTenderConflictSeverity")
            );
            case "tender-evaluation-protocols" -> Map.ofEntries(
                Map.entry("statuses", "FrontendTenderEvaluationProtocolsStatus"),
                Map.entry("selection-rules", "FrontendTenderEvaluationProtocolsSelectionRule")
            );
            case "users" -> Map.ofEntries(
                Map.entry("roles", "FrontendUsersRoles")
            );
            case "valuation-documents" -> Map.ofEntries(
                Map.entry("statuses", "FrontendValuationDocumentsStatus"),
                Map.entry("document-types", "FrontendValuationDocumentsDocumentType")
            );
            default -> Map.of();
        };
    }

    private Optional<EnumDefinition> inferredStatusDefinition(String moduleKey) {
        String expectedEnumName = toPascalCase(singularizeLastSegment(moduleKey)) + "Status";
        return globalDefinitions.values().stream()
            .filter(definition -> definition.enumName().equals(expectedEnumName))
            .findFirst();
    }

    private List<EnumOptionResponse> options(
        EnumDefinition definition,
        Map<String, Map<String, Map<String, String>>> translations
    ) {
        Map<String, Map<String, String>> enumTranslations =
            translations.getOrDefault(definition.enumName(), Map.of());
        return definition.values().stream()
            .map(value -> {
                Map<String, String> labels = labels(definition.enumName(), value, enumTranslations.getOrDefault(value, Map.of()));
                return new EnumOptionResponse(
                    value,
                    labels,
                    definition.sortOrder(value),
                    definition.active(value),
                    Map.of("color", definition.color(value))
                );
            })
            .toList();
    }

    private Map<String, String> labels(String enumName, String code, Map<String, String> translated) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> builtIn = BUILT_IN_LABELS.getOrDefault(enumName + "." + code, BUILT_IN_LABELS.getOrDefault(code, Map.of()));
        for (String locale : LOCALES) {
            String label = translated.get(locale);
            if (label == null || label.isBlank()) {
                label = builtIn.get(locale);
            }
            labels.put(locale, label == null || label.isBlank() ? humanizeCode(code) : label);
        }
        return labels;
    }

    private static Map<String, Map<String, String>> builtInLabels() {
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        putLabel(labels, "MaterialSubtype.MATERIAL", "Материалы", "Materiallar", "Materials");
        putLabel(labels, "MaterialSubtype.EQUIPMENT", "Оборудование", "Uskunalar", "Equipment");
        putLabel(labels, "MaterialSubtype.ANIMALS", "Животные", "Hayvonlar", "Animals");
        putLabel(labels, "APPROVAL_WORKFLOW", "Согласование", "Kelishuv", "Approval workflow");
        putLabel(labels, "ASSET_ACCEPTANCE", "Приемка основного средства", "Asosiy vosita qabuli", "Asset acceptance");
        putLabel(labels, "ASSET_HANDOVER", "Передача основного средства", "Asosiy vosita topshirish", "Asset handover");
        putLabel(labels, "YEAR", "Год", "Yil", "Year");
        putLabel(labels, "QUARTER", "Четверть", "Chorak", "Quarter");
        putLabel(labels, "RFQ_NOT_FOUND", "RFQ не найден", "RFQ topilmadi", "RFQ not found");
        putLabel(labels, "RFQ_STATUS_NOT_PUBLISHABLE", "RFQ нельзя опубликовать в текущем статусе", "RFQ joriy holatda e'lon qilinmaydi", "RFQ cannot be published in its current status");
        putLabel(labels, "RFQ_DUE_DATE_REQUIRED", "Укажите срок ответа по RFQ", "RFQ javob muddatini kiriting", "Response due date is required");
        putLabel(labels, "RFQ_DUE_DATE_EXPIRED", "Срок ответа по RFQ истёк", "RFQ javob muddati tugagan", "Response due date has expired");
        putLabel(labels, "RFQ_PACKAGE_LANGUAGE_INVALID", "Выберите язык пакета RFQ", "RFQ paketi tilini tanlang", "Select a valid RFQ package language");
        putLabel(labels, "RFQ_ITEMS_REQUIRED", "Добавьте позиции RFQ", "RFQ pozitsiyalarini qo'shing", "Add RFQ positions");
        putLabel(labels, "RFQ_SUPPLIERS_REQUIRED", "Пригласите хотя бы одного поставщика", "Kamida bitta yetkazib beruvchini taklif qiling", "Invite at least one supplier");
        putLabel(labels, "RFQ_SUPPLIER_NOT_FOUND", "Поставщик RFQ не найден", "RFQ yetkazib beruvchisi topilmadi", "RFQ supplier was not found");
        putLabel(labels, "RFQ_SUPPLIER_EMAIL_INVALID", "У поставщика нет корректного email для портала", "Yetkazib beruvchida portal uchun to'g'ri email yo'q", "Supplier needs a valid portal email");
        putLabel(labels, "RFQ_SUPPLIER_PORTAL_EMAIL_CONFLICT", "У поставщика конфликт email в портале", "Yetkazib beruvchining portal emailida ziddiyat bor", "Supplier portal email has a conflict");
        putLabel(labels, "RFQ_NO_ELIGIBLE_SUPPLIERS", "Нет поставщиков, которым можно отправить письмо RFQ", "RFQ xatini yuborish mumkin bo'lgan yetkazib beruvchi yo'q", "No supplier can receive the RFQ letter");
        putLabel(labels, "RFQ_DOCUMENT_MISSING", "Приложенный документ RFQ недоступен", "RFQ ilova hujjati mavjud emas", "Attached RFQ document is unavailable");
        putLabel(labels, "RFQ_MAIL_NOT_CONFIGURED", "Почта для отправки RFQ не настроена", "RFQ yuborish pochtasi sozlanmagan", "RFQ mail delivery is not configured");
        putLabel(labels, "RFQ_PROCUREMENT_PROCEDURE_REQUIRED", "У тендерного RFQ нет процедуры закупки", "Tender RFQ uchun xarid jarayoni yo'q", "Tender RFQ needs a procurement procedure");
        putLabel(labels, "RFQ_TENDER_PRICE_BASIS_REQUIRED", "Тендер нельзя опубликовать до финализации сбора цен", "Tender narx yig'ish yakunlanmaguncha e'lon qilinmaydi", "Finalize price discovery before publishing the tender");
        putLabel(labels, "RFQ_PACKAGE_STALE", "Пакет RFQ устарел, обновите предпросмотр", "RFQ paketi eskirgan, ko'rib chiqishni yangilang", "RFQ package is stale; refresh the preview");
        putLabel(labels, "RFQ_PACKAGE_RENDER_FAILED", "Не удалось сформировать пакет RFQ", "RFQ paketini yaratib bo'lmadi", "RFQ package could not be generated");
        putLabel(labels, "DRAFT", "Черновик", "Qoralama", "Draft");
        putLabel(labels, "SUBMITTED", "Отправлено", "Yuborilgan", "Submitted");
        putLabel(labels, "APPROVAL_PENDING", "На согласовании", "Tasdiqlashda", "Approval pending");
        putLabel(labels, "PENDING", "Ожидает", "Kutilmoqda", "Pending");
        putLabel(labels, "APPROVED", "Согласовано", "Tasdiqlangan", "Approved");
        putLabel(labels, "REJECTED", "Отклонено", "Rad etilgan", "Rejected");
        putLabel(labels, "RETURNED", "Возвращено", "Qaytarilgan", "Returned");
        putLabel(labels, "ACTIVE", "Активен", "Faol", "Active");
        putLabel(labels, "INACTIVE", "Неактивен", "Nofaol", "Inactive");
        putLabel(labels, "CANCELLED", "Отменено", "Bekor qilingan", "Cancelled");
        putLabel(labels, "CLOSED", "Закрыто", "Yopilgan", "Closed");
        putLabel(labels, "POSTED", "Проведено", "O'tkazilgan", "Posted");
        putLabel(labels, "RECEIVED", "Принято", "Qabul qilingan", "Received");
        putLabel(labels, "PARTIALLY_RECEIVED", "Частично принято", "Qisman qabul qilingan", "Partially received");
        putLabel(labels, "SENT", "Отправлено", "Yuborilgan", "Sent");
        putLabel(labels, "GOODS", "Товары", "Tovarlar", "Goods");
        putLabel(labels, "SERVICE", "Услуги", "Xizmatlar", "Services");
        putLabel(labels, "SERVICES", "Услуги", "Xizmatlar", "Services");
        putLabel(labels, "FIXED_ASSET", "Основное средство", "Asosiy vosita", "Fixed asset");
        putLabel(labels, "TMB", "ТМБ", "TMB", "TMB");
        putLabel(labels, "WORKS", "Работы", "Ishlar", "Works");
        putLabel(labels, "LOW", "Низкий", "Past", "Low");
        putLabel(labels, "MEDIUM", "Средний", "O'rta", "Medium");
        putLabel(labels, "HIGH", "Высокий", "Yuqori", "High");
        putLabel(labels, "CRITICAL", "Критический", "Juda muhim", "Critical");
        putLabel(labels, "YES", "Да", "Ha", "Yes");
        putLabel(labels, "NO", "Нет", "Yo'q", "No");
        putLabel(labels, "NONE", "Нет", "Yo'q", "None");
        putLabel(labels, "OPTIONAL", "Необязательно", "Ixtiyoriy", "Optional");
        putLabel(labels, "REQUIRED", "Обязательно", "Majburiy", "Required");
        putLabel(labels, "PROCUREMENT_METHOD_DECISION", "\u0420\u0435\u0448\u0435\u043d\u0438\u0435 \u043f\u043e \u043c\u0435\u0442\u043e\u0434\u0443 \u0437\u0430\u043a\u0443\u043f\u043a\u0438", "Xarid usuli qarori", "Procurement method decision");
        return Map.copyOf(labels);
    }

    private static void putLabel(Map<String, Map<String, String>> labels, String code, String ru, String uz, String en) {
        labels.put(code, Map.of("ru", ru, "uz", uz, "en", en));
    }

    private static String humanizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String normalized = code.replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return code;
        }
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private Map<String, Map<String, Map<String, String>>> translationsByEnumName(Set<String> enumNames) {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
        for (EnumTranslation translation : translationRepository.findByEnumNameIn(enumNames)) {
            result
                .computeIfAbsent(translation.getEnumName(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(translation.getCode(), ignored -> new LinkedHashMap<>())
                .put(localeKey(translation.getLocale()), translation.getLabel());
        }
        return result;
    }

    private String localeKey(Locale locale) {
        if (locale == null || locale.getLanguage() == null || locale.getLanguage().isBlank()) {
            return "";
        }
        return locale.getLanguage();
    }

    private Map<String, Map<String, EnumDefinition>> buildDefinitions(
        Map<String, EnumDefinition> globalDefinitions
    ) {
        Map<String, Map<String, EnumDefinition>> result = new LinkedHashMap<>();
        add(result, globalDefinitions, "purchase-orders", "statuses", "PurchaseOrderStatus");
        add(result, globalDefinitions, "purchase-orders", "types", "RequestType");
        add(result, globalDefinitions, "purchase-orders", "workspace-views", "PurchaseOrderWorkspaceView");
        add(result, globalDefinitions, "purchase-orders", "process-stages", "PurchaseOrderProcessStage");
        add(result, globalDefinitions, "purchase-orders", "workspace-action-keys", "PurchaseOrderWorkspaceActionKey");
        add(result, globalDefinitions, "purchase-orders", "audit-actions", "PurchaseOrderAuditAction");
        add(result, globalDefinitions, "purchase-orders", "coverage-statuses", "PurchaseOrderCoverageStatus");
        add(result, globalDefinitions, "contract-invoices", "statuses", "ContractInvoiceStatus");
        add(result, globalDefinitions, "contract-payments", "statuses", "ContractPaymentStatus");
        add(result, globalDefinitions, "contract-payments", "source-document-types", "ProcurementSourceDocumentType");
        add(result, globalDefinitions, "purchase-requests", "statuses", "PurchaseRequestStatus");
        add(result, globalDefinitions, "purchase-requests", "request-types", "RequestType");
        add(result, globalDefinitions, "purchase-requests", "workspace-views", "PurchaseRequestWorkspaceView");
        add(result, globalDefinitions, "purchase-requests", "process-stages", "PurchaseRequestProcessStage");
        add(result, globalDefinitions, "purchase-requests", "workspace-action-keys", "PurchaseRequestWorkspaceActionKey");
        add(result, globalDefinitions, "purchase-requests", "coverage-statuses", "PurchaseRequestCoverageStatus");
        add(result, globalDefinitions, "demand-plans", "workspace-views", "DemandPlanWorkspaceView");
        add(result, globalDefinitions, "demand-plans", "process-stages", "DemandPlanProcessStage");
        add(result, globalDefinitions, "demand-plans", "workspace-action-keys", "DemandPlanWorkspaceActionKey");
        add(result, globalDefinitions, "demand-plans", "coverage-statuses", "DemandPlanCoverageStatus");
        add(result, globalDefinitions, "stock-movements", "statuses", "StockMovementStatus");
        add(result, globalDefinitions, "stock-movements", "movement-types", "MovementType");
        add(result, globalDefinitions, "materials", "statuses", "MaterialStatus");
        add(result, globalDefinitions, "materials", "material-types", "MaterialType");
        add(result, globalDefinitions, "materials", "material-subtypes", "MaterialSubtype");
        add(result, globalDefinitions, "materials", "tracking-modes", "TrackingMode");
        add(result, globalDefinitions, "materials", "batch-tracking-policies", "BatchTrackingPolicy");
        add(result, globalDefinitions, "materials", "serial-tracking-policies", "SerialTrackingPolicy");
        add(result, globalDefinitions, "materials", "valuation-methods", "ValuationMethod");
        add(result, globalDefinitions, "materials", "hazard-classes", "HazardClass");
        return result;
    }

    private void add(
        Map<String, Map<String, EnumDefinition>> result,
        Map<String, EnumDefinition> globalDefinitions,
        String moduleKey,
        String enumType,
        String enumName
    ) {
        EnumDefinition definition = globalDefinitions.values().stream()
            .filter(candidate -> candidate.enumName().equals(enumName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Enum class is not registered: " + enumName));
        result.computeIfAbsent(moduleKey, ignored -> new LinkedHashMap<>()).put(enumType, definition);
    }

    private Map<String, EnumDefinition> discoverGlobalDefinitions() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            CachingMetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
            Map<String, EnumDefinition> result = new LinkedHashMap<>();
            java.util.ArrayList<EnumDefinition> discovered = new java.util.ArrayList<>();
            for (String enumPackage : ENUM_PACKAGES) {
                Resource[] resources = resolver.getResources(
                    "classpath*:" + enumPackage.replace('.', '/') + "/*.class"
                );
                for (Resource resource : resources) {
                    MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                    Class<?> candidate = Class.forName(metadataReader.getClassMetadata().getClassName());
                    if (candidate.isEnum()) {
                        discovered.add(EnumDefinition.from(candidate.asSubclass(Enum.class)));
                    }
                }
            }
            discovered.sort(Comparator.comparing(EnumDefinition::enumName));
            for (EnumDefinition definition : discovered) {
                for (String alias : aliases(definition.enumName())) {
                    result.putIfAbsent(alias, definition);
                }
            }
            addSupplementalDefinitions(result);
            return result;
        } catch (ClassNotFoundException | IOException ex) {
            throw new IllegalStateException("Failed to discover backend enum catalog", ex);
        }
    }

    private Set<String> aliases(String enumName) {
        String kebab = camelToKebab(enumName);
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(pluralizeLastSegment(kebab));
        aliases.add(kebab);
        return aliases;
    }

    private String normalizeEnumType(String enumType) {
        return enumType == null ? "" : enumType.trim().toLowerCase(Locale.ROOT);
    }

    private String camelToKebab(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private String singularizeLastSegment(String value) {
        String[] parts = value.split("-");
        if (parts.length == 0) {
            return value;
        }
        parts[parts.length - 1] = singularize(parts[parts.length - 1]);
        return String.join("-", parts);
    }

    private String singularize(String value) {
        if (value.endsWith("ies") && value.length() > 3) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("ses") && value.length() > 3) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("s") && value.length() > 1) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String pluralizeLastSegment(String value) {
        String[] parts = value.split("-");
        if (parts.length == 0) {
            return value;
        }
        parts[parts.length - 1] = pluralize(parts[parts.length - 1]);
        return String.join("-", parts);
    }

    private String pluralize(String value) {
        if (value.endsWith("s")) {
            return value + "es";
        }
        if (value.endsWith("y")) {
            return value.substring(0, value.length() - 1) + "ies";
        }
        return value + "s";
    }

    private String toPascalCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.split("-")) {
            if (!part.isBlank()) {
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return result.toString();
    }

    private record EnumDefinition(
        String enumName,
        List<String> values,
        Map<String, Boolean> activeOverrides
    ) {
        static EnumDefinition of(String enumName, String... values) {
            return new EnumDefinition(enumName, List.of(values), Map.of());
        }

        static <E extends Enum<E>> EnumDefinition from(Class<E> enumClass) {
            return new EnumDefinition(
                enumClass.getSimpleName(),
                java.util.Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).toList(),
                Map.of()
            );
        }

        int sortOrder(String code) {
            int index = values.indexOf(code);
            return index < 0 ? values.size() + 1 : index + 1;
        }

        boolean active(String code) {
            return activeOverrides.getOrDefault(code, true);
        }

        String color(String code) {
            return switch (code) {
                case "APPROVED", "APPROVAL_PENDING", "PENDING", "SENT", "RFQ_CREATED",
                    "PARTIALLY_SENT_TO_TENDER", "FULLY_SENT_TO_TENDER", "GOODS", "RECEIPT",
                    "TRANSFER", "FIFO", "WEIGHTED_AVERAGE", "MOVING_AVERAGE" -> "blue";
                case "ACTIVE", "POSTED", "COMPLETED", "PARTIALLY_COMPLETED", "RECEIVED",
                    "PARTIALLY_RECEIVED", "CLOSED", "CONVERTED_TO_PO", "SERVICE", "SERVICES", "ISSUE" -> "green";
                case "RETURNED", "INACTIVE", "OBSOLETE", "RETURN", "WRITE_OFF",
                    "ADJUSTMENT", "STANDARD_COST", "LIFO", "MARKET_PRICE" -> "orange";
                case "REJECTED", "CANCELLED", "PROHIBITED", "REVERSAL",
                    "LOWER_OF_COST_OR_MARKET", "NET_REALIZABLE_VALUE" -> "red";
                case "FIXED_ASSET", "WORKS", "SPECIFIC_BATCH", "CLASS_7_RADIOACTIVE" -> "purple";
                case "TMB" -> "blue";
                default -> "default";
            };
        }
    }
}
