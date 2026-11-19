package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.StockMovementDocumentService;
import com.enterprise.mm.service.StockUomPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KimyosanoatIssueService {
    private static final String SYSTEM = "KIMYOSANOAT";
    private static final String AREA = "MANAGEMENT";
    private static final List<String> SNAPSHOT_FIELDS = List.of("id", "fromWarehouseId", "fromWarehouseName",
        "productId", "productNCode", "productName", "lotProductName", "mxikCode", "lotTransactionNumber", "sellerName", "buyerName", "buyerInn", "description",
        "lastStatus", "confirmedStatuses", "administrativeClosure", "reentryAllowed", "cylinderHandoverId", "transferType",
        "quantityModelVersion", "measureUnit", "warehouseQuantity", "confirmedNettoKg", "acceptedQuantity", "totalQuantity",
        "documentBagCount", "documentBagWeightGram", "validFrom", "validTo", "createdAt");
    private static final Set<String> FINGERPRINT_FIELDS = Set.of("id", "fromWarehouseId", "productId", "productNCode", "lotTransactionNumber",
        "buyerName", "buyerInn", "administrativeClosure", "reentryAllowed", "cylinderHandoverId", "transferType",
        "quantityModelVersion", "measureUnit", "warehouseQuantity", "confirmedNettoKg", "acceptedQuantity");
    private final KimyosanoatClient client;
    private final RoleDataScopeService access;
    private final ReferenceMappingRepository mappings;
    private final WarehouseRepository warehouses;
    private final MaterialRepository materials;
    private final StockBalanceRepository balances;
    private final StockMovementDocumentService movements;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public KimyosanoatIssueService(KimyosanoatClient client, RoleDataScopeService access, ReferenceMappingRepository mappings,
            WarehouseRepository warehouses, MaterialRepository materials, StockBalanceRepository balances,
            StockMovementDocumentService movements, JdbcTemplate jdbc, ObjectMapper json) {
        this.client = client; this.access = access; this.mappings = mappings; this.warehouses = warehouses;
        this.materials = materials; this.balances = balances; this.movements = movements; this.jdbc = jdbc; this.json = json;
    }

    public record Document(String sourceId, String fingerprint, JsonNode details, UUID materialId,
            String quantityKg, String quantityStock, String stockUnit, String state, UUID movementDocumentId,
            String matchFingerprint, UUID eventId, String reviewReason) { }
    public record Documents(List<Document> content, int page, int size, long totalElements, int totalPages) { }
    public record ApproveRequest(UUID warehouseId, UUID stockBalanceId, String fingerprint, UUID materialId, UUID eventId) {
        public ApproveRequest(UUID warehouseId, UUID stockBalanceId, String fingerprint) { this(warehouseId, stockBalanceId, fingerprint, null, null); }
        public ApproveRequest(UUID warehouseId, UUID stockBalanceId, String fingerprint, UUID materialId) {
            this(warehouseId, stockBalanceId, fingerprint, materialId, null);
        }
    }
    public record MaterialCandidate(UUID materialId, String name, String code, String mxikCode, String match,
            String quantityStock, String stockUnit, String fingerprint) { }
    public record Approval(UUID movementDocumentId, boolean alreadyPosted) { }

    @Transactional(readOnly = true)
    public Documents list(UUID warehouseId, int page, int size) {
        UUID warehouse = warehouse(warehouseId);
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid page or size");
        SourceWarehouse sourceWarehouse = sourceWarehouse(warehouse);
        JsonNode source = sourceWarehouse.sourceId() == null
            ? client.list(sourceWarehouse.ids().getFirst(), page, size)
            : client.listBy1c(sourceWarehouse.sourceId(), page, size);
        if (!source.path("content").isArray()) throw new BusinessException("Invalid Kimyosanoat issue response");
        List<Document> result = new ArrayList<>();
        for (JsonNode row : source.path("content")) {
            if (!sourceWarehouse.ids().contains(row.path("fromWarehouseId").asLong(-1))) throw new BusinessException("Kimyosanoat returned an issue outside the warehouse");
            ExitWeighedIssue candidate = ExitWeighedIssue.from(row);
            UUID material = candidate == null ? null : mappedMaterial(row);
            ObjectNode snapshot = snapshot(row);
            Link link = link(row.path("id").asLong());
            String state = candidate == null ? "UNSUPPORTED" : material == null ? "MAPPING_REQUIRED" : "PENDING";
            StockAmount amount = null;
            if (material != null) {
                try { amount = stockAmount(material, candidate.quantityKg()); }
                catch (BusinessException ex) { state = "UNSUPPORTED"; }
            }
            String fingerprint = fingerprint(snapshot, material, amount);
            if (link != null) {
                if (!link.belongsTo(warehouse)) continue;
                boolean same = fingerprint.equals(link.fingerprint()) && Objects.equals(material, link.materialId());
                if (candidate != null && link.materialId() != null) {
                    try {
                        StockAmount linkedAmount = stockAmount(link.materialId(), candidate.quantityKg());
                        if (selectionFingerprint(snapshot, link.materialId(), linkedAmount).equals(link.fingerprint())) {
                            same = true; material = link.materialId(); amount = linkedAmount;
                        }
                    } catch (BusinessException ignored) { }
                }
                state = same && candidate != null && "POSTED".equals(link.movementStatus()) ? "POSTED" : "RECONCILE";
            }
            EventReview review = eventReview(row.path("id").asLong());
            if (link == null && review != null && "REVIEW_REQUIRED".equals(review.state())
                    && ("PENDING".equals(state) || "MAPPING_REQUIRED".equals(state))) state = "REVIEW_REQUIRED";
            result.add(new Document(row.path("id").asText(), fingerprint, snapshot, material,
                candidate == null ? null : candidate.quantityKg().toPlainString(),
                amount == null ? null : amount.quantity().stripTrailingZeros().toPlainString(),
                amount == null ? null : amount.unit(), state, link == null ? null : link.movementId(), sourceMatchFingerprint(snapshot),
                review == null ? null : review.eventId(),
                review == null || "POSTED".equals(state) ? null : review.reason()));
        }
        return new Documents(List.copyOf(result), page, size, source.path("totalElements").asLong(), source.path("totalPages").asInt());
    }

    @Transactional
    public Approval approve(long sourceId, ApproveRequest request) {
        if (sourceId <= 0 || request == null || request.stockBalanceId() == null || request.fingerprint() == null)
            throw new BadRequestException("Issue, warehouse, stock position and document fingerprint are required");
        UUID warehouse = warehouse(request.warehouseId());
        SourceWarehouse sourceWarehouse = sourceWarehouse(warehouse);
        JsonNode source = sourceWarehouse.sourceId() == null
            ? client.detail(sourceWarehouse.ids().getFirst(), sourceId)
            : client.detailBy1c(sourceWarehouse.sourceId(), sourceId);
        ExitWeighedIssue candidate = ExitWeighedIssue.from(source);
        if (candidate == null || candidate.transferId() != sourceId || !sourceWarehouse.ids().contains(candidate.warehouseId()))
            throw new ConflictException("Source document is no longer eligible for issue");
        ObjectNode snapshot = snapshot(source);
        UUID materialId = request.materialId() == null ? mappedMaterial(source) : request.materialId();
        if (materialId == null) throw new ConflictException("Kimyosanoat product code does not uniquely identify an imported material; refresh before approval");
        StockAmount amount = stockAmount(materialId, candidate.quantityKg());
        String currentFingerprint = request.materialId() == null ? fingerprint(snapshot, materialId, amount)
            : selectionFingerprint(snapshot, materialId, amount);
        if (!currentFingerprint.equals(request.fingerprint())) throw new ConflictException("Source document or material changed; refresh before approval");
        Link existing = link(sourceId);
        if (existing != null) {
            Approval replayed = replay(existing, warehouse, materialId, currentFingerprint);
            markEventPosted(request.eventId(), sourceId, replayed.movementDocumentId());
            return replayed;
        }
        if (request.materialId() != null && !candidateIds(source, warehouse).contains(materialId))
            throw new ConflictException("Selected material no longer matches the source product code or MXIK");
        Approval posted = claimAndPost(sourceId, warehouse, materialId, currentFingerprint, source, snapshot,
            amount, request.stockBalanceId(), SecurityContextHolder.getContext().getAuthentication().getName());
        markEventPosted(request.eventId(), sourceId, posted.movementDocumentId());
        return posted;
    }

    @Transactional
    public Approval autoApprove(UUID eventId, long sourceId, UUID warehouse1cId, JsonNode source, List<Long> sourceWarehouseIds) {
        if (eventId == null || sourceId <= 0 || warehouse1cId == null || sourceWarehouseIds == null || sourceWarehouseIds.isEmpty())
            throw new KimyosanoatAutoReviewException("SOURCE_IDENTITY_INVALID", "Source warehouse identity is missing");
        String[] groups = warehouse1cId.toString().split("-");
        String raw1cId = groups[3] + groups[4] + groups[2] + groups[1] + groups[0];
        List<UUID> mappedWarehouses = jdbc.queryForList("""
            select distinct master_id from warehouse_source_master_links
            where tenant_id=? and company_id=? and reference_kind='WAREHOUSE'
              and reference_type='00000055' and lower(source_id)=?
            """, UUID.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), raw1cId);
        if (mappedWarehouses.size() != 1)
            throw new KimyosanoatAutoReviewException("WAREHOUSE_MAPPING_REQUIRED", "One imported MM warehouse mapping is required");
        UUID warehouse = mappedWarehouses.getFirst();
        if (warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(),
                CompanyContext.currentCompany(), warehouse).isEmpty())
            throw new KimyosanoatAutoReviewException("WAREHOUSE_UNAVAILABLE", "Mapped MM warehouse is unavailable");
        ExitWeighedIssue candidate = ExitWeighedIssue.from(source);
        if (candidate == null || candidate.transferId() != sourceId || !sourceWarehouseIds.contains(candidate.warehouseId()))
            throw new KimyosanoatAutoReviewException("SOURCE_NOT_ELIGIBLE", "Source transfer is not a final eligible weighed exit");
        ObjectNode snapshot = snapshot(source);
        Link existing = link(sourceId);
        if (existing != null) {
            try {
                StockAmount linkedAmount = stockAmount(existing.materialId(), candidate.quantityKg());
                String fp = fingerprint(snapshot, existing.materialId(), linkedAmount);
                if (!fp.equals(existing.fingerprint())) fp = selectionFingerprint(snapshot, existing.materialId(), linkedAmount);
                Approval replayed = replay(existing, warehouse, existing.materialId(), fp);
                markEventPosted(eventId, sourceId, replayed.movementDocumentId());
                return replayed;
            } catch (BusinessException | ConflictException ex) {
                throw new KimyosanoatAutoReviewException("POSTED_SOURCE_CHANGED", ex.getMessage());
            }
        }
        UUID materialId = mappedMaterial(source);
        if (materialId == null)
            throw new KimyosanoatAutoReviewException("MATERIAL_MAPPING_REQUIRED", "Product N-code does not uniquely identify an imported MM material");
        StockAmount amount;
        try { amount = stockAmount(materialId, candidate.quantityKg()); }
        catch (BusinessException ex) { throw new KimyosanoatAutoReviewException("MATERIAL_UNIT_INVALID", ex.getMessage()); }
        List<UUID> positions = jdbc.queryForList("""
            select id from stock_balances
            where tenant_id=? and company_id=? and warehouse_id=? and material_id=?
              and is_delete=false and stock_category='OWNED' and owner_party_id is null
              and stock_batch_id is not null and available_quantity>=?
            order by id limit 2
            """, UUID.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouse,
            materialId, amount.quantity());
        if (positions.size() != 1)
            throw new KimyosanoatAutoReviewException(positions.isEmpty() ? "STOCK_INSUFFICIENT" : "STOCK_POSITION_AMBIGUOUS",
                positions.isEmpty() ? "No single owned stock position covers the source quantity" : "Multiple stock positions can cover the source quantity");
        try {
            Approval posted = claimAndPost(sourceId, warehouse, materialId, fingerprint(snapshot, materialId, amount),
                source, snapshot, amount, positions.getFirst(), "kimyosanoat-auto");
            markEventPosted(eventId, sourceId, posted.movementDocumentId());
            return posted;
        } catch (BusinessException | ConflictException ex) {
            throw new KimyosanoatAutoReviewException("POSTING_REVIEW_REQUIRED", ex.getMessage());
        }
    }

    private Approval claimAndPost(long sourceId, UUID warehouse, UUID materialId, String currentFingerprint,
            JsonNode source, ObjectNode snapshot, StockAmount amount, UUID stockBalanceId, String actor) {
        int inserted = jdbc.update("""
            insert into kimyosanoat_issue_links(source_transfer_id, tenant_id, company_id, warehouse_id, material_id, source_fingerprint, source_snapshot, created_by)
            values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?) on conflict (source_transfer_id) do nothing
            """, sourceId, TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouse, materialId, currentFingerprint, snapshot.toString(), actor);
        if (inserted == 0) return replay(Objects.requireNonNull(link(sourceId)), warehouse, materialId, currentFingerprint);
        StockBalance balance = balances.findByIdAndIsDeletedFalseForUpdate(stockBalanceId)
            .orElseThrow(() -> new BusinessException("Stock position is unavailable"));
        if (!TenantContext.currentTenant().equals(balance.getTenantId()) || !CompanyContext.currentCompany().equals(balance.getCompanyId())
                || !warehouse.equals(balance.getWarehouseId()) || !materialId.equals(balance.getMaterialId())
                || !"OWNED".equals(balance.getStockCategory()) || balance.getOwnerPartyId() != null
                || balance.getStockBatchId() == null || balance.getAvailableQuantity().compareTo(amount.quantity()) < 0)
            throw new BusinessException("Selected stock position is not eligible or has insufficient stock");
        StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
        item.setMaterialId(materialId); item.setQuantity(amount.quantity()); item.setStockBatchId(balance.getStockBatchId());
        item.setStorageLocationId(balance.getStorageLocationId()); item.setStorageBinId(balance.getStorageBinId());
        StockMovementDocumentCreateRequest document = new StockMovementDocumentCreateRequest();
        document.setName("Kimyosanoat shipment issue"); document.setDescription("Kimyosanoat transfer " + sourceId + "; buyer: " + source.path("buyerName").asText(""));
        document.setWarehouseId(warehouse); document.setMovementType(MovementType.ISSUE); document.setMovementDate(LocalDate.now());
        document.setReferenceType(SYSTEM);
        document.setReferenceId(UUID.nameUUIDFromBytes((SYSTEM + ":" + sourceId).getBytes(StandardCharsets.UTF_8)));
        document.setItems(List.of(item));
        var created = movements.createKimyosanoatIssue(document);
        movements.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING));
        var approved = movements.changeStatus(created.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.APPROVED));
        var posted = movements.postKimyosanoatIssue(created.getId(), new StockMovementPostRequest(approved.getVersion()), "kimyosanoat-issue-" + sourceId);
        jdbc.update("update kimyosanoat_issue_links set movement_document_id=? where source_transfer_id=?", posted.getId(), sourceId);
        return new Approval(posted.getId(), false);
    }

    private void markEventPosted(UUID eventId, long sourceId, UUID movementId) {
        if (eventId == null) return;
        int updated = jdbc.update("""
            update kimyosanoat_event_inbox set processing_state='POSTED', movement_document_id=?,
                review_reason=null, review_detail=null, processed_at=current_timestamp,
                lease_token=null, lease_until=null
            where event_id=? and tenant_id=? and company_id=? and transfer_id=?
            """, movementId, eventId, TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceId);
        if (updated != 1) throw new ConflictException("Kimyosanoat event is no longer available for this issue");
    }

    private record EventReview(UUID eventId, String state, String reason) { }

    private EventReview eventReview(long sourceId) {
        return jdbc.query("""
            select event_id, processing_state, review_reason from kimyosanoat_event_inbox
            where tenant_id=? and company_id=? and transfer_id=?
            order by occurred_at desc, received_at desc limit 1
            """, rs -> rs.next() ? new EventReview(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)) : null,
            TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceId);
    }

    private Approval replay(Link link, UUID warehouse, UUID material, String fingerprint) {
        if (!link.belongsTo(warehouse) || link.movementId() == null) throw new ConflictException("Source issue is already registered");
        if (!fingerprint.equals(link.fingerprint()) || !material.equals(link.materialId()) || !"POSTED".equals(link.movementStatus()))
            throw new ConflictException("Posted source document changed; reconciliation is required");
        return new Approval(link.movementId(), true);
    }

    private UUID warehouse(UUID requested) {
        UUID id = access.availabilityWarehouseId(requested);
        if (id == null || warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), id).isEmpty())
            throw new BadRequestException("Warehouse is required");
        access.assertWarehouseAccess(id);
        return id;
    }

    private record SourceWarehouse(List<Long> ids, String sourceId) { }

    private SourceWarehouse sourceWarehouse(UUID warehouse) {
        var sourceIds = jdbc.queryForList("""
            select distinct source_id from warehouse_source_master_links
            where tenant_id=? and company_id=? and reference_kind='WAREHOUSE'
              and reference_type='00000055' and master_id=?
            """, String.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouse);
        if (sourceIds.size() > 1) throw new BusinessException("Multiple 1C identities for this warehouse; review required");
        if (sourceIds.size() == 1) return new SourceWarehouse(client.resolveWarehouses(sourceIds.getFirst()), sourceIds.getFirst());
        var matches = mappings.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndIsDeletedFalseOrderByPriorityAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "WAREHOUSE", warehouse).stream()
            .filter(this::activeKimyoMapping).map(ReferenceMapping::getExternalCode).distinct().toList();
        if (matches.size() != 1) throw new BusinessException("One active Kimyosanoat warehouse mapping is required");
        try { long id = Long.parseLong(matches.get(0)); if (id > 0) return new SourceWarehouse(List.of(id), null); } catch (NumberFormatException ignored) { }
        throw new BusinessException("Kimyosanoat warehouse mapping must be a positive numeric ID");
    }

    @Transactional(readOnly = true)
    public List<MaterialCandidate> materialCandidates(UUID warehouseId, long sourceId, String expectedFingerprint) {
        if (sourceId <= 0 || expectedFingerprint == null || expectedFingerprint.isBlank())
            throw new BadRequestException("Issue and source fingerprint are required");
        UUID warehouse = warehouse(warehouseId);
        SourceWarehouse sourceWarehouse = sourceWarehouse(warehouse);
        JsonNode source = sourceWarehouse.sourceId() == null
            ? client.detail(sourceWarehouse.ids().getFirst(), sourceId)
            : client.detailBy1c(sourceWarehouse.sourceId(), sourceId);
        ExitWeighedIssue issue = ExitWeighedIssue.from(source);
        if (issue == null || issue.transferId() != sourceId || !sourceWarehouse.ids().contains(issue.warehouseId()))
            throw new ConflictException("Source document is no longer eligible for issue");
        if (link(sourceId) != null) throw new ConflictException("Source issue is already registered");
        List<UUID> nMatches = nCodeMatches(source);
        String mxik = sourceCode(source, "mxikCode", 40);
        ObjectNode snapshot = snapshot(source);
        if (!sourceMatchFingerprint(snapshot).equals(expectedFingerprint))
            throw new ConflictException("Source document changed; refresh before selecting material");
        List<MaterialCandidate> result = new ArrayList<>();
        for (UUID id : candidateIds(source, warehouse, nMatches, mxik)) {
            Material material = materials.findByIdAndIsDeletedFalse(id).orElse(null);
            if (material == null) continue;
            try {
                StockAmount amount = stockAmount(id, issue.quantityKg());
                boolean nMatch = nMatches.contains(id);
                String mmMxik = material.getMxikCode() == null ? null : material.getMxikCode().strip();
                boolean mxikMatch = mxik != null && canonicalMxik(mxik).equals(canonicalMxik(mmMxik));
                boolean normalized = mxikMatch && !mxik.equals(mmMxik);
                if (!nMatch && !mxikMatch) continue;
                String reason = nMatch && mxikMatch ? (normalized ? "N_CODE_AND_MXIK_NORMALIZED" : "N_CODE_AND_MXIK")
                    : nMatch ? "N_CODE" : normalized ? "MXIK_NORMALIZED" : "MXIK";
                result.add(new MaterialCandidate(id, material.getName(), materialCode(material), material.getMxikCode(), reason,
                    amount.quantity().stripTrailingZeros().toPlainString(), amount.unit(), selectionFingerprint(snapshot, id, amount)));
            } catch (BusinessException ignored) { }
        }
        return List.copyOf(result);
    }

    private UUID mappedMaterial(JsonNode source) {
        List<UUID> matches = nCodeMatches(source);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String sourceCode(JsonNode source, String field, int maxLength) {
        JsonNode node = source.path(field);
        if (!node.isTextual()) return null;
        String value = node.asText().strip();
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private List<UUID> nCodeMatches(JsonNode source) {
        String code = sourceCode(source, "productNCode", 15);
        if (code == null) return List.of();
        return jdbc.queryForList("""
            select case when p.target_id=l.master_id and p.fields->>'marked'='false'
                              and p.fields->>'isGroup'='false' then p.target_id else null end
            from warehouse_source_profiles p
            left join warehouse_source_master_links l on l.tenant_id=p.tenant_id and l.company_id=p.company_id
              and l.reference_kind='MATERIAL' and l.reference_type='00000037' and l.source_id=p.source_id
            where p.tenant_id=? and p.company_id=? and p.category='MATERIAL_DETAILS'
              and p.source_table='_reference55' and p.code in (?, rpad(?, 15, ' '))
            """, UUID.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), code, code);
    }

    private String materialCode(Material material) {
        if (material.getSourceData() != null && material.getSourceData().get("code") instanceof String sourceCode
            && !sourceCode.isBlank()) return sourceCode.strip();
        return material.getCode();
    }

    private String canonicalMxik(String value) {
        if (value == null) return null;
        String code = value.strip();
        return code.matches("[0-9]{16}") ? "0" + code : code;
    }

    private List<UUID> candidateIds(JsonNode source, UUID warehouse) {
        return candidateIds(source, warehouse, nCodeMatches(source), sourceCode(source, "mxikCode", 40));
    }

    private List<UUID> candidateIds(JsonNode source, UUID warehouse, List<UUID> nMatches, String mxik) {
        var ids = new java.util.LinkedHashSet<UUID>();
        nMatches.stream().filter(Objects::nonNull).forEach(ids::add);
        if (mxik != null) ids.addAll(jdbc.queryForList("""
            select distinct m.id from materials m join stock_balances b
              on b.tenant_id=m.tenant_id and b.company_id=m.company_id and b.material_id=m.id
            where m.tenant_id=? and m.company_id=? and m.is_delete=false and m.status='ACTIVE'
              and b.warehouse_id=? and b.is_delete=false and b.stock_category='OWNED' and b.owner_party_id is null
              and b.stock_batch_id is not null and b.available_quantity > 0
              and (btrim(m.ikpu_code)=? or (btrim(m.ikpu_code) ~ '^[0-9]{16,17}$'
                   and lpad(btrim(m.ikpu_code), 17, '0')=?))
            """, UUID.class, TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouse, mxik,
            mxik.matches("[0-9]{16,17}") ? canonicalMxik(mxik) : null));
        return List.copyOf(ids);
    }

    private boolean activeKimyoMapping(ReferenceMapping m) {
        LocalDate today = LocalDate.now();
        return SYSTEM.equals(m.getExternalSystem()) && AREA.equals(m.getAccountingArea()) && "ACTIVE".equals(m.getStatus())
            && (m.getValidFrom() == null || !m.getValidFrom().isAfter(today)) && (m.getValidTo() == null || !m.getValidTo().isBefore(today));
    }

    private record StockAmount(BigDecimal quantity, String unit) { }

    private StockAmount stockAmount(UUID materialId, BigDecimal quantityKg) {
        Material material = materials.findByIdAndIsDeletedFalse(materialId).orElseThrow(() -> new BusinessException("Mapped material unavailable"));
        if (!TenantContext.currentTenant().equals(material.getTenantId()) || !CompanyContext.currentCompany().equals(material.getCompanyId())
            || !"ACTIVE".equals(material.getStatus()) || material.getUnitOfMeasure() == null
            || material.getUnitOfMeasure().isDeleted()
            || !TenantContext.currentTenant().equals(material.getUnitOfMeasure().getTenantId())
            || !CompanyContext.currentCompany().equals(material.getUnitOfMeasure().getCompanyId()))
            throw new BusinessException("Mapped material unavailable");
        UnitOfMeasure unit = material.getUnitOfMeasure();
        var names = java.util.stream.Stream.of(unit.getCode(), unit.getMeasurementNameShort(), unit.getMeasurementNameShortRu(), unit.getMeasurementNameShortUz())
            .filter(Objects::nonNull).map(x -> x.strip().toLowerCase(Locale.ROOT)).toList();
        boolean kg = names.stream().anyMatch(Set.of("kg", "кг", "kilogram", "kilograms", "килограмм", "килограммы", "килограммов")::contains);
        boolean tonne = names.stream().anyMatch(Set.of("t", "т", "tn", "тн", "ton", "tons", "tonne", "tonnes", "tonna", "тонна", "тонны", "тонн")::contains);
        if (kg == tonne) throw new BusinessException("Mapped MM material must use an unambiguous mass unit");
        BigDecimal quantity = tonne ? quantityKg.movePointLeft(3) : quantityKg;
        StockUomPolicy.quantity(material, quantity, false);
        return new StockAmount(quantity, tonne ? "t" : "kg");
    }

    private ObjectNode snapshot(JsonNode source) {
        ObjectNode result = json.createObjectNode();
        for (String key : SNAPSHOT_FIELDS) if (source.has(key)) result.set(key, source.get(key).deepCopy());
        copyFields(source.path("weight"), result.putObject("weight"), List.of("tara", "taraTime", "brutto", "bruttoTime", "netto"));
        copyFields(source.path("lastEndedLoad"), result.putObject("lastEndedLoad"),
            List.of("type", "bagCount", "weight", "totalWeight", "concentration", "actualQuantity", "actualQuantityUnit"));
        var drivers = result.putArray("drivers");
        if (source.path("driverDtos").isArray()) for (JsonNode driver : source.path("driverDtos")) {
            ObjectNode item = drivers.addObject();
            copyFields(driver, item, List.of("transportNumber", "transportModel", "transportDriverName"));
        }
        return result;
    }

    private void copyFields(JsonNode source, ObjectNode target, List<String> fields) {
        for (String key : fields) if (source.has(key)) target.set(key, source.get(key).deepCopy());
    }

    private String fingerprint(JsonNode snapshot, UUID materialId, StockAmount amount) {
        ObjectNode relevant = json.createObjectNode();
        for (String key : SNAPSHOT_FIELDS) if (FINGERPRINT_FIELDS.contains(key) && snapshot.has(key)) relevant.set(key, snapshot.get(key));
        if (materialId != null) relevant.put("mmMaterialId", materialId.toString());
        if (amount != null) {
            relevant.put("mmStockQuantity", amount.quantity().stripTrailingZeros().toPlainString());
            relevant.put("mmStockUnit", amount.unit());
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(relevant))); }
        catch (Exception ex) { throw new BusinessException("Cannot verify Kimyosanoat document"); }
    }

    private String sourceMatchFingerprint(JsonNode snapshot) {
        ObjectNode source = json.createObjectNode();
        source.put("document", fingerprint(snapshot, null, null));
        for (String field : List.of("mxikCode", "productName", "lotProductName"))
            if (snapshot.has(field)) source.set(field, snapshot.get(field));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(source)));
        } catch (Exception ex) { throw new BusinessException("Cannot verify Kimyosanoat document"); }
    }

    private String selectionFingerprint(JsonNode snapshot, UUID materialId, StockAmount amount) {
        Material material = materials.findByIdAndIsDeletedFalse(materialId)
            .orElseThrow(() -> new BusinessException("Mapped material unavailable"));
        ObjectNode selection = json.createObjectNode();
        selection.put("version", "selected-material-v1");
        selection.put("document", fingerprint(snapshot, materialId, amount));
        for (String field : List.of("mxikCode", "productName", "lotProductName"))
            if (snapshot.has(field)) selection.set(field, snapshot.get(field));
        selection.put("materialName", material.getName());
        selection.put("materialCode", materialCode(material));
        selection.put("materialMxik", material.getMxikCode());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(selection)));
        } catch (Exception ex) { throw new BusinessException("Cannot verify Kimyosanoat document"); }
    }

    private Link link(long sourceId) {
        return jdbc.query("""
            select l.tenant_id, l.company_id, l.warehouse_id, l.material_id, l.movement_document_id,
                   l.source_fingerprint, d.status
            from kimyosanoat_issue_links l left join stock_movement_documents d
              on d.id=l.movement_document_id and d.tenant_id=l.tenant_id and d.company_id=l.company_id and d.is_delete=false
            where l.source_transfer_id=?
            """, rs -> rs.next() ? new Link(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7)) : null, sourceId);
    }
    private record Link(String tenant, UUID company, UUID warehouse, UUID materialId, UUID movementId, String fingerprint, String movementStatus) {
        boolean belongsTo(UUID warehouseId) { return tenant.equals(TenantContext.currentTenant()) && company.equals(CompanyContext.currentCompany()) && warehouse.equals(warehouseId); }
    }
}
