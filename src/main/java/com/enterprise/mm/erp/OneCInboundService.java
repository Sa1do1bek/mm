package com.enterprise.mm.erp;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.service.StockMovementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OneCInboundService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StockMovementService stock;
    public OneCInboundService(JdbcTemplate jdbc, ObjectMapper mapper, StockMovementService stock) {
        this.jdbc=jdbc; this.mapper=mapper; this.stock=stock;
    }

    @Transactional
    public Map<String,Object> receive(JsonNode event, String idempotencyKey) {
        String tenant=TenantContext.currentTenant(); UUID company=CompanyContext.currentCompany();
        UUID eventId=uuid(event.path("eventId")); UUID document=uuid(event.path("documentId"));
        String kind=event.path("documentKind").asText();
        if (!event.path("protocolVersion").isIntegralNumber() || event.path("protocolVersion").asInt()!=1
            || !event.path("posted").isBoolean() || !event.path("posted").asBoolean()
            || !eventId.toString().equals(idempotencyKey) || !tenant.equals(event.path("tenantId").asText())
            || !company.toString().equals(event.path("companyId").asText())
            || !("RECEIPT".equals(kind) || "ISSUE".equals(kind) || "TRANSFER".equals(kind))) throw new BadRequestException("Invalid native 1C event identity, state or scope");
        String hash=hash(event);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(cast(? as text) || ':' || cast(? as text) || ':1c:' || cast(? as text),0))",Object.class,tenant,company,document);
        List<Map<String,Object>> previous=jdbc.queryForList("select event_id,payload_hash from one_c_api_inbox where tenant_id=? and company_id=? and document_kind=? and document_id=?",tenant,company,kind,document);
        if (!previous.isEmpty()) {
            if (!eventId.equals(previous.getFirst().get("event_id")) || !hash.equals(previous.getFirst().get("payload_hash")))
                throw new ConflictException("Native document already imported with different identity or data");
            return Map.of("eventId",eventId,"status","APPLIED","replayed",true);
        }
        LocalDate date;
        try { date=LocalDate.parse(event.path("postingDate").asText()); }
        catch (RuntimeException ex) { throw new BadRequestException("Invalid native posting date"); }
        JsonNode lines=event.path("lines");
        if (!lines.isArray() || lines.isEmpty()) throw new BadRequestException("Native document lines are required");
        int index=0;
        for (JsonNode line:lines) {
            UUID material=uuid(line.path("materialId")), warehouse=uuid(line.path("warehouseId"));
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from materials where id=? and tenant_id=? and company_id=? and is_delete=false)",Boolean.class,material,tenant,company))
                || !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from warehouses where id=? and tenant_id=? and company_id=? and is_delete=false)",Boolean.class,warehouse,tenant,company)))
                throw new BadRequestException("Native material or warehouse is outside company scope");
            BigDecimal quantity=decimal(line.path("quantity")), amount=decimal(line.path("amount"));
            if (quantity.signum()<=0 || amount.signum()<0) throw new BadRequestException("Invalid native quantity or value");
            StockMovement movement=new StockMovement(); movement.setTenantId(tenant); movement.setCompanyId(company);
            movement.setCode("ONEC-"+eventId+"-"+(++index)); movement.setMaterialId(material); movement.setWarehouseId(warehouse);
            movement.setMovementType(MovementType.valueOf(kind)); movement.setMovementDate(date); movement.setQuantity(quantity);
            if ("TRANSFER".equals(kind)) {
                UUID destination=uuid(event.path("targetWarehouseId"));
                if (destination.equals(warehouse) || !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from warehouses where id=? and tenant_id=? and company_id=? and is_delete=false)",Boolean.class,destination,tenant,company)))
                    throw new BadRequestException("Native transfer destination is outside company scope");
                movement.setTargetWarehouseId(destination);
            }
            movement.setUnitCost(amount.divide(quantity,6,RoundingMode.HALF_UP)); movement.setTotalCost(amount);
            movement.setReferenceType("ONE_C_API"); movement.setReferenceId(document);
            movement.setDescription("1C API: "+document);
            var posted=stock.importOneCApiMovement(movement);
            if (posted.getTotalCost().compareTo(amount)!=0) throw new ConflictException("Native 1C value does not match MM valuation; the import was rolled back");
        }
        jdbc.update("insert into one_c_api_inbox(tenant_id,company_id,event_id,document_id,document_kind,payload_hash) values (?,?,?,?,?,?)",tenant,company,eventId,document,kind,hash);
        return Map.of("eventId",eventId,"status","APPLIED","replayed",false);
    }

    private UUID uuid(JsonNode value) {
        try { return UUID.fromString(value.asText()); }
        catch (RuntimeException ex) { throw new BadRequestException("Invalid native UUID"); }
    }
    private BigDecimal decimal(JsonNode value) {
        try { return new BigDecimal(value.asText()); }
        catch (RuntimeException ex) { throw new BadRequestException("Invalid native decimal"); }
    }
    private String hash(JsonNode event) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("Cannot fingerprint native event",ex); }
    }
}
