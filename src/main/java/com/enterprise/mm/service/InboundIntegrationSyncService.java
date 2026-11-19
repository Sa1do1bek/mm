package com.enterprise.mm.service;

import com.enterprise.mm.payload.HrDtos.HrDepartmentDto;
import com.enterprise.mm.payload.HrDtos.HrEmployeeDto;
import com.enterprise.mm.payload.HrDtos.HrPositionDto;
import com.enterprise.mm.payload.HrDtos.HrSyncRequest;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundDatasetSyncResult;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSyncRequest;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSyncResult;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSystemSyncResult;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.ChartOfAccount;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ExternalInboundRecord;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ExternalInboundRecordRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboundIntegrationSyncService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private static final int DEFAULT_LIMIT = 10_000;
    private final IntegrationSettingService settingService;
    private final IntegrationEventService eventService;
    private final HrIntegrationService hrIntegrationService;
    private final ProductCodeSyncService productCodeSyncService;
    private final ExternalInboundRecordRepository inboundRecordRepository;
    private final BudgetItemRepository budgetItemRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final ContractRepository contractRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.erp.OneCTransport oneCTransport;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Transactional
    public InboundSyncResult syncAll(InboundSyncRequest request) {
        List<ExternalIntegrationSystem> systems = systems(request);
        List<InboundSystemSyncResult> results = systems.stream().map(system -> syncSystem(system, request)).toList();
        return new InboundSyncResult(
            LocalDateTime.now(),
            results.size(),
            results.stream().mapToInt(InboundSystemSyncResult::received).sum(),
            results.stream().mapToInt(InboundSystemSyncResult::created).sum(),
            results.stream().mapToInt(InboundSystemSyncResult::updated).sum(),
            results.stream().mapToInt(InboundSystemSyncResult::archived).sum(),
            results.stream().mapToInt(InboundSystemSyncResult::skipped).sum(),
            results
        );
    }
    @Transactional
    public InboundSystemSyncResult syncSystem(ExternalIntegrationSystem system, InboundSyncRequest request) {
        if (system == ExternalIntegrationSystem.FAKTURA_UZ) {
            throw new BusinessException("Use the dedicated Faktura import endpoint with a date range");
        }
        List<InboundDatasetSyncResult> results = datasets(system, request).stream().map(dataset -> syncDataset(system, dataset, request)).toList();
        return new InboundSystemSyncResult(
            system,
            results.stream().mapToInt(InboundDatasetSyncResult::received).sum(),
            results.stream().mapToInt(InboundDatasetSyncResult::created).sum(),
            results.stream().mapToInt(InboundDatasetSyncResult::updated).sum(),
            results.stream().mapToInt(InboundDatasetSyncResult::archived).sum(),
            results.stream().mapToInt(InboundDatasetSyncResult::skipped).sum(),
            results
        );
    }
    private InboundDatasetSyncResult syncDataset(ExternalIntegrationSystem system, String dataset, InboundSyncRequest request) {
        try {
            if (system == ExternalIntegrationSystem.HR && "ORG_STRUCTURE".equals(dataset)) {
                return syncHr(request);
            }
            if (system == ExternalIntegrationSystem.SOLIQ && ("IKPU_CODES".equals(dataset) || "MXIK_CODES".equals(dataset))) {
                var result = "MXIK_CODES".equals(dataset)
                    ? productCodeSyncService.syncMxik(new ProductCodeSyncRequest(path(system, dataset, request), null, true, 1000, 0, 10_000, null, null, request != null && Boolean.TRUE.equals(request.requireLive())))
                    : productCodeSyncService.syncIkpu(new ProductCodeSyncRequest(path(system, dataset, request), null, true, 1000, 0, 10_000, null, null, request != null && Boolean.TRUE.equals(request.requireLive())));
                return emitDataset(system, dataset, path(system, dataset, request), result.mode(), result.received(), result.created(), result.updated(), 0, 0, result.message());
            }
            if ((system == ExternalIntegrationSystem.SOLIQ || system == ExternalIntegrationSystem.ETTN) && "TNVED_CODES".equals(dataset)) {
                var result = productCodeSyncService.syncTnved(new ProductCodeSyncRequest(path(system, dataset, request), null, true, 1000, 0, 10_000, null, null, request != null && Boolean.TRUE.equals(request.requireLive())));
                return emitDataset(system, dataset, path(system, dataset, request), result.mode(), result.received(), result.created(), result.updated(), 0, 0, result.message());
            }
            FetchResult fetched = fetch(system, dataset, request);
            Counter counter = new Counter();
            int limit = limit(request);
            for (JsonNode row : fetched.rows().stream().limit(limit).toList()) {
                AppliedEntity applied = apply(system, dataset, row);
                saveInboundRecord(system, dataset, fetched.sourcePath(), row, applied, counter);
                if (Boolean.TRUE.equals(request == null ? null : request.archiveOriginals())) {
                    counter.archived += archiveOriginal(system, row);
                }
            }
            return emitDataset(system, dataset, fetched.sourcePath(), fetched.mode(), fetched.rows().size(), counter.created, counter.updated, counter.archived, counter.skipped, fetched.message());
        } catch (Exception ex) {
            return emitDataset(system, dataset, path(system, dataset, request), "ERROR", 0, 0, 0, 0, 1, ex.getMessage());
        }
    }
    private InboundDatasetSyncResult syncHr(InboundSyncRequest request) {
        FetchResult departments = fetch(ExternalIntegrationSystem.HR, "DEPARTMENTS", request);
        FetchResult positions = fetch(ExternalIntegrationSystem.HR, "POSITIONS", request);
        FetchResult employees = fetch(ExternalIntegrationSystem.HR, "EMPLOYEES", request);
        var result = hrIntegrationService.sync(new HrSyncRequest(
            "HR",
            departments.rows().stream().map(this::hrDepartment).toList(),
            positions.rows().stream().map(this::hrPosition).toList(),
            employees.rows().stream().map(this::hrEmployee).toList()
        ));
        return new InboundDatasetSyncResult("ORG_STRUCTURE", "HR aggregate", "LIVE", result.departmentsUpserted() + result.positionsUpserted() + result.employeesUpserted(), result.departmentsUpserted() + result.positionsUpserted() + result.employeesUpserted(), 0, 0, 0, "HR org structure applied", result.integrationEventId());
    }
    private FetchResult fetch(ExternalIntegrationSystem system, String dataset, InboundSyncRequest request) {
        if(system==ExternalIntegrationSystem.ONE_C&&oneCTransport!=null&&oneCTransport.pullEnabled())
            throw new BusinessException("1C initiates inbound exchange; outbound import polling is disabled");
        IntegrationSettingService.RuntimeEndpoint endpoint = settingService.endpoint(system);
        String sourcePath = path(system, dataset, request);
        if (!endpoint.enabled() || !endpoint.configured() || endpoint.mockMode()) {
            if (request != null && Boolean.TRUE.equals(request.requireLive())) {
                throw new BusinessException(system.name() + " is not configured for live inbound sync");
            }
            return new FetchResult(sourcePath, "CONFIG_ONLY", List.of(), "Live endpoint is disabled or mock mode is enabled");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri(endpoint, sourcePath)))
                .timeout(Duration.ofSeconds(20))
                .GET();
            endpoint.headers().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Provider returned HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            return new FetchResult(sourcePath, "LIVE", rows(body), "Loaded from live API");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot fetch " + system.name() + "/" + dataset + ": " + ex.getMessage());
        }
    }
    private AppliedEntity apply(ExternalIntegrationSystem system, String dataset, JsonNode row) {
        return switch (dataset) {
            case "BUDGET_ITEMS", "BUDGET_LIMITS" -> budgetItem(row);
            case "CHART_OF_ACCOUNTS" -> chartOfAccount(system, row);
            case "PAYMENT_STATUSES", "PAYMENT_REQUESTS" -> contractPayment(row);
            case "INCOMING_DOCUMENTS", "OUTGOING_DOCUMENTS", "INVOICES" -> contractInvoice(row);
            case "STOCK_BALANCES" -> stockBalance(row);
            default -> AppliedEntity.none();
        };
    }
    private AppliedEntity budgetItem(JsonNode row) {
        String code = required(firstText(row, "budgetCode", "articleCode", "code", "externalCode", "external1CId"), "budget code");
        BudgetItem item = budgetItemRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), code).orElseGet(BudgetItem::new);
        item.setCode(code);
        item.setName(firstNonBlank(firstText(row, "name", "articleName", "title"), "Inbound Material"));
        item.setStatus(firstNonBlank(firstText(row, "status", "state"), "ACTIVE"));
        item.setDescription(firstText(row, "description", "note"));
        item.setAnnualLimit(decimal(row, "annualLimit", "limit", "amount"));
        item.setUsedAmount(decimal(row, "usedAmount", "consumed", "factAmount"));
        return applied("BudgetItem", budgetItemRepository.save(item).getId());
    }
    private AppliedEntity chartOfAccount(ExternalIntegrationSystem system, JsonNode row) {
        String accountCode = required(firstText(row, "accountCode", "account_code", "code"), "account code");
        ChartOfAccount item = chartOfAccountRepository.findByTenantIdAndCompanyIdAndAccountCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), accountCode).orElseGet(ChartOfAccount::new);
        item.setCode(accountCode);
        item.setAccountCode(accountCode);
        item.setName(firstNonBlank(firstText(row, "name", "accountName", "title"), "Inbound Account"));
        item.setStatus(firstNonBlank(firstText(row, "status", "state"), "ACTIVE"));
        item.setParentCode(firstText(row, "parentCode", "parent_code"));
        item.setAccountType(firstText(row, "accountType", "type"));
        item.setGroup(bool(row, "group", "isGroup"));
        item.setSourceSystem(system.name());
        item.setExternalId(firstText(row, "externalId", "external1CId", "id"));
        item.setLastSyncedAt(LocalDateTime.now());
        return applied("ChartOfAccount", chartOfAccountRepository.save(item).getId());
    }
    private AppliedEntity contractPayment(JsonNode row) {
        // Inbound payment sync is disabled until PO/invoice lineage is supplied by the source system.
        return AppliedEntity.none();
    }

    private AppliedEntity contractInvoice(JsonNode row) {
        // Inbound invoice sync is disabled until PO/evidence lineage is supplied by the source system.
        return AppliedEntity.none();
    }

    private AppliedEntity stockBalance(JsonNode row) {
        String materialCode = required(firstText(row, "materialCode", "productCode", "sku"), "material code");
        String warehouseCode = required(firstText(row, "warehouseCode", "warehouse", "locationCode"), "warehouse code");
        var material = materialRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), materialCode).orElse(null);
        var warehouse = warehouseRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), warehouseCode).orElse(null);
        if (material == null || warehouse == null || !com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId())) {
            return AppliedEntity.none();
        }
        if (material.getMaterialType() != MaterialType.TMB) {
            return AppliedEntity.none();
        }
        guardOpeningImport(material.getCompanyId());
        guardOpeningImport(warehouse.getCompanyId());
        var location = Optional.ofNullable(firstText(row, "storageLocationCode", "binCode"))
            .flatMap(code -> storageLocationRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), code))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElse(null);
        UUID storageLocationId = location == null ? null : location.getId();
        StockBalance item = stockBalanceRepository.findBalance(
            TenantContext.currentTenant(),
            material.getId(),
            warehouse.getId(),
            storageLocationId == null,
            storageLocationId,
            true,
            null
        ).orElseGet(StockBalance::new);
        guardOpeningImport(item.getCompanyId() == null ? CompanyContext.currentCompany() : item.getCompanyId());
        item.setMaterialId(material.getId());
        item.setWarehouseId(warehouse.getId());
        item.setStorageLocationId(location == null ? null : location.getId());
        item.setCode(materialCode + "@" + warehouseCode);
        item.setName(material.getName() + " / " + warehouse.getName());
        item.setStatus("ACTIVE");
        item.setQuantity(decimal(row, "quantity", "qty", "balance"));
        item.setAvailableQuantity(decimal(row, "availableQuantity", "available", "freeQuantity"));
        item.setReservedQuantity(decimal(row, "reservedQuantity", "reserved"));
        item.setTotalValue(decimal(row, "totalValue", "amount", "value"));
        return applied("StockBalance", stockBalanceRepository.save(item).getId());
    }
    private void saveInboundRecord(ExternalIntegrationSystem system, String dataset, String path, JsonNode row, AppliedEntity applied, Counter counter) {
        String externalId = required(firstText(row, "externalId", "external1CId", "id", "code", "number", "documentNumber"), "external id");
        String payload = row.toString();
        String payloadHash = sha256(payload);
        ExternalInboundRecord record = inboundRecordRepository.findByTenantIdAndCompanyIdAndSourceSystemAndDatasetAndExternalIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), system.name(), dataset, externalId).orElseGet(() -> {
            counter.created++;
            return new ExternalInboundRecord();
        });
        if (record.getId() != null && !payloadHash.equals(record.getPayloadHash())) {
            counter.updated++;
        } else if (record.getId() != null) {
            counter.skipped++;
        }
        record.setSourceSystem(system.name());
        record.setDataset(dataset);
        record.setExternalId(externalId);
        record.setExternalCode(firstText(row, "code", "externalCode", "external1CId"));
        record.setProviderStatus(firstText(row, "status", "state"));
        record.setSourcePath(path);
        record.setPayloadHash(payloadHash);
        record.setPayload(payload);
        record.setAppliedEntityType(applied.entityType());
        record.setAppliedEntityId(applied.entityId());
        record.setSyncedAt(LocalDateTime.now());
        record.setCode(shortCode(system.name() + "-" + dataset + "-" + externalId));
        record.setName(system.name() + " " + dataset + " inbound record");
        record.setStatus("SYNCED");
        inboundRecordRepository.save(record);
    }
    private int archiveOriginal(ExternalIntegrationSystem system, JsonNode row) {
        String externalId = firstText(row, "externalId", "id", "documentId", "documentNumber");
        if (externalId == null || externalId.isBlank()) {
            return 0;
        }
        settingService.archiveOriginalDocument(system, externalId);
        return 1;
    }
    private InboundDatasetSyncResult emitDataset(ExternalIntegrationSystem system, String dataset, String sourcePath, String mode, int received, int created, int updated, int archived, int skipped, String message) {
        IntegrationEvent event = eventService.emit("INBOUND." + system.name() + "." + dataset + ".SYNCED", system.name(), null, Map.of(
            "dataset", dataset,
            "sourcePath", sourcePath == null ? "" : sourcePath,
            "mode", mode,
            "received", received,
            "created", created,
            "updated", updated,
            "archived", archived,
            "skipped", skipped,
            "message", message == null ? "" : message
        ));
        return new InboundDatasetSyncResult(dataset, sourcePath, mode, received, created, updated, archived, skipped, message, event.getId());
    }
    private Optional<Contract> resolveContract(JsonNode row) {
        String code = firstText(row, "contractCode", "contractNumber", "contractExternalId");
        if (code == null) {
            return Optional.empty();
        }
        return contractRepository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), code)
            .or(() -> contractRepository.findByTenantIdAndCompanyIdAndContractNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), code));
    }
    private HrDepartmentDto hrDepartment(JsonNode row) {
        return new HrDepartmentDto(firstText(row, "externalId", "id", "code"), firstText(row, "parentExternalId", "parentId", "parentCode"), firstNonBlank(firstText(row, "name", "title"), "Department"), firstText(row, "departmentType", "type"), firstText(row, "costCenter"), integer(row, "sortOrder", "order"), firstNonBlank(firstText(row, "status"), "ACTIVE"));
    }
    private HrPositionDto hrPosition(JsonNode row) {
        return new HrPositionDto(firstText(row, "externalId", "id", "code"), firstText(row, "departmentExternalId", "departmentId", "departmentCode"), firstNonBlank(firstText(row, "name", "title"), "Position"), firstText(row, "positionCategory", "category"), firstText(row, "grade"), decimal(row, "headcount"), decimal(row, "occupiedHeadcount"), firstNonBlank(firstText(row, "status"), "ACTIVE"));
    }
    private HrEmployeeDto hrEmployee(JsonNode row) {
        return new HrEmployeeDto(firstText(row, "externalId", "id"), firstText(row, "personnelNumber", "employeeNumber", "tabNumber"), firstText(row, "departmentExternalId", "departmentId", "departmentCode"), firstText(row, "positionExternalId", "positionId", "positionCode"), firstText(row, "managerExternalId", "managerId"), firstNonBlank(firstText(row, "fullName", "name"), firstText(row, "lastName")), firstText(row, "email"), firstText(row, "phone"), firstNonBlank(firstText(row, "employmentStatus", "status"), "ACTIVE"), date(row, "hireDate", "hiredAt"), date(row, "dismissalDate", "firedAt"));
    }
    private List<ExternalIntegrationSystem> systems(InboundSyncRequest request) {
        if (request != null && request.systems() != null && !request.systems().isEmpty()) {
            return request.systems().stream().map(ExternalIntegrationSystem::from).toList();
        }
        return List.of(ExternalIntegrationSystem.HR, ExternalIntegrationSystem.BUDGETING, ExternalIntegrationSystem.TREASURY, ExternalIntegrationSystem.FI, ExternalIntegrationSystem.ONE_C, ExternalIntegrationSystem.PRODUCTION, ExternalIntegrationSystem.WMS, ExternalIntegrationSystem.SCADA, ExternalIntegrationSystem.EDO, ExternalIntegrationSystem.SOLIQ, ExternalIntegrationSystem.DIDOX, ExternalIntegrationSystem.EXCHANGE, ExternalIntegrationSystem.ETTN);
    }
    private List<String> datasets(ExternalIntegrationSystem system, InboundSyncRequest request) {
        if (request != null && request.datasets() != null && !request.datasets().isEmpty()) {
            return request.datasets().stream().map(this::normalize).toList();
        }
        return switch (system) {
            case HR -> List.of("ORG_STRUCTURE");
            case BUDGETING -> List.of("BUDGET_ITEMS", "BUDGET_LIMITS");
            case TREASURY -> List.of("PAYMENT_REQUESTS", "PAYMENT_STATUSES", "COMMITMENTS");
            case FI, ONE_C -> List.of("CHART_OF_ACCOUNTS", "ACCOUNTING_STATUSES");
            case PRODUCTION -> List.of("MATERIAL_DEMANDS", "PRODUCTION_RECEIPTS");
            case WMS -> List.of("STOCK_BALANCES", "WAREHOUSE_TASKS");
            case SCADA -> List.of("CONSUMPTION_SIGNALS");
            case EDO, DIDOX, FAKTURA_UZ -> List.of("INCOMING_DOCUMENTS", "OUTGOING_DOCUMENTS");
            case SOLIQ -> List.of("MXIK_CODES", "SUPPLIER_STATUSES");
            case ETTN -> List.of("TNVED_CODES", "TRANSPORT_DOCUMENTS");
            case EXCHANGE -> List.of("RFQ_STATUSES", "QUOTATIONS");
            default -> List.of("EVENTS");
        };
    }
    private String path(ExternalIntegrationSystem system, String dataset, InboundSyncRequest request) {
        String key = system.name() + "." + dataset;
        if (request != null && request.paths() != null && request.paths().containsKey(key)) {
            return request.paths().get(key);
        }
        return defaultPath(system, dataset);
    }
    private String defaultPath(ExternalIntegrationSystem system, String dataset) {
        return switch (dataset) {
            case "DEPARTMENTS" -> "/api/integrations/mm/departments";
            case "POSITIONS" -> "/api/integrations/mm/positions";
            case "EMPLOYEES" -> "/api/integrations/mm/employees";
            case "BUDGET_ITEMS", "BUDGET_LIMITS" -> "/api/v1/basic/treasury/commitments";
            case "PAYMENT_REQUESTS", "PAYMENT_STATUSES" -> "/api/v1/basic/treasury/payments/requests";
            case "COMMITMENTS" -> "/api/v1/basic/treasury/commitments";
            case "CHART_OF_ACCOUNTS" -> "/api/v1/basic/finmodel/chart-of-accounts";
            case "ACCOUNTING_STATUSES" -> "/api/v1/basic/finmodel/integrations/entries";
            case "MATERIAL_DEMANDS" -> "/api/v1/integrations/erp/material-demands";
            case "PRODUCTION_RECEIPTS" -> "/api/v1/integrations/erp/confirmed-production";
            case "STOCK_BALANCES" -> "/api/v1/integrations/wms/stock-balances";
            case "WAREHOUSE_TASKS" -> "/api/v1/integrations/wms/tasks";
            case "CONSUMPTION_SIGNALS" -> "/api/v1/integrations/scada/events";
            case "INCOMING_DOCUMENTS" -> system == ExternalIntegrationSystem.EDO ? "/api/integrations/mm/documents/incoming" : "/documents/incoming";
            case "OUTGOING_DOCUMENTS" -> system == ExternalIntegrationSystem.EDO ? "/api/integrations/mm/documents/outgoing" : "/documents/outgoing";
            case "IKPU_CODES", "MXIK_CODES" -> "https://tasnif.soliq.uz/api/cls-api/elasticsearch/search?search=&lang=ru";
            case "TNVED_CODES" -> "https://tarif.customs.uz/hscode-list";
            case "SUPPLIER_STATUSES" -> "/api/taxpayer/status";
            case "TRANSPORT_DOCUMENTS" -> "/api/documents/transport";
            case "RFQ_STATUSES", "QUOTATIONS" -> "/api/v1/basic/treasury/tenders";
            default -> "/events";
        };
    }
    private String uri(IntegrationSettingService.RuntimeEndpoint endpoint, String path) {
        return path != null && (path.startsWith("http://") || path.startsWith("https://")) ? path : endpoint.uri(path);
    }
    private List<JsonNode> rows(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return List.of();
        }
        if (body.isArray()) {
            return body.findValues("").isEmpty() ? iterable(body) : iterable(body);
        }
        for (String field : List.of("data", "items", "content", "results", "records", "rows", "documents", "employees", "departments", "positions")) {
            JsonNode node = body.get(field);
            if (node != null && !node.isNull()) {
                return rows(node);
            }
        }
        return body.isObject() ? List.of(body) : List.of();
    }
    private List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }
    private String firstText(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode node = row.path(field);
            if (!node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText().trim();
            }
        }
        return null;
    }
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("Inbound row does not contain " + label);
        }
        return value.trim();
    }
    private BigDecimal decimal(JsonNode row, String... fields) {
        String value = firstText(row, fields);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(" ", "").replace(",", "."));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }
    private LocalDate date(JsonNode row, String... fields) {
        String value = firstText(row, fields);
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception ex) {
            return null;
        }
    }
    private Integer integer(JsonNode row, String... fields) {
        String value = firstText(row, fields);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private boolean bool(JsonNode row, String... fields) {
        String value = firstText(row, fields);
        return value != null && List.of("true", "1", "yes", "y").contains(value.toLowerCase(Locale.ROOT));
    }
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private int limit(InboundSyncRequest request) {
        return request == null || request.limit() == null || request.limit() <= 0 ? DEFAULT_LIMIT : request.limit();
    }
    private String shortCode(String value) {
        String clean = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-");
        return clean.length() <= 80 ? clean : clean.substring(0, 47) + "-" + sha256(value).substring(0, 32);
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("Cannot hash inbound payload");
        }
    }
    private AppliedEntity applied(String type, UUID id) {
        return new AppliedEntity(type, id);
    }
    private record FetchResult(String sourcePath, String mode, List<JsonNode> rows, String message) {
    }
    private record AppliedEntity(String entityType, UUID entityId) {
        static AppliedEntity none() {
            return new AppliedEntity(null, null);
        }
    }
    private static class Counter {
        int created;
        int updated;
        int archived;
        int skipped;
    }
}
