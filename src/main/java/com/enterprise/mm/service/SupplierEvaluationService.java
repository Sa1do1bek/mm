package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierEvaluation;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierEvaluationMapper;
import com.enterprise.mm.payload.BusinessActionDtos.SupplierEvaluationRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierEvaluationCreateRequest;
import com.enterprise.mm.payload.SupplierEvaluationResponse;
import com.enterprise.mm.payload.SupplierEvaluationUpdateRequest;
import com.enterprise.mm.payload.SupplierEvaluationSearchRequest;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierEvaluationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierEvaluationService {
    private final SupplierEvaluationRepository repository;
    private final SupplierEvaluationMapper mapper;
    private final String entityName = "SupplierEvaluation";
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final SupplierAdmissionService supplierAdmissionService;

    @Transactional
    public SupplierEvaluationResponse evaluate(SupplierEvaluationRequest request) {
        SupplierEvaluation evaluation = createEvaluation(
            request.supplierId(),
            request.purchaseOrderId(),
            request.deliveryScore(),
            request.qualityScore(),
            request.priceScore(),
            request.claimsScore(),
            request.quantityScore()
        );
        return mapper.toResponse(evaluation);
    }

    @Transactional
    public SupplierEvaluation evaluateDelivery(UUID supplierId, UUID purchaseOrderId, int deliveryScore) {
        return createEvaluation(
            supplierId,
            purchaseOrderId,
            BigDecimal.valueOf(deliveryScore),
            BigDecimal.valueOf(95),
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(100)
        );
    }

    @Transactional
    public SupplierEvaluation evaluateReceipt(PurchaseOrder po, PurchaseOrderItem item, UUID stockMovementId, BigDecimal receivedQuantity, LocalDate receiptDate) {
        requireCurrentCompany(po.getTenantId(), po.getCompanyId(), "Purchase order", po.getId());
        requireCurrentCompany(item.getTenantId(), item.getCompanyId(), "Purchase order item", item.getId());
        UUID supplierId = po.getSupplier() != null ? po.getSupplier().getId() : po.getSupplierId();
        Supplier supplier = supplier(supplierId);
        BigDecimal ordered = nvl(item.getQuantity());
        BigDecimal received = nvl(receivedQuantity);
        int lateDays = lateDays(po, receiptDate);

        SupplierEvaluation evaluation = repository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                stockMovementId
            )
            .orElseGet(SupplierEvaluation::new);
        if (evaluation.getId() == null) {
            evaluation.setCode("EVAL-" + UUID.randomUUID().toString().substring(0, 8));
            evaluation.setName(BusinessNameGenerator.workflowDocumentName("SupplierEvaluation", po.getName()));
        }
        evaluation.setStatus("POSTED");
        evaluation.setSupplier(supplier);
        evaluation.setPurchaseOrder(po);
        evaluation.setPurchaseOrderItem(item);
        evaluation.setStockMovementId(stockMovementId);
        evaluation.setMaterialId(item.getMaterialId());
        evaluation.setOrderedQuantity(ordered);
        evaluation.setReceivedQuantity(received);
        evaluation.setAcceptedQuantity(received);
        evaluation.setRejectedQuantity(BigDecimal.ZERO);
        evaluation.setLateDays(lateDays);
        evaluation.setDeliveryScore(deliveryScore(lateDays));
        evaluation.setQualityScore(BigDecimal.valueOf(95));
        evaluation.setQuantityScore(quantityScore(ordered, received));
        evaluation.setPriceScore(BigDecimal.valueOf(100));
        evaluation.setClaimsScore(BigDecimal.valueOf(100));
        evaluation.setTotalScore(weighted(evaluation));
        evaluation.setEvaluationSource("GOODS_RECEIPT");
        evaluation.setCalculationDetailsJson(toJson(Map.of(
            "source", "GOODS_RECEIPT",
            "orderedQuantity", ordered,
            "receivedQuantity", received,
            "lateDays", lateDays
        )));
        evaluation.setEvaluatedAt(LocalDateTime.now());
        SupplierEvaluation saved = repository.save(evaluation);
        recalculateSupplierRating(supplier);
        auditService.log("SupplierEvaluation", saved.getId(), "RECEIPT_EVALUATION", saved.getTotalScore().toPlainString(), null, null);
        return saved;
    }

    @Transactional
    public void recalculateFromQualityInspection(QualityInspection inspection) {
        requireCurrentCompany(
            inspection.getTenantId(), inspection.getCompanyId(), "Quality inspection", inspection.getId());
        if (inspection.getStockMovementId() == null) {
            return;
        }
        SupplierEvaluation evaluation = repository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                inspection.getStockMovementId()
            )
            .orElse(null);
        if (evaluation == null) {
            return;
        }
        BigDecimal received = positive(inspection.getReceivedQuantity());
        BigDecimal accepted = positive(inspection.getAcceptedQuantity());
        BigDecimal rejected = positive(inspection.getRejectedQuantity());
        evaluation.setAcceptedQuantity(accepted);
        evaluation.setRejectedQuantity(rejected);
        evaluation.setQualityScore(qualityScore(received, accepted, rejected));
        evaluation.setEvaluationSource("QUALITY_INSPECTION");
        evaluation.setCalculationDetailsJson(toJson(Map.of(
            "source", "QUALITY_INSPECTION",
            "inspectionId", inspection.getId(),
            "receivedQuantity", received,
            "acceptedQuantity", accepted,
            "rejectedQuantity", rejected,
            "inspectionStatus", Objects.toString(inspection.getStatus(), "")
        )));
        evaluation.setTotalScore(weighted(evaluation));
        evaluation.setEvaluatedAt(LocalDateTime.now());
        SupplierEvaluation saved = repository.save(evaluation);
        recalculateSupplierRating(supplier(saved.getSupplierId()));
        auditService.log("SupplierEvaluation", saved.getId(), "QUALITY_RECALCULATION", saved.getTotalScore().toPlainString(), null, null);
    }

    private SupplierEvaluation createEvaluation(UUID supplierId, UUID purchaseOrderId, BigDecimal delivery, BigDecimal quality, BigDecimal price, BigDecimal claims, BigDecimal quantity) {
        Supplier supplier = supplier(supplierId);
        SupplierEvaluation evaluation = new SupplierEvaluation();
        evaluation.setCode("EVAL-" + UUID.randomUUID().toString().substring(0, 8));
        evaluation.setStatus("POSTED");
        evaluation.setSupplier(supplier);
        if (purchaseOrderId != null) {
            PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId);
            if (!Objects.equals(purchaseOrder.getSupplierId(), supplierId)) {
                throw new BadRequestException("Purchase order does not belong to supplier: " + supplierId);
            }
            evaluation.setName(BusinessNameGenerator.workflowDocumentName("SupplierEvaluation", purchaseOrder.getName()));
            evaluation.setPurchaseOrder(purchaseOrder);
        } else {
            evaluation.setName("Evaluation for " + supplier.getName());
        }
        evaluation.setDeliveryScore(nvl(delivery));
        evaluation.setQualityScore(nvl(quality));
        evaluation.setQuantityScore(nvl(quantity));
        evaluation.setPriceScore(nvl(price));
        evaluation.setClaimsScore(nvl(claims));
        evaluation.setTotalScore(weighted(evaluation));
        evaluation.setEvaluationSource("MANUAL");
        evaluation.setEvaluatedAt(LocalDateTime.now());
        SupplierEvaluation saved = repository.save(evaluation);
        recalculateSupplierRating(supplier);
        auditService.log("SupplierEvaluation", saved.getId(), "EVALUATE", saved.getTotalScore().toPlainString(), null, null);
        return saved;
    }

    private BigDecimal weighted(SupplierEvaluation evaluation) {
        return nvl(evaluation.getDeliveryScore()).multiply(BigDecimal.valueOf(0.30))
            .add(nvl(evaluation.getQualityScore()).multiply(BigDecimal.valueOf(0.40)))
            .add(nvl(evaluation.getQuantityScore()).multiply(BigDecimal.valueOf(0.15)))
            .add(nvl(evaluation.getPriceScore()).multiply(BigDecimal.valueOf(0.10)))
            .add(nvl(evaluation.getClaimsScore()).multiply(BigDecimal.valueOf(0.05)))
            .setScale(2, RoundingMode.HALF_UP);
    }

    private void recalculateSupplierRating(Supplier supplier) {
        var evaluations = repository.findByTenantIdAndSupplier_IdAndIsDeletedFalse(TenantContext.currentTenant(), supplier.getId());
        if (evaluations.isEmpty()) {
            return;
        }
        BigDecimal avg = evaluations.stream()
            .map(SupplierEvaluation::getTotalScore)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(evaluations.size()), 2, RoundingMode.HALF_UP);
        supplier.setRating(avg);
        if (avg.compareTo(BigDecimal.valueOf(85)) >= 0) {
            supplier.setCategory("A");
        } else if (avg.compareTo(BigDecimal.valueOf(70)) >= 0) {
            supplier.setCategory("B");
        } else if (avg.compareTo(BigDecimal.valueOf(55)) >= 0) {
            supplier.setCategory("C");
        } else {
            supplier.setCategory("D");
        }
        supplierRepository.save(supplier);
        supplierAdmissionService.recalculate(supplier.getId());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal positive(BigDecimal value) {
        return nvl(value).max(BigDecimal.ZERO);
    }

    private Supplier supplier(UUID supplierId) {
        Supplier supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), supplierId)
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierId));
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new NotFoundException("Supplier not found: " + supplierId);
        }
        return supplier;
    }

    private PurchaseOrder purchaseOrder(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository
            .findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        if (!Objects.equals(CompanyContext.currentCompany(), purchaseOrder.getCompanyId())) {
            throw new NotFoundException("Purchase order not found: " + purchaseOrderId);
        }
        return purchaseOrder;
    }

    private int lateDays(PurchaseOrder po, LocalDate receiptDate) {
        if (po.getExpectedDeliveryDate() == null || receiptDate == null || !receiptDate.isAfter(po.getExpectedDeliveryDate())) {
            return 0;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(po.getExpectedDeliveryDate(), receiptDate));
    }

    private BigDecimal deliveryScore(int lateDays) {
        return BigDecimal.valueOf(Math.max(50, 100 - (lateDays * 5L)));
    }

    private BigDecimal quantityScore(BigDecimal ordered, BigDecimal received) {
        if (ordered == null || ordered.signum() <= 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal ratio = nvl(received).divide(ordered, 4, RoundingMode.HALF_UP);
        return ratio.min(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal qualityScore(BigDecimal received, BigDecimal accepted, BigDecimal rejected) {
        if (received == null || received.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal acceptedScore = nvl(accepted).divide(received, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        BigDecimal rejectionPenalty = nvl(rejected).divide(received, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(25));
        return acceptedScore.subtract(rejectionPenalty).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    @Transactional(readOnly = true)
    public Page<SupplierEvaluationResponse> search(SupplierEvaluationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            searchPattern(filter),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.supplierId(),
            filter.purchaseOrderId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            request.totalScoreMin(),
            request.totalScoreMax(),
            request.evaluatedFrom() == null ? null : request.evaluatedFrom().atStartOfDay(),
            request.evaluatedTo() == null ? null : request.evaluatedTo().atTime(java.time.LocalTime.MAX),
            pageable
        ).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierEvaluationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public SupplierEvaluationResponse create(SupplierEvaluationCreateRequest dto) {
        SupplierEvaluation entity = mapper.toEntity(dto);
        validateCreateScope(entity);
        beforeCreate(entity, dto);
        SupplierEvaluation saved = repository.save(entity);
        recalculateSupplierRating(supplier(saved.getSupplierId()));
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public SupplierEvaluationResponse update(UUID id, SupplierEvaluationUpdateRequest dto) {
        SupplierEvaluation entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        UUID previousSupplierId = entity.getSupplierId();
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierEvaluation saved = repository.save(entity);
        if (previousSupplierId != null) {
            recalculateSupplierRating(supplier(previousSupplierId));
        }
        if (saved.getSupplierId() != null && !Objects.equals(previousSupplierId, saved.getSupplierId())) {
            recalculateSupplierRating(supplier(saved.getSupplierId()));
        }
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        SupplierEvaluation entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        UUID supplierId = entity.getSupplierId();
        entity.softDelete();
        repository.save(entity);
        if (supplierId != null) {
            recalculateSupplierRating(supplier(supplierId));
        }
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public SupplierEvaluationResponse restore(UUID id) {
        SupplierEvaluation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierEvaluation saved = repository.save(entity);
        if (saved.getSupplierId() != null) {
            recalculateSupplierRating(supplier(saved.getSupplierId()));
        }
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected SupplierEvaluation findEntity(UUID id) {
        SupplierEvaluation entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    private SupplierEvaluation findMutableEntity(UUID id) {
        SupplierEvaluation entity = findEntity(id);
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    private void validateCreateScope(SupplierEvaluation entity) {
        UUID supplierId = entity.getSupplierId();
        supplier(supplierId);
        if (entity.getPurchaseOrderId() == null) {
            return;
        }
        PurchaseOrder purchaseOrder = purchaseOrder(entity.getPurchaseOrderId());
        if (!Objects.equals(purchaseOrder.getSupplierId(), supplierId)) {
            throw new BadRequestException("Purchase order does not belong to supplier: " + supplierId);
        }
    }

    private void requireCurrentCompany(String tenantId, UUID companyId, String label, UUID id) {
        if (!Objects.equals(TenantContext.currentTenant(), tenantId)
            || !Objects.equals(CompanyContext.currentCompany(), companyId)) {
            throw new NotFoundException(label + " not found: " + id);
        }
    }

    protected void beforeCreate(SupplierEvaluation entity, SupplierEvaluationCreateRequest dto) {
        entity.setStatus("POSTED");
        entity.setEvaluationSource("MANUAL");
        entity.setEvaluatedAt(LocalDateTime.now());
        validateAndCalculateScore(entity);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    protected void beforeUpdate(SupplierEvaluation entity, SupplierEvaluationUpdateRequest dto) {
        validateAndCalculateScore(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private void validateAndCalculateScore(SupplierEvaluation entity) {
        validateScore("deliveryScore", entity.getDeliveryScore());
        validateScore("qualityScore", entity.getQualityScore());
        validateScore("quantityScore", entity.getQuantityScore());
        validateScore("priceScore", entity.getPriceScore());
        validateScore("claimsScore", entity.getClaimsScore());
        entity.setTotalScore(weighted(entity));
    }

    private void validateScore(String field, BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BadRequestException(field + " must be between 0 and 100");
        }
    }

    protected boolean companyScoped() {
        return true;
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }
}
