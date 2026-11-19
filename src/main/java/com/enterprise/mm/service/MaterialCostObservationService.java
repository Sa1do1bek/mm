package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialCostObservation;
import com.enterprise.mm.enums.MaterialCostObservationSourceType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.MaterialCostObservationResponse;
import com.enterprise.mm.repository.MaterialCostObservationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialCostObservationService {
    private final MaterialCostObservationRepository repository;
    private final MaterialRepository materialRepository;

    @Transactional(readOnly = true)
    public Page<MaterialCostObservationResponse> candidates(
        String mxikCode,
        UUID materialId,
        String currencyCode,
        Pageable pageable
    ) {
        return repository.findCandidates(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            normalizeCode(mxikCode),
            materialId,
            normalizeCurrency(currencyCode),
            pageable
        ).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialCostObservation requireSelectable(UUID id, String mxikCode) {
        MaterialCostObservation observation = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Material cost observation not found: " + id));
        if (!TenantContext.currentTenant().equals(observation.getTenantId())
            || !CompanyContext.currentCompany().equals(observation.getCompanyId())) {
            throw new NotFoundException("Material cost observation not found: " + id);
        }
        String normalizedMxik = normalizeCode(mxikCode);
        if (normalizedMxik != null && !Objects.equals(normalizedMxik, observation.getMxikCode())) {
            throw new BadRequestException("Selected cost observation MXIK does not match material MXIK", "material.average_cost.mxik_mismatch");
        }
        return observation;
    }

    @Transactional
    public void recordPurchaseRequest(UUID materialId, BigDecimal quantity, BigDecimal unitCost, String currencyCode, UUID sourceEntityId, UUID sourceLineId, String documentCode, LocalDateTime observedAt) {
        record(materialId, quantity, unitCost, currencyCode, MaterialCostObservationSourceType.PURCHASE_REQUEST, sourceEntityId, sourceLineId, documentCode, null, observedAt);
    }

    @Transactional
    public void recordPurchaseOrder(UUID materialId, BigDecimal quantity, BigDecimal unitCost, String currencyCode, UUID sourceEntityId, UUID sourceLineId, String documentCode, UUID supplierId, LocalDate observedAt) {
        record(materialId, quantity, unitCost, currencyCode, MaterialCostObservationSourceType.PURCHASE_ORDER, sourceEntityId, sourceLineId, documentCode, supplierId, dateTime(observedAt));
    }

    @Transactional
    public void recordSupplierQuotation(UUID materialId, BigDecimal quantity, BigDecimal unitCost, String currencyCode, UUID sourceEntityId, UUID sourceLineId, String documentCode, UUID supplierId, LocalDateTime observedAt) {
        record(materialId, quantity, unitCost, currencyCode, MaterialCostObservationSourceType.SUPPLIER_QUOTATION, sourceEntityId, sourceLineId, documentCode, supplierId, observedAt);
    }

    @Transactional
    public void recordReceipt(UUID materialId, BigDecimal quantity, BigDecimal unitCost, String currencyCode, UUID sourceLineId, String documentCode, LocalDateTime observedAt) {
        record(materialId, quantity, unitCost, currencyCode, MaterialCostObservationSourceType.RECEIPT, sourceLineId, sourceLineId, documentCode, null, observedAt);
    }

    @Transactional
    public void recordMarketPrice(UUID materialId, BigDecimal unitCost, String currencyCode, UUID sourceLineId, String documentCode, LocalDate observedAt) {
        record(materialId, null, unitCost, currencyCode, MaterialCostObservationSourceType.MARKET_PRICE, sourceLineId, sourceLineId, documentCode, null, dateTime(observedAt));
    }

    private void record(
        UUID materialId,
        BigDecimal quantity,
        BigDecimal unitCost,
        String currencyCode,
        MaterialCostObservationSourceType sourceType,
        UUID sourceEntityId,
        UUID sourceLineId,
        String documentCode,
        UUID supplierId,
        LocalDateTime observedAt
    ) {
        if (materialId == null || sourceLineId == null || nvl(unitCost).signum() <= 0) {
            return;
        }
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .filter(candidate -> CompanyContext.currentCompany().equals(candidate.getCompanyId()))
            .orElse(null);
        if (material == null || material.getMxikCode() == null || material.getMxikCode().isBlank()) {
            return;
        }
        String normalizedSourceType = sourceType.name();
        MaterialCostObservation observation = repository
            .findByTenantIdAndCompanyIdAndSourceTypeAndSourceLineIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                normalizedSourceType,
                sourceLineId
            )
            .orElseGet(MaterialCostObservation::new);
        observation.setCompanyId(CompanyContext.currentCompany());
        observation.setCode(normalizedSourceType + "-" + sourceLineId.toString().substring(0, 8).toUpperCase(Locale.ROOT));
        observation.setName("Observed material cost");
        observation.setStatus("ACTIVE");
        observation.setMaterialId(materialId);
        observation.setMxikCode(normalizeCode(material.getMxikCode()));
        observation.setUnitCost(nvl(unitCost));
        observation.setCurrencyCode(Objects.requireNonNullElse(normalizeCurrency(currencyCode), "UZS"));
        observation.setQuantity(quantity);
        observation.setSourceType(normalizedSourceType);
        observation.setSourceEntityId(sourceEntityId);
        observation.setSourceLineId(sourceLineId);
        observation.setDocumentCode(trim(documentCode));
        observation.setSupplierId(supplierId);
        observation.setObservedAt(observedAt == null ? LocalDateTime.now() : observedAt);
        repository.save(observation);
    }

    private MaterialCostObservationResponse toResponse(MaterialCostObservation entity) {
        MaterialCostObservationResponse response = new MaterialCostObservationResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setMaterialId(entity.getMaterialId());
        response.setMaterialCode(materialCode(entity.getMaterial()));
        response.setMaterialName(materialName(entity.getMaterial()));
        response.setMxikCode(entity.getMxikCode());
        response.setUnitCost(entity.getUnitCost());
        response.setCurrencyCode(entity.getCurrencyCode());
        response.setQuantity(entity.getQuantity());
        response.setSourceType(entity.getSourceType());
        response.setSourceEntityId(entity.getSourceEntityId());
        response.setSourceLineId(entity.getSourceLineId());
        response.setDocumentCode(entity.getDocumentCode());
        response.setSupplierId(entity.getSupplierId());
        response.setObservedAt(entity.getObservedAt());
        return response;
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.length() == 3 ? normalized : null;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private LocalDateTime dateTime(LocalDate value) {
        return value == null ? LocalDateTime.now() : value.atStartOfDay();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String materialCode(Material material) {
        return material == null ? null : firstNonBlank(material.getCode(), material.getArticle(), material.getName());
    }

    private String materialName(Material material) {
        return material == null ? null : firstNonBlank(material.getName(), material.getCode(), material.getArticle());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
