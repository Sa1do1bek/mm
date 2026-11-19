package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Creates the server-owned context and rejects unsupported AI account choices. */
@Service
@RequiredArgsConstructor
public class MaterialStockAccountRecommendationService {
    private static final int MAX_ACCOUNTS = 100;
    private static final int MAX_EVIDENCE = 30;

    private final MaterialStockAccountResolutionService resolutionService;
    private final StockAccountResolver accounts;
    private final MaterialRepository materials;
    private final ObjectMapper mapper;
    private final AiAnalysisJobRepository jobs;
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService roleScope;
    private final ManualReceiptAccountDraftService receiptDrafts;

    public record Request(UUID materialId, UUID draftId, String requestedBy, Long materialVersion, String resolutionFingerprint,
                          String evidenceFingerprint,
                          List<UUID> allowedAccountIds, List<String> evidenceIds, JsonNode context) {}

    @Transactional(readOnly = true)
    public Request snapshot(UUID materialId, String requestedBy) {
        return snapshot(materialId, requestedBy, null);
    }

    @Transactional(readOnly = true)
    public Request snapshot(UUID materialId, String requestedBy, UUID draftId) {
        var resolution = resolutionService.get(materialId);
        if (!"CHOICE_REQUIRED".equals(resolution.status()) && !"ACCOUNT_REQUIRED".equals(resolution.status()))
            throw new BadRequestException("This material does not need an account recommendation");
        Material material = materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new BadRequestException("Material not found"));
        ManualReceiptAccountDraftService.Draft currentOperation = draftId == null ? null : receiptDrafts.get(draftId);
        if ("CHOICE_REQUIRED".equals(resolution.status()) && currentOperation == null)
            throw new BadRequestException("A saved receipt draft is required for this account recommendation");
        if (currentOperation != null && !materialId.equals(currentOperation.materialId()))
            throw new BadRequestException("Receipt draft material differs from recommendation material");
        List<MaterialStockAccountResolutionService.Account> allowed = "CHOICE_REQUIRED".equals(resolution.status())
            ? resolution.candidateAccounts()
            : accounts.eligibleAccounts().stream()
                .map(value -> new MaterialStockAccountResolutionService.Account(value.id(), value.accountCode(), value.accountName()))
                .toList();
        if (allowed.isEmpty() || allowed.size() > MAX_ACCOUNTS)
            throw new BadRequestException("Account candidate list is unavailable for AI recommendation");
        var evidence = resolution.evidence().stream().limit(MAX_EVIDENCE).toList();
        List<String> evidenceIds = evidence.stream().map(item -> "one-c:" + item.importId() + ":" + item.entryId()).toList();
        List<Map<String, Object>> evidenceRows = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            var item = evidence.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evidenceIds.get(i));
            row.put("sourceType", "ONE_C_BALANCE");
            row.put("sourceDate", item.snapshotDate());
            row.put("warehouseId", item.warehouseId());
            row.put("warehouseCode", item.warehouseCode());
            row.put("accountId", item.accountId());
            row.put("quantity", item.quantity());
            evidenceRows.add(row);
        }
        var scope = new MapSqlParameterSource("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany()).addValue("material", materialId);
        var warehouseScope = roleScope.currentScope();
        scope.addValue("unrestricted", !warehouseScope.warehouseScoped());
        scope.addValue("warehouses", warehouseScope.warehouseIds().isEmpty()
            ? List.of(new UUID(0, 0)) : warehouseScope.warehouseIds());
        jdbc.query("""
            select id,warehouse_id,stock_category,quantity,balance_at from stock_balances
            where tenant_id=:tenant and company_id=:company and material_id=:material and is_delete=false
              and (:unrestricted=true or warehouse_id in (:warehouses))
            order by balance_at desc nulls last,id limit 10
            """, scope, row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "stock:" + row.getObject("id", UUID.class));
                item.put("sourceType", "OPERATIONAL_BALANCE");
                item.put("warehouseId", row.getObject("warehouse_id", UUID.class));
                item.put("stockCategory", row.getString("stock_category"));
                item.put("quantity", row.getBigDecimal("quantity").toPlainString());
                item.put("balanceAt", row.getTimestamp("balance_at"));
                evidenceRows.add(item);
            });
        jdbc.query("""
            select m.id,m.warehouse_id,m.movement_date,m.movement_type,m.quantity,
                   m.account_code_snapshot,d.purpose
            from stock_movements m
            left join manual_receipt_account_drafts d on d.posted_movement_id=m.id
              and d.tenant_id=m.tenant_id and d.company_id=m.company_id and d.status='POSTED'
            where m.tenant_id=:tenant and m.company_id=:company and m.material_id=:material and m.is_delete=false
              and m.account_code_snapshot is not null
              and (:unrestricted=true or m.warehouse_id in (:warehouses))
            order by m.movement_date desc,m.id limit 10
            """, scope, row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "movement:" + row.getObject("id", UUID.class));
                item.put("sourceType", "OPERATIONAL_MOVEMENT");
                item.put("warehouseId", row.getObject("warehouse_id", UUID.class));
                item.put("date", row.getDate("movement_date"));
                item.put("movementType", row.getString("movement_type"));
                item.put("quantity", row.getBigDecimal("quantity").toPlainString());
                item.put("accountCode", row.getString("account_code_snapshot"));
                if (row.getString("purpose") != null && !row.getString("purpose").isBlank()) {
                    Map<String, Object> operation = new LinkedHashMap<>();
                    operation.put("operationType", "RECEIPT");
                    operation.put("purpose", row.getString("purpose"));
                    operation.put("warehouseId", row.getObject("warehouse_id", UUID.class));
                    item.put("operationContext", operation);
                }
                evidenceRows.add(item);
            });
        jdbc.query("""
            with latest as (
              select max(snapshot_date) snapshot_date from warehouse_account_imports
              where tenant_id=:tenant and company_id=:company and status='COMPLETED'
            )
            select e.id,e.import_id,e.source_date,e.account_code,e.quantity,e.document_key,
                   e.warehouse_code
            from warehouse_account_entries e
            join warehouse_account_imports r on r.id=e.import_id and r.tenant_id=e.tenant_id
              and r.company_id=e.company_id and r.status='COMPLETED'
            join latest on latest.snapshot_date=r.snapshot_date
            left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id
              and lm.company_id=e.company_id and lm.reference_kind='MATERIAL'
              and lm.reference_type=e.material_reference_type and lm.source_id=e.material_source_id
            left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id
              and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE'
              and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id
            where e.tenant_id=:tenant and e.company_id=:company and e.kind='MOVEMENT'
              and coalesce(e.material_id,lm.master_id)=:material
              and (:unrestricted=true or coalesce(e.warehouse_id,lw.master_id) in (:warehouses))
            order by e.source_date desc,e.id limit 10
            """, scope, row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "one-c-movement:" + row.getObject("import_id", UUID.class)
                    + ":" + row.getObject("id", UUID.class));
                item.put("sourceType", "ONE_C_MOVEMENT");
                item.put("date", row.getDate("source_date"));
                item.put("accountCode", row.getString("account_code"));
                item.put("warehouseCode", row.getString("warehouse_code"));
                item.put("quantity", row.getBigDecimal("quantity").toPlainString());
                item.put("documentKey", row.getString("document_key"));
                evidenceRows.add(item);
            });
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean canReadProcurement = authentication != null && authentication.getAuthorities()
            .stream().anyMatch(value -> "ROLE_ADMIN".equals(value.getAuthority()));
        if (canReadProcurement) {
            jdbc.query("""
                select s.id specification_id,c.id contract_id,c.status contract_status,
                       c.contract_number,c.start_date,p.id supplier_id,p.name supplier_name
                from contract_specifications s
                join contracts c on c.id=s.contract_id and c.tenant_id=s.tenant_id
                  and c.company_id=s.company_id and c.is_delete=false
                left join suppliers p on p.id=c.supplier_id and p.tenant_id=c.tenant_id
                  and p.company_id=c.company_id and p.is_delete=false
                where s.tenant_id=:tenant and s.company_id=:company and s.material_id=:material
                  and s.is_delete=false and c.status in ('APPROVED','COMPLETED')
                order by c.start_date desc nulls last,s.id limit 10
                """, scope, row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", "contract-specification:" + row.getObject("specification_id", UUID.class));
                    item.put("sourceType", "PROCUREMENT_SPECIFICATION");
                    item.put("contractId", row.getObject("contract_id", UUID.class));
                    item.put("status", row.getString("contract_status"));
                    item.put("contractNumber", row.getString("contract_number"));
                    item.put("date", row.getDate("start_date"));
                    item.put("supplierId", row.getObject("supplier_id", UUID.class));
                    item.put("supplierName", row.getString("supplier_name"));
                    evidenceRows.add(item);
                });
            jdbc.query("""
                select i.id item_id,o.id order_id,o.status,o.order_date,o.supplier_id,
                       p.name supplier_name
                from purchase_order_items i
                join purchase_orders o on o.id=i.purchase_order_id and o.tenant_id=i.tenant_id
                  and o.company_id=i.company_id and o.is_delete=false
                left join suppliers p on p.id=o.supplier_id and p.tenant_id=o.tenant_id
                  and p.company_id=o.company_id and p.is_delete=false
                where i.tenant_id=:tenant and i.company_id=:company and i.material_id=:material
                  and i.is_delete=false and o.status in ('APPROVED','COMPLETED')
                order by o.order_date desc nulls last,i.id limit 10
                """, scope, row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", "purchase-order-item:" + row.getObject("item_id", UUID.class));
                    item.put("sourceType", "PURCHASE_ORDER");
                    item.put("orderId", row.getObject("order_id", UUID.class));
                    item.put("status", row.getString("status"));
                    item.put("date", row.getDate("order_date"));
                    item.put("supplierId", row.getObject("supplier_id", UUID.class));
                    item.put("supplierName", row.getString("supplier_name"));
                    evidenceRows.add(item);
                });
        }
        if ("ACCOUNT_REQUIRED".equals(resolution.status()) && material.getCreatedAt() != null) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("id", "material:" + materialId);
            properties.put("sourceType", "MATERIAL_PROPERTIES");
            properties.put("sourceDate", material.getCreatedAt().toLocalDate());
            properties.put("name", material.getName());
            properties.put("subtype", material.getMaterialSubtype());
            properties.put("mxikCode", material.getMxikCode());
            evidenceRows.add(properties);
        }
        List<Map<String, Object>> aiEvidence = new ArrayList<>(evidenceRows.stream()
            .map(item -> aiEvidence(item, materialId, allowed))
            .filter(Objects::nonNull).toList());
        if (currentOperation != null) {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("id", "current-operation:" + currentOperation.id());
            operation.put("sourceType", "CURRENT_OPERATION");
            operation.put("sourceId", currentOperation.id().toString());
            operation.put("sourceDate", currentOperation.movementDate().toString());
            operation.put("complete", true);
            operation.put("materialId", materialId);
            operation.put("accountId", null);
            operation.put("status", "VALID");
            operation.put("operationContext", Map.of("operationType", "RECEIPT",
                "purpose", currentOperation.purpose(), "warehouseId", currentOperation.warehouseId()));
            operation.put("facts", Map.of("quantity", currentOperation.quantity().toPlainString(),
                "unitCost", currentOperation.unitCost().toPlainString()));
            aiEvidence.add(operation);
        }
        evidenceIds = aiEvidence.stream().map(item -> (String) item.get("id")).toList();
        String evidenceFingerprint = fingerprint(resolution.evidenceFingerprint(), allowed, aiEvidence);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schemaVersion", 1);
        context.put("task", "MATERIAL_STOCK_ACCOUNT_RECOMMENDATION");
        context.put("scope", Map.of("tenantId", TenantContext.currentTenant(),
            "companyId", CompanyContext.currentCompany()));
        Map<String, Object> materialContext = new LinkedHashMap<>();
        materialContext.put("id", materialId);
        materialContext.put("version", material.getVersion());
        materialContext.put("code", material.getCode());
        materialContext.put("name", material.getName());
        materialContext.put("type", material.getMaterialType());
        materialContext.put("subtype", material.getMaterialSubtype());
        materialContext.put("mxikCode", material.getMxikCode());
        materialContext.put("configuredAccountId", material.getManualReceiptStockAccountId());
        context.put("material", materialContext);
        Map<String, Object> resolutionContext = new LinkedHashMap<>();
        resolutionContext.put("status", resolution.status());
        resolutionContext.put("snapshotDate", resolution.snapshotDate());
        resolutionContext.put("evidenceFingerprint", evidenceFingerprint);
        resolutionContext.put("allowedAccounts", allowed);
        List<UUID> snapshotImportIds = jdbc.query("""
            select id from warehouse_account_imports
            where tenant_id=:tenant and company_id=:company and status='COMPLETED'
              and snapshot_date=:snapshotDate order by id limit 100
            """, scope.addValue("snapshotDate", resolution.snapshotDate()),
            (row, index) -> row.getObject("id", UUID.class));
        resolutionContext.put("snapshotImportIds", snapshotImportIds);
        context.put("resolution", resolutionContext);
        context.put("evidence", aiEvidence);
        return new Request(materialId, draftId, requestedBy, material.getVersion(), resolution.evidenceFingerprint(),
            evidenceFingerprint,
            allowed.stream().map(MaterialStockAccountResolutionService.Account::id).toList(),
            evidenceIds, mapper.valueToTree(context));
    }

    public JsonNode validate(UUID jobId, Request request, JsonNode result) {
        if (result == null || result.path("schemaVersion").asInt(-1) != 1
            || !request.materialId().toString().equals(result.path("materialId").asText())
            || request.materialVersion() == null
            || request.materialVersion().longValue() != result.path("materialVersion").asLong(-1)
            || !request.evidenceFingerprint().equals(result.path("evidenceFingerprint").asText())
            || !jobId.toString().equals(result.path("jobId").asText()))
            throw new BadRequestException("AI account response does not match its material context");
        String status = result.path("status").asText();
        if ("RECOMMENDED".equals(status)) {
            if (!List.of("HIGH", "MEDIUM", "LOW").contains(result.path("confidence").asText()))
                throw new BadRequestException("AI account confidence is invalid");
            JsonNode recommended = result.path("recommendedAccount");
            UUID id;
            try {
                id = UUID.fromString(recommended.path("id").asText());
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("AI account ID is invalid");
            }
            if (!request.allowedAccountIds().contains(id))
                throw new BadRequestException("AI account is outside the eligible candidates");
            var account = accounts.requireEligibleOwnedAccount(id);
            if (!account.getAccountCode().trim().equals(recommended.path("code").asText()))
                throw new BadRequestException("AI account code does not match its ID");
        } else if (!"NO_RELIABLE_SUGGESTION".equals(status)
            || !result.path("recommendedAccount").isNull()
            || !result.path("confidence").isNull()) {
            throw new BadRequestException("AI account response status is invalid");
        }
        if (result.path("reason").asText().isBlank() || result.path("reason").asText().length() > 1000)
            throw new BadRequestException("AI account explanation is invalid");
        JsonNode references = result.path("evidenceRefs");
        if (!references.isArray() || references.size() > MAX_ACCOUNTS
            || "RECOMMENDED".equals(status) && references.isEmpty())
            throw new BadRequestException("AI account evidence references are invalid");
        for (JsonNode reference : references) {
            if (!request.evidenceIds().contains(reference.asText()))
                throw new BadRequestException("AI account evidence reference was not supplied");
        }
        return result;
    }

    @Transactional(readOnly = true)
    public void verifyApplied(UUID jobId, UUID materialId, UUID accountId,
                              MaterialStockAccountResolutionService.Resolution current) {
        verifyApplied(jobId, materialId, accountId, current, null);
    }

    @Transactional(readOnly = true)
    public void verifyApplied(UUID jobId, UUID materialId, UUID accountId,
                              MaterialStockAccountResolutionService.Resolution current, UUID expectedDraftId) {
        AiAnalysisJob job = jobs.findScoped(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new BadRequestException("AI account recommendation job not found"));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) throw new BadRequestException("User context is required for AI account application");
        String user = authentication.getName();
        if (job.getAnalysisType() != AiAnalysisType.MATERIAL_STOCK_ACCOUNT_RECOMMENDATION
            || job.getStatus() != AiAnalysisJobStatus.COMPLETED
            || !"MATERIAL".equals(job.getEntityType()) || !materialId.equals(job.getEntityId())
            || !user.equals(job.getRequestedBy()))
            throw new BadRequestException("AI account recommendation does not match this material and user");
        try {
            Request request = mapper.readValue(job.getRequestPayload(), Request.class);
            JsonNode result = mapper.readTree(job.getResultPayload());
            if (expectedDraftId != null && !expectedDraftId.equals(request.draftId()))
                throw new BadRequestException("AI account recommendation belongs to another receipt draft");
            if (!Objects.equals(request.materialVersion(), current.materialVersion())
                || !request.resolutionFingerprint().equals(current.evidenceFingerprint())
                || !request.evidenceFingerprint().equals(snapshot(materialId, user, request.draftId()).evidenceFingerprint()))
                throw new ConflictException("AI account recommendation is stale; review again");
            validate(jobId, request, result);
            if (!"RECOMMENDED".equals(result.path("status").asText())
                || !accountId.toString().equals(result.path("recommendedAccount").path("id").asText()))
                throw new BadRequestException("AI account recommendation differs from the selected account");
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI account recommendation payload is invalid");
        }
    }

    private String fingerprint(String resolutionFingerprint,
                               List<MaterialStockAccountResolutionService.Account> allowed,
                               List<Map<String, Object>> evidence) {
        try {
            String data = resolutionFingerprint + mapper.writeValueAsString(allowed)
                + mapper.writeValueAsString(evidence);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Account evidence fingerprint could not be created", exception);
        }
    }

    Map<String, Object> aiEvidence(Map<String, Object> source, UUID materialId,
        List<MaterialStockAccountResolutionService.Account> allowed) {
        String date = evidenceDate(source.getOrDefault("sourceDate", source.getOrDefault("date", source.get("balanceAt"))));
        if (date == null) return null;
        String id = (String) source.get("id");
        String type = switch ((String) source.get("sourceType")) {
            case "OPERATIONAL_MOVEMENT" -> "MM_MOVEMENT";
            case "PROCUREMENT_SPECIFICATION" -> "SPECIFICATION";
            case "PURCHASE_ORDER" -> "ORDER";
            default -> (String) source.get("sourceType");
        };
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("id", id);
        evidence.put("sourceType", type);
        evidence.put("sourceId", id.substring(id.indexOf(':') + 1));
        evidence.put("sourceDate", date);
        evidence.put("complete", true);
        evidence.put("materialId", materialId);
        evidence.put("status", "VALID");
        Object accountId = source.get("accountId");
        if (accountId == null && source.get("accountCode") != null) {
            String code = source.get("accountCode").toString().trim();
            accountId = allowed.stream().filter(account -> code.equals(account.code()))
                .map(MaterialStockAccountResolutionService.Account::id).findFirst().orElse(null);
        }
        if (accountId != null) evidence.put("accountId", accountId);
        if (source.get("operationContext") != null)
            evidence.put("operationContext", source.get("operationContext"));
        Map<String, Object> facts = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!List.of("id", "sourceType", "sourceDate", "date", "balanceAt", "accountId",
                "operationContext").contains(key)
                && facts.size() < 20)
                facts.put(key, value == null || value instanceof Boolean ? value : value.toString());
        });
        evidence.put("facts", facts);
        return evidence;
    }

    private String evidenceDate(Object value) {
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof LocalDateTime date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Timestamp date) return date.toLocalDateTime().toLocalDate().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        return value == null ? null : value.toString();
    }
}
