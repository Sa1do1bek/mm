package com.enterprise.mm.erp;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ErpExchangeService implements ErpExchangeListener {
    private static final Logger log = LoggerFactory.getLogger(ErpExchangeService.class);
    private static final UUID UNSPECIFIED_WAREHOUSE = new UUID(0L, 0L);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaterialRepository materials;
    private final MaterialGroupRepository groups;
    private final ErpExchangeProcessor processor = new ErpExchangeProcessor();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ErpReconciliationService reconciliationService;
    @org.springframework.beans.factory.annotation.Autowired
    private OneCApiAccess nativeApi;
    @org.springframework.beans.factory.annotation.Autowired
    private OneCTransport transport;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.enterprise.mm.service.RoleDataScopeService confirmationAccess;

    @org.springframework.beans.factory.annotation.Autowired
    public ErpExchangeService(JdbcTemplate jdbc, ObjectMapper mapper, MaterialRepository materials, MaterialGroupRepository groups) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.materials = materials;
        this.groups = groups;
    }

    ErpExchangeService(JdbcTemplate jdbc, ObjectMapper mapper, MaterialRepository materials, MaterialGroupRepository groups,
                       com.enterprise.mm.service.RoleDataScopeService access) {
        this(jdbc, mapper, materials, groups); this.confirmationAccess = access;
    }

    public record Line(UUID materialId, UUID warehouseId, BigDecimal quantity, BigDecimal amount) {
    }

    public record ConnectionSettings(boolean enabled, String baseUrl, String username, boolean passwordSet, String scheduleCron, String transportMode) {
    }

    private String transportMode(){return transport==null?"MM_INITIATED":transport.mode();}

    public record PostingSettings(String arrivalMode, String accountCode, String subcontoCode, String warehouseCode) {
    }

    @Transactional(readOnly = true)
    public ConnectionSettings connection() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select enabled, base_url, username, password_value, schedule_cron from erp_exchange_connection where tenant_id = ? and company_id = ?",
            tenant(), company());
        if (rows.isEmpty()) {
            return new ConnectionSettings(false, null, null, false, null, transportMode());
        }
        Map<String, Object> row = rows.get(0);
        String password = text(row.get("password_value"));
        return new ConnectionSettings(truth(row.get("enabled")), text(row.get("base_url")), text(row.get("username")),
            password != null && !password.isBlank(), text(row.get("schedule_cron")), transportMode());
    }

    @Transactional
    public ConnectionSettings saveConnection(boolean enabled, String baseUrl, String username, String password, String scheduleCron) {
        String tenant = tenant();
        UUID company = company();
        if (enabled && nativeApi != null) {
            if(transport!=null&&transport.pullEnabled())nativeApi.requireCurrentScope();
            else nativeApi.authorize(java.net.http.HttpRequest.newBuilder(), baseUrl, tenant, company);
        }
        String keptPassword = password;
        String keptUsername = username;
        String keptSchedule = scheduleCron;
        if (keptPassword == null || keptPassword.isBlank() || keptUsername == null || keptSchedule == null) {
            List<Map<String,Object>> previous = jdbc.queryForList("select username,password_value,schedule_cron from erp_exchange_connection where tenant_id=? and company_id=?", tenant, company);
            if (!previous.isEmpty()) {
                Map<String,Object> prior = previous.get(0);
                if (keptPassword == null || keptPassword.isBlank()) keptPassword = text(prior.get("password_value"));
                if (keptUsername == null) keptUsername = text(prior.get("username"));
                if (keptSchedule == null) keptSchedule = text(prior.get("schedule_cron"));
            }
        }
        jdbc.update("delete from erp_exchange_connection where tenant_id = ? and company_id = ?", tenant, company);
        jdbc.update("""
            insert into erp_exchange_connection
            (id, tenant_id, company_id, enabled, base_url, username, password_value, schedule_cron, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
            """, UUID.randomUUID(), tenant, company, enabled, trim(baseUrl), trim(keptUsername), keptPassword, trim(keptSchedule));
        return connection();
    }

    @Transactional(readOnly = true)
    public PostingSettings postingConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select arrival_mode, account_code, subconto_code, warehouse_code from erp_exchange_posting_config where tenant_id = ? and company_id = ?",
            tenant(), company());
        if (rows.isEmpty()) {
            return new PostingSettings(ErpDocumentKinds.HOLD_UNTIL_CONFIRMED, null, null, null);
        }
        Map<String, Object> row = rows.get(0);
        return new PostingSettings(text(row.get("arrival_mode")), text(row.get("account_code")),
            text(row.get("subconto_code")), text(row.get("warehouse_code")));
    }

    @Transactional
    public PostingSettings savePostingConfig(String arrivalMode, String accountCode, String subcontoCode, String warehouseCode) {
        String mode = ErpDocumentKinds.requireMode(arrivalMode);
        String tenant = tenant();
        UUID company = company();
        jdbc.update("delete from erp_exchange_posting_config where tenant_id = ? and company_id = ?", tenant, company);
        jdbc.update("""
            insert into erp_exchange_posting_config
            (id, tenant_id, company_id, arrival_mode, account_code, subconto_code, warehouse_code, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, current_timestamp)
            """, UUID.randomUUID(), tenant, company, mode, trim(accountCode), trim(subcontoCode), trim(warehouseCode));
        return postingConfig();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> envelopes() {
        return jdbc.query("""
            select id, document_kind, document_id, document_version, delivery_state, rejection_reason, created_at,
                   operation, posting_date, remote_document_ref, remote_posted, attempt_count, confirm_requested
            from erp_exchange_envelope
            where tenant_id = ? and company_id = ?
            order by created_at desc
            limit 50
            """, (rs, row) -> envelopeRow(rs), tenant(), company());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void acceptFromPosting(PostingCommand command) {
        String kind = ErpDocumentKinds.fromContext(command.contextKey());
        if (kind == null || command.resourceId() == null) {
            return;
        }
        persist(kind, command.resourceId(), command.expectedVersion(), command.postingDate(), lines(command.payload()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acceptWarehouseDocument(String kind, UUID documentId, long version, LocalDate postingDate,
                                        String operation, UUID sourceWarehouseId, UUID destinationWarehouseId, List<Line> lines) {
        String tenant = tenant(); UUID company = company();
        if (!enabled(tenant, company)) return;
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(cast(? as text) || ':' || cast(? as text), 0))", Object.class, tenant, company);
        ErpDocumentKinds.requireKind(kind);
        if (documentId == null || postingDate == null || lines == null || lines.isEmpty())
            throw new BadRequestException("Complete ERP event evidence is required");
        UUID id = UUID.randomUUID();
        ObjectNode event = mapper.createObjectNode();
        event.put("protocolVersion", 1); event.put("eventId", id.toString());
        event.put("tenantId", tenant); event.put("companyId", company.toString());
        event.put("documentKind", kind); event.put("documentId", documentId.toString());
        event.put("documentVersion", version); event.put("operation", operation);
        event.put("postingDate", postingDate.toString()); event.put("arrivalMode", mode(tenant, company));
        if (sourceWarehouseId != null) event.put("sourceWarehouseId", sourceWarehouseId.toString());
        if (destinationWarehouseId != null) event.put("destinationWarehouseId", destinationWarehouseId.toString());
        ArrayNode items = event.putArray("lines");
        for (Line line : lines) {
            if (line.materialId() == null || line.warehouseId() == null || line.quantity() == null
                || line.quantity().signum() <= 0 || line.amount() == null || line.amount().signum() < 0)
                throw new BadRequestException("ERP line requires material, warehouse, positive quantity and actual nonnegative value");
            ObjectNode item = items.addObject(); item.put("materialId", line.materialId().toString());
            item.put("warehouseId", line.warehouseId().toString()); item.put("quantity", line.quantity().toPlainString());
            item.put("amount", line.amount().toPlainString());
        }
        int inserted = jdbc.update("""
            insert into erp_exchange_envelope (id,tenant_id,company_id,document_kind,document_id,document_version,payload,delivery_state,operation,posting_date,created_at)
            values (?,?,?,?,?,?,?,'PENDING',?,?,clock_timestamp()) on conflict (tenant_id,company_id,document_kind,document_id,document_version,operation)
            where operation is not null do nothing
            """, id, tenant, company, kind, documentId, version, event.toString(), operation, Date.valueOf(postingDate));
        if (inserted == 0) throw new ConflictException("ERP event identity already exists; replay must use warehouse idempotency");
    }

    /** Captures only verified posted manual stock facts, without reapplying stock movements. */
    @Transactional
    public Map<String, Object> captureStockFact(UUID documentId) {
        String tenant = tenant(); UUID company = company();
        if (!enabled(tenant, company)) throw new ConflictException("ERP exchange is disabled");
        List<Map<String,Object>> docs = jdbc.queryForList("""
            select id, status, version, movement_type, movement_date, warehouse_id, posted_at, reference_type, is_delete
            from stock_movement_documents where id=? and tenant_id=? and company_id=? for update
            """, documentId, tenant, company);
        if (docs.size()!=1) throw new NotFoundException("Stock fact document not found");
        Map<String,Object> doc=docs.get(0); String kind=text(doc.get("movement_type"));
        if (!"POSTED".equals(text(doc.get("status"))) || doc.get("posted_at")==null || truth(doc.get("is_delete"))
            || doc.get("movement_date")==null || doc.get("warehouse_id")==null || doc.get("version")==null
            || !"MANUAL_WAREHOUSE_FACT".equals(text(doc.get("reference_type"))) || !("RECEIPT".equals(kind) || "ISSUE".equals(kind)))
            throw new ConflictException("Document is not a posted manual material stock fact");
        UUID warehouse=uuid(doc.get("warehouse_id")); LocalDate date=((Date)doc.get("movement_date")).toLocalDate();
        List<Map<String,Object>> movements=jdbc.queryForList("""
            select s.id,s.material_id,s.warehouse_id,s.movement_type,s.movement_date,s.quantity,s.total_cost,
                   s.status,s.posted_at,s.is_delete,s.reference_type,s.reference_id,m.material_type,m.is_delete as material_deleted,
                   (select coalesce(sum(a.on_hand_delta),0) from stock_movement_allocations a
                    where a.movement_id=s.id and a.tenant_id=s.tenant_id and a.company_id=s.company_id and a.material_id=s.material_id) as quantity_delta,
                   (select coalesce(sum(a.total_value_delta),0) from stock_movement_allocations a
                    where a.movement_id=s.id and a.tenant_id=s.tenant_id and a.company_id=s.company_id and a.material_id=s.material_id) as value_delta
            from stock_movements s left join materials m on m.id=s.material_id and m.tenant_id=s.tenant_id and m.company_id=s.company_id
            where s.document_id=? and s.tenant_id=? and s.company_id=? order by s.id
            """,documentId,tenant,company);
        if (movements.isEmpty()) throw new ConflictException("Posted stock fact has no movement evidence");
        List<Line> lines=new ArrayList<>(); int sign="RECEIPT".equals(kind) ? 1 : -1;
        for (Map<String,Object> movement : movements) {
            BigDecimal quantity=decimal(movement.get("quantity")), amount=decimal(movement.get("total_cost"));
            BigDecimal quantityDelta=decimal(movement.get("quantity_delta")), valueDelta=decimal(movement.get("value_delta"));
            if (!"POSTED".equals(text(movement.get("status"))) || movement.get("posted_at")==null || truth(movement.get("is_delete"))
                || !"TMB".equals(text(movement.get("material_type"))) || truth(movement.get("material_deleted"))
                || !"MANUAL_WAREHOUSE_FACT".equals(text(movement.get("reference_type"))) || !documentId.equals(uuid(movement.get("reference_id")))
                || !kind.equals(text(movement.get("movement_type"))) || !warehouse.equals(uuid(movement.get("warehouse_id")))
                || movement.get("movement_date")==null || !date.equals(((Date)movement.get("movement_date")).toLocalDate())
                || quantity.signum()<=0 || amount.signum()<0
                || quantityDelta.compareTo(quantity.multiply(BigDecimal.valueOf(sign)))!=0
                || valueDelta.compareTo(amount.multiply(BigDecimal.valueOf(sign)))!=0)
                throw new ConflictException("Stock fact movement and allocation evidence do not reconcile");
            lines.add(new Line(uuid(movement.get("material_id")),warehouse,quantity,amount));
        }
        List<Map<String,Object>> existing=jdbc.queryForList("""
            select id,payload,document_version from erp_exchange_envelope
            where tenant_id=? and company_id=? and document_id=? and document_kind=? and operation=?
            """,tenant,company,documentId,kind,kind);
        if (existing.size()>1) throw new ConflictException("Multiple ERP events exist for the same stock fact");
        if (existing.size()==1) {
            Map<String,Object> row=existing.get(0); validateExistingStockFact(row,documentId,kind,date,lines);
            return Map.of("envelopeId",uuid(row.get("id")),"created",false);
        }
        long version=((Number)doc.get("version")).longValue();
        acceptWarehouseDocument(kind,documentId,version,date,kind,"ISSUE".equals(kind) ? warehouse : null,
            "RECEIPT".equals(kind) ? warehouse : null,lines);
        UUID id=jdbc.queryForObject("""
            select id from erp_exchange_envelope where tenant_id=? and company_id=? and document_id=? and document_kind=? and operation=? and document_version=?
            """,UUID.class,tenant,company,documentId,kind,kind,version);
        return Map.of("envelopeId",id,"created",true);
    }

    private void validateExistingStockFact(Map<String,Object> row, UUID documentId, String kind, LocalDate date, List<Line> lines) {
        try {
            JsonNode event=mapper.readTree(text(row.get("payload"))); JsonNode items=event.path("lines");
            if (!uuid(row.get("id")).toString().equals(event.path("eventId").asText()) || !documentId.toString().equals(event.path("documentId").asText())
                || !kind.equals(event.path("operation").asText()) || !kind.equals(event.path("documentKind").asText())
                || !tenant().equals(event.path("tenantId").asText()) || !company().toString().equals(event.path("companyId").asText())
                || event.path("documentVersion").asLong(-1) != ((Number) row.get("document_version")).longValue()
                || !date.toString().equals(event.path("postingDate").asText())
                || !items.isArray() || items.size()!=lines.size()) throw new IllegalArgumentException();
            for (int i=0;i<lines.size();i++) {
                Line line=lines.get(i); JsonNode item=items.get(i);
                if (!line.materialId().toString().equals(item.path("materialId").asText()) || !line.warehouseId().toString().equals(item.path("warehouseId").asText())
                    || line.quantity().compareTo(new BigDecimal(item.path("quantity").asText()))!=0
                    || line.amount().compareTo(new BigDecimal(item.path("amount").asText()))!=0) throw new IllegalArgumentException();
            }
        } catch (Exception ex) { throw new ConflictException("Existing ERP event differs from stock fact evidence"); }
    }

    @Transactional
    public Map<String, Object> confirm(UUID envelopeId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select delivery_state, operation, confirm_requested, payload from erp_exchange_envelope
            where id=? and tenant_id=? and company_id=? for update
            """, envelopeId, tenant(), company());
        if (rows.isEmpty()) throw new NotFoundException("ERP event not found");
        Map<String,Object> row = rows.get(0); String state = text(row.get("delivery_state"));
        if (row.get("operation") != null) requireConfirmationAccess(mapper, confirmationAccess, text(row.get("payload")), text(row.get("operation")), envelopeId, tenant(), company());
        if ("POSTED".equals(state) && row.get("operation") != null)
            return Map.of("envelopeId", envelopeId, "status", "POSTED", "posted", true);
        boolean requested = truth(row.get("confirm_requested"));
        if (row.get("operation") == null || !("AWAITING_CONFIRMATION".equals(state) || "RETRY".equals(state) && requested))
            throw new ConflictException("Only an acknowledged 1C document awaiting confirmation can be confirmed");
        if (!requested) jdbc.update("update erp_exchange_envelope set confirm_requested=true, next_attempt_at=current_timestamp where id=?", envelopeId);
        return Map.of("envelopeId", envelopeId, "status", "QUEUED", "posted", false);
    }

    static void requireConfirmationAccess(ObjectMapper mapper, com.enterprise.mm.service.RoleDataScopeService access,
                                          String payload, String operation, UUID envelopeId, String tenant, UUID company) {
        if (access == null) throw new ConflictException("Warehouse access validation is unavailable");
        java.util.Set<UUID> warehouses = new java.util.LinkedHashSet<>();
        try {
            JsonNode event = mapper.readTree(payload); JsonNode lines=event.path("lines");
            if (!envelopeId.toString().equals(event.path("eventId").asText()) || !tenant.equals(event.path("tenantId").asText())
                || !company.toString().equals(event.path("companyId").asText()) || !operation.equals(event.path("operation").asText())
                || !lines.isArray() || lines.isEmpty())
                throw new IllegalArgumentException();
            for (JsonNode line : lines) warehouses.add(requiredWarehouse(line.path("warehouseId")));
            if (operation.startsWith("TRANSFER_") && (!event.has("sourceWarehouseId") || !event.has("destinationWarehouseId")))
                throw new IllegalArgumentException();
            for (String field : List.of("sourceWarehouseId", "destinationWarehouseId")) {
                if (event.has(field)) warehouses.add(requiredWarehouse(event.path(field)));
            }
        } catch (Exception ex) { throw new ConflictException("ERP event has invalid warehouse scope"); }
        for (UUID warehouse : warehouses) access.assertWarehouseAccess(warehouse);
    }

    private static UUID requiredWarehouse(JsonNode node) {
        if (!node.isTextual()) throw new IllegalArgumentException();
        UUID value=UUID.fromString(node.asText());
        if (!value.toString().equals(node.asText()) || value.equals(new UUID(0,0))) throw new IllegalArgumentException();
        return value;
    }

    public boolean captureEnabled() { return enabled(tenant(), company()); }

    public LocalDate movementDate(UUID movementId) {
        if (movementId == null) throw new BadRequestException("Receipt movement date is unavailable");
        List<Date> dates = jdbc.queryForList("select movement_date from stock_movements where id=? and tenant_id=? and company_id=? and posted_at is not null", Date.class, movementId, tenant(), company());
        if (dates.size() != 1 || dates.get(0) == null) throw new BadRequestException("Receipt movement date is unavailable");
        return dates.get(0).toLocalDate();
    }

    public BigDecimal movementAmount(UUID movementId) {
        if (movementId == null) throw new BadRequestException("Receipt movement value is unavailable");
        List<BigDecimal> amounts = jdbc.queryForList("select total_cost from stock_movements where id=? and tenant_id=? and company_id=? and posted_at is not null", BigDecimal.class, movementId, tenant(), company());
        if (amounts.size() != 1 || amounts.get(0) == null) throw new BadRequestException("Receipt movement value is unavailable");
        return amounts.get(0);
    }

    @Transactional
    public Map<String, Object> saveAbsoluteBalances(LocalDate date, List<Line> lines) {
        if (date == null) {
            throw new BadRequestException("Balance date is required");
        }
        String tenant = tenant();
        UUID company = company();
        int stored = 0;
        for (Line line : lines == null ? List.<Line>of() : lines) {
            if (line.materialId() == null) {
                continue;
            }
            UUID warehouseId = line.warehouseId() == null ? UNSPECIFIED_WAREHOUSE : line.warehouseId();
            replaceBalance(tenant, company, date, line.materialId(), warehouseId, nvl(line.quantity()), nvl(line.amount()), true);
            stored++;
        }
        return Map.of("date", date.toString(), "stored", stored, "stockChanged", false);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> reconciliation(LocalDate date) {
        if (reconciliationService == null) throw new BadRequestException("ERP reconciliation service is unavailable");
        return reconciliationService.reconcile(tenant(), company(), date == null ? LocalDate.now() : date);
    }

    @Transactional
    public Map<String, Object> registerUnitMark(UUID materialId, UUID goodsReceiptId, String markCode) {
        if (materialId == null || markCode == null || markCode.isBlank()) {
            throw new BadRequestException("Material and Asl Belgisi mark are required");
        }
        String code = markCode.trim();
        if (code.length() > 160) {
            throw new BadRequestException("Asl Belgisi mark is too long");
        }
        materials.findByIdAndIsDeletedFalse(materialId)
            .orElseThrow(() -> new NotFoundException("Material not found"));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                insert into unit_marks (id, tenant_id, company_id, material_id, goods_receipt_id, mark_code, is_delete, created_at)
                values (?, ?, ?, ?, ?, ?, false, current_timestamp)
                """, id, tenant(), company(), materialId, goodsReceiptId, code);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("Asl Belgisi mark already exists");
        }
        return Map.of("id", id, "markCode", code, "materialId", materialId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveScan(String code, String operation) {
        ErpDocumentKinds.requireKind(operation);
        String value = code == null ? "" : code.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("code", value);
        if (value.isEmpty()) {
            result.put("matched", false);
            return result;
        }
        String tenant = tenant();
        Material material = materials.findFirstByTenantIdAndBarcodeAndIsDeletedFalse(tenant, value).orElse(null);
        String source = "BARCODE";
        if (material == null) {
            material = materials.findFirstByTenantIdAndQrCodeAndIsDeletedFalse(tenant, value).orElse(null);
            source = "QR";
        }
        if (material == null) {
            material = materials.findFirstByTenantIdAndRfidTagAndIsDeletedFalse(tenant, value).orElse(null);
            source = "RFID";
        }
        UUID unitMarkId = null;
        if (material == null) {
            List<Map<String, Object>> marks = jdbc.queryForList("""
                select id, material_id from unit_marks
                where tenant_id = ? and company_id = ? and mark_code = ? and is_delete = false
                """, tenant, company(), value);
            if (!marks.isEmpty()) {
                unitMarkId = uuid(marks.get(0).get("id"));
                UUID materialId = uuid(marks.get(0).get("material_id"));
                material = materialId == null ? null : materials.findByIdAndIsDeletedFalse(materialId).orElse(null);
                source = "UNIT_MARK";
            }
        }
        result.put("matched", material != null);
        if (material != null) {
            result.put("source", source);
            result.put("materialId", material.getId());
            result.put("materialCode", material.getCode());
            result.put("materialName", material.getName());
            if (unitMarkId != null) {
                result.put("unitMarkId", unitMarkId);
            }
        }
        return result;
    }

    private void persist(String kind, UUID documentId, long version, LocalDate postingDate, List<Line> lines) {
        String tenant = tenant();
        UUID company = company();
        if (!enabled(tenant, company)) {
            processor.accept(false, ErpDocumentKinds.HOLD_UNTIL_CONFIRMED, false, 0);
            return;
        }
        String mode = mode(tenant, company);
        boolean exists = envelopeExists(tenant, company, kind, documentId, version);
        int existingPostings = exists ? postingCount(tenant, company, kind, documentId, version) : 0;
        ErpExchangeProcessor.Decision decision = processor.accept(true, mode, exists, existingPostings);
        if (!decision.created()) {
            return;
        }
        UUID envelopeId = UUID.randomUUID();
        String state = decision.posted() ? "POSTED" : "AWAITING_CONFIRMATION";
        String payload = payload(lines);
        try {
            jdbc.update("""
                insert into erp_exchange_envelope
                (id, tenant_id, company_id, document_kind, document_id, document_version, payload, delivery_state, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                """, envelopeId, tenant, company, kind, documentId, version, payload, state);
        } catch (DuplicateKeyException ex) {
            return;
        }
        UUID documentRowId = UUID.randomUUID();
        jdbc.update("""
            insert into erp_module_document
            (id, tenant_id, company_id, envelope_id, document_kind, document_id, document_version, posted, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
            """, documentRowId, tenant, company, envelopeId, kind, documentId, version, decision.posted());
        if (decision.posted()) {
            writePostings(tenant, company, documentRowId, envelopeId, postingDate, lines);
            jdbc.update("""
                insert into erp_module_confirmation (id, document_row_id, confirmed_by, confirmed_at, note)
                values (?, ?, ?, current_timestamp, ?)
                """, UUID.randomUUID(), documentRowId, "MM", "Document left MM already confirmed");
        }
    }

    private void writePostings(String tenant, UUID company, UUID documentRowId, UUID envelopeId, LocalDate postingDate, List<Line> lines) {
        List<Line> posted = lines == null || lines.isEmpty()
            ? List.of(new Line(null, null, BigDecimal.ZERO, BigDecimal.ZERO))
            : lines;
        PostingSettings settings = postingSettings(tenant, company);
        for (Line line : posted) {
            Mapping mapping = mapping(tenant, company, line.materialId(), settings);
            jdbc.update("""
                insert into erp_module_posting
                (id, tenant_id, company_id, document_row_id, envelope_id, account_code, subconto_code, warehouse_code,
                 material_id, warehouse_id, quantity, amount, posting_date, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                """, UUID.randomUUID(), tenant, company, documentRowId, envelopeId, mapping.account(), mapping.subconto(),
                mapping.warehouseCode(), line.materialId(), line.warehouseId(), nvl(line.quantity()), nvl(line.amount()),
                Date.valueOf(postingDate));
            if (line.materialId() != null) {
                addMovementBalance(tenant, company, postingDate, line.materialId(),
                    line.warehouseId() == null ? UNSPECIFIED_WAREHOUSE : line.warehouseId(),
                    nvl(line.quantity()), nvl(line.amount()));
            }
        }
    }

    private void addMovementBalance(String tenant, UUID company, LocalDate date, UUID materialId, UUID warehouseId,
                                    BigDecimal quantity, BigDecimal amount) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select id, quantity, amount, absolute from erp_module_balance
            where tenant_id = ? and company_id = ? and balance_date = ? and material_id = ? and warehouse_id = ?
            """, tenant, company, Date.valueOf(date), materialId, warehouseId);
        if (rows.isEmpty()) {
            jdbc.update("""
                insert into erp_module_balance
                (id, tenant_id, company_id, balance_date, material_id, warehouse_id, quantity, amount, absolute, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, false, current_timestamp)
                """, UUID.randomUUID(), tenant, company, Date.valueOf(date), materialId, warehouseId, quantity, amount);
            return;
        }
        if (truth(rows.get(0).get("absolute"))) {
            return;
        }
        jdbc.update("update erp_module_balance set quantity = quantity + ?, amount = amount + ? where id = ?",
            quantity, amount, uuid(rows.get(0).get("id")));
    }

    private void replaceBalance(String tenant, UUID company, LocalDate date, UUID materialId, UUID warehouseId,
                                BigDecimal quantity, BigDecimal amount, boolean absolute) {
        jdbc.update("""
            delete from erp_module_balance
            where tenant_id = ? and company_id = ? and balance_date = ? and material_id = ? and warehouse_id = ?
            """, tenant, company, Date.valueOf(date), materialId, warehouseId);
        jdbc.update("""
            insert into erp_module_balance
            (id, tenant_id, company_id, balance_date, material_id, warehouse_id, quantity, amount, absolute, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
            """, UUID.randomUUID(), tenant, company, Date.valueOf(date), materialId, warehouseId, quantity, amount, absolute);
    }

    private List<Map<String, Object>> differences(String tenant, UUID company, LocalDate date) {
        Map<String, Balance> erp = erpBalances(tenant, company, date);
        Map<String, Balance> mm = mmBalances(tenant, company);
        Map<String, Balance> keys = new LinkedHashMap<>();
        keys.putAll(mm);
        erp.forEach(keys::putIfAbsent);
        List<Map<String, Object>> differences = new ArrayList<>();
        for (String key : keys.keySet()) {
            Balance left = mm.getOrDefault(key, Balance.empty(keys.get(key)));
            Balance right = erp.getOrDefault(key, Balance.empty(left));
            if (left.quantity.compareTo(right.quantity) == 0 && left.amount.compareTo(right.amount) == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", left.materialId);
            row.put("warehouseId", UNSPECIFIED_WAREHOUSE.equals(left.warehouseId) ? null : left.warehouseId);
            row.put("mmQuantity", left.quantity);
            row.put("erpQuantity", right.quantity);
            row.put("mmAmount", left.amount);
            row.put("erpAmount", right.amount);
            differences.add(row);
        }
        return differences;
    }

    private Map<String, Balance> mmBalances(String tenant, UUID company) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select material_id, warehouse_id, sum(quantity) as quantity, sum(total_value) as amount
            from stock_balances
            where tenant_id = ? and company_id = ? and is_delete = false
            group by material_id, warehouse_id
            """, tenant, company);
        Map<String, Balance> balances = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID materialId = uuid(row.get("material_id"));
            if (materialId == null) {
                continue;
            }
            UUID warehouseId = uuid(row.get("warehouse_id"));
            if (warehouseId == null) {
                warehouseId = UNSPECIFIED_WAREHOUSE;
            }
            balances.put(key(materialId, warehouseId), new Balance(materialId, warehouseId, decimal(row.get("quantity")), decimal(row.get("amount"))));
        }
        return balances;
    }

    private Map<String, Balance> erpBalances(String tenant, UUID company, LocalDate date) {
        Integer absolute = jdbc.queryForObject("""
            select count(*) from erp_module_balance
            where tenant_id = ? and company_id = ? and balance_date = ? and absolute = true
            """, Integer.class, tenant, company, Date.valueOf(date));
        List<Map<String, Object>> rows = absolute != null && absolute > 0
            ? jdbc.queryForList("""
                select material_id, warehouse_id, quantity, amount from erp_module_balance
                where tenant_id = ? and company_id = ? and balance_date = ? and absolute = true
                """, tenant, company, Date.valueOf(date))
            : jdbc.queryForList("""
                select material_id, warehouse_id, sum(quantity) as quantity, sum(amount) as amount
                from erp_module_balance
                where tenant_id = ? and company_id = ? and balance_date <= ? and absolute = false
                group by material_id, warehouse_id
                """, tenant, company, Date.valueOf(date));
        Map<String, Balance> balances = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID materialId = uuid(row.get("material_id"));
            UUID warehouseId = uuid(row.get("warehouse_id"));
            if (materialId == null || warehouseId == null) {
                continue;
            }
            balances.put(key(materialId, warehouseId), new Balance(materialId, warehouseId, decimal(row.get("quantity")), decimal(row.get("amount"))));
        }
        return balances;
    }

    private boolean enabled(String tenant, UUID company) {
        Boolean enabled = jdbc.query(
            "select enabled from erp_exchange_connection where tenant_id = ? and company_id = ?",
            rs -> rs.next() && rs.getBoolean(1), tenant, company);
        return Boolean.TRUE.equals(enabled);
    }

    private String mode(String tenant, UUID company) {
        String mode = jdbc.query(
            "select arrival_mode from erp_exchange_posting_config where tenant_id = ? and company_id = ?",
            rs -> rs.next() ? rs.getString(1) : null, tenant, company);
        return ErpDocumentKinds.POST_WHEN_CONFIRMED.equals(mode) ? mode : ErpDocumentKinds.HOLD_UNTIL_CONFIRMED;
    }

    private boolean envelopeExists(String tenant, UUID company, String kind, UUID documentId, long version) {
        Integer count = jdbc.queryForObject("""
            select count(*) from erp_exchange_envelope
            where tenant_id = ? and company_id = ? and document_kind = ? and document_id = ? and document_version = ?
            """, Integer.class, tenant, company, kind, documentId, version);
        return count != null && count > 0;
    }

    private int postingCount(String tenant, UUID company, String kind, UUID documentId, long version) {
        Integer count = jdbc.queryForObject("""
            select count(*) from erp_module_posting p
            join erp_exchange_envelope e on e.id = p.envelope_id
            where e.tenant_id = ? and e.company_id = ? and e.document_kind = ? and e.document_id = ? and e.document_version = ?
            """, Integer.class, tenant, company, kind, documentId, version);
        return count == null ? 0 : count;
    }

    private int postingCount(UUID envelopeId) {
        Integer count = jdbc.queryForObject("select count(*) from erp_module_posting where envelope_id = ?", Integer.class, envelopeId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> envelope(String tenant, UUID company, UUID envelopeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select e.id, e.payload, e.created_at, d.id as document_row_id
            from erp_exchange_envelope e
            left join erp_module_document d on d.envelope_id = e.id
            where e.id = ? and e.tenant_id = ? and e.company_id = ?
            """, envelopeId, tenant, company);
        if (rows.isEmpty()) {
            throw new NotFoundException("ERP envelope not found");
        }
        return rows.get(0);
    }

    private PostingSettings postingSettings(String tenant, UUID company) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select account_code, subconto_code, warehouse_code from erp_exchange_posting_config where tenant_id = ? and company_id = ?",
            tenant, company);
        if (rows.isEmpty()) {
            return new PostingSettings(ErpDocumentKinds.HOLD_UNTIL_CONFIRMED, null, null, null);
        }
        Map<String, Object> row = rows.get(0);
        return new PostingSettings(ErpDocumentKinds.HOLD_UNTIL_CONFIRMED, text(row.get("account_code")),
            text(row.get("subconto_code")), text(row.get("warehouse_code")));
    }

    private Mapping mapping(String tenant, UUID company, UUID materialId, PostingSettings settings) {
        if (materialId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                select account_code, sub_account_code, warehouse_accounting_code
                from reference_mappings
                where tenant_id = ? and company_id = ? and reference_id = ? and external_system = 'ONE_C' and is_delete = false
                order by priority desc
                limit 1
                """, tenant, company, materialId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                return new Mapping(first(text(row.get("account_code")), settings.accountCode()),
                    first(text(row.get("sub_account_code")), settings.subcontoCode()),
                    first(text(row.get("warehouse_accounting_code")), settings.warehouseCode()));
            }
        }
        return new Mapping(settings.accountCode(), settings.subcontoCode(), settings.warehouseCode());
    }

    private String payload(List<Line> lines) {
        ArrayNode array = mapper.createArrayNode();
        for (Line line : lines) {
            ObjectNode node = mapper.createObjectNode();
            if (line.materialId() != null) {
                node.put("materialId", line.materialId().toString());
            }
            if (line.warehouseId() != null) {
                node.put("warehouseId", line.warehouseId().toString());
            }
            node.put("quantity", nvl(line.quantity()));
            node.put("amount", nvl(line.amount()));
            if (line.materialId() != null) {
                materials.findByIdAndIsDeletedFalse(line.materialId()).ifPresent(material -> enrich(node, material));
                List<String> marks = jdbc.queryForList("""
                    select mark_code from unit_marks
                    where tenant_id = ? and company_id = ? and material_id = ? and is_delete = false
                    order by created_at
                    limit 50
                    """, String.class, tenant(), company(), line.materialId());
                if (!marks.isEmpty()) {
                    ArrayNode markNodes = node.putArray("unitMarks");
                    marks.forEach(markNodes::add);
                }
            }
            array.add(node);
        }
        return array.toString();
    }

    private void enrich(ObjectNode node, Material material) {
        node.put("analytics1", material.getAnalytics1());
        node.put("analytics2", material.getAnalytics2());
        node.put("analytics3", material.getAnalytics3());
        node.put("barcode", material.getBarcode());
        node.put("qrCode", material.getQrCode());
        node.put("rfidTag", material.getRfidTag());
        if (material.getMaterialGroupId() != null) {
            groups.findByIdAndIsDeletedFalse(material.getMaterialGroupId()).ifPresent(group -> enrichGroup(node, group));
        }
    }

    private void enrichGroup(ObjectNode node, MaterialGroup group) {
        node.put("additionalCategory", group.getAdditionalCategory());
        node.put("lowerCategory", group.getLowerCategory());
    }

    private List<Line> lines(JsonNode payload) {
        if (payload != null && payload.has("lines") && payload.get("lines").isArray()) {
            List<Line> lines = new ArrayList<>();
            payload.get("lines").forEach(node -> lines.add(line(node)));
            return lines;
        }
        return List.of(line(payload));
    }

    private List<Line> readLines(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(payload);
            if (!node.isArray()) {
                return List.of();
            }
            List<Line> lines = new ArrayList<>();
            node.forEach(item -> lines.add(line(item)));
            return lines;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Line line(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Line(null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new Line(uuidText(node, "materialId"), uuidText(node, "warehouseId"),
            decimal(node, "quantity", "qty"), decimal(node, "amount"));
    }

    private Map<String, Object> envelopeRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id"));
        row.put("documentKind", rs.getString("document_kind"));
        row.put("documentId", rs.getObject("document_id"));
        row.put("documentVersion", rs.getLong("document_version"));
        row.put("deliveryState", rs.getString("delivery_state"));
        row.put("rejectionReason", rs.getString("rejection_reason"));
        row.put("lastError", rs.getString("rejection_reason"));
        row.put("operation", rs.getString("operation"));
        row.put("postingDate", rs.getDate("posting_date"));
        row.put("remoteDocumentRef", rs.getObject("remote_document_ref"));
        row.put("remotePosted", rs.getObject("remote_posted"));
        row.put("attemptCount", rs.getInt("attempt_count"));
        row.put("confirmRequested", rs.getBoolean("confirm_requested"));
        row.put("createdAt", rs.getTimestamp("created_at"));
        return row;
    }

    private static UUID uuidText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                try {
                    return new BigDecimal(value.asText());
                } catch (NumberFormatException ex) {
                    return BigDecimal.ZERO;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private static String key(UUID materialId, UUID warehouseId) {
        return materialId + ":" + warehouseId;
    }

    private static String tenant() {
        return TenantContext.currentTenant();
    }

    private static UUID company() {
        return CompanyContext.currentCompany();
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "erp";
        }
        return authentication.getName();
    }

    private static boolean truth(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(value.toString());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID id) {
            return id;
        }
        return UUID.fromString(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal amount) {
            return amount;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Mapping(String account, String subconto, String warehouseCode) {
    }

    private record Balance(UUID materialId, UUID warehouseId, BigDecimal quantity, BigDecimal amount) {
        private static Balance empty(Balance source) {
            return new Balance(source.materialId, source.warehouseId, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
