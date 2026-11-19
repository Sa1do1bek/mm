package com.enterprise.mm.service;

import com.enterprise.mm.payload.PriceAiDtos.PriceAiPricePointDto;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.StockValuation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class PriceAiHistoryCollector {
    @PersistenceContext
    private EntityManager entityManager;
    List<PriceAiPricePointDto> pricePoints(UUID materialId, UUID supplierId, UUID contractId, String currency, int limit) {
        List<PriceAiPricePointDto> points = new ArrayList<>();
        purchaseOrderItems(materialId, limit).forEach(item -> addPurchaseOrderPoint(points, item, supplierId, contractId, currency));
        quotationItems(materialId, limit).forEach(item -> addQuotationPoint(points, item, supplierId, currency));
        purchaseRequestItems(materialId, limit).forEach(item -> addPurchaseRequestPoint(points, item, currency));
        stockValuations(materialId, limit).forEach(valuation -> addValuationPoint(points, valuation, currency));
        return points.stream()
            .filter(point -> point.unitPrice() != null && point.unitPrice().signum() > 0)
            .sorted(Comparator.comparing(PriceAiPricePointDto::documentDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(limit)
            .toList();
    }
    PriceStats stats(List<PriceAiPricePointDto> points) {
        List<BigDecimal> prices = points.stream()
            .map(PriceAiPricePointDto::unitPrice)
            .filter(value -> value != null && value.signum() > 0)
            .toList();
        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(null);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(null);
        BigDecimal avg = prices.isEmpty()
            ? null
            : prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(prices.size()), 4, RoundingMode.HALF_UP);
        BigDecimal lastPurchase = points.stream()
            .filter(point -> "PURCHASE_ORDER".equals(point.source()))
            .max(Comparator.comparing(PriceAiPricePointDto::documentDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(PriceAiPricePointDto::unitPrice)
            .orElse(null);
        BigDecimal quoteMin = points.stream()
            .filter(point -> "SUPPLIER_QUOTATION".equals(point.source()))
            .map(PriceAiPricePointDto::unitPrice)
            .filter(value -> value != null && value.signum() > 0)
            .min(BigDecimal::compareTo)
            .orElse(null);
        return new PriceStats(min, max, avg, lastPurchase, quoteMin);
    }
    private void addPurchaseOrderPoint(List<PriceAiPricePointDto> points, PurchaseOrderItem item, UUID supplierId, UUID contractId, String currency) {
        PurchaseOrder order = findScoped(PurchaseOrder.class, item.getPurchaseOrderId());
        if (order == null || !matches(order.getSupplierId(), supplierId) || !matches(order.getContractId(), contractId)) {
            return;
        }
        Supplier supplier = findScoped(Supplier.class, order.getSupplierId());
        points.add(new PriceAiPricePointDto(
            "PURCHASE_ORDER",
            order.getId(),
            firstNonBlank(order.getCode(), order.getName()),
            order.getSupplierId(),
            supplierName(supplier),
            nvl(item.getUnitPrice()),
            nvl(item.getQuantity()),
            firstNonBlank(order.getCurrencyCode(), currency),
            firstDate(order.getOrderDate(), item.getCreatedAt()),
            firstNonBlank(order.getStatus(), item.getStatus())
        ));
    }
    private void addQuotationPoint(List<PriceAiPricePointDto> points, SupplierQuotationItem item, UUID supplierId, String currency) {
        SupplierQuotation quotation = findScoped(SupplierQuotation.class, item.getQuotationId());
        if (quotation == null || !matches(quotation.getSupplierId(), supplierId)) {
            return;
        }
        Supplier supplier = findScoped(Supplier.class, quotation.getSupplierId());
        points.add(new PriceAiPricePointDto(
            "SUPPLIER_QUOTATION",
            quotation.getId(),
            firstNonBlank(quotation.getCode(), quotation.getName()),
            quotation.getSupplierId(),
            supplierName(supplier),
            nvl(item.getUnitPrice()),
            nvl(item.getQuantity()),
            firstNonBlank(quotation.getCurrencyCode(), currency),
            quotation.getSubmittedAt() == null ? firstDate(null, item.getCreatedAt()) : quotation.getSubmittedAt().toLocalDate(),
            firstNonBlank(quotation.getStatus(), item.getStatus())
        ));
    }
    private void addPurchaseRequestPoint(List<PriceAiPricePointDto> points, PurchaseRequestItem item, String currency) {
        PurchaseRequest request = findScoped(PurchaseRequest.class, item.getPurchaseRequestId());
        if (request == null) {
            return;
        }
        points.add(new PriceAiPricePointDto(
            "PURCHASE_REQUEST_ESTIMATE",
            request.getId(),
            firstNonBlank(request.getCode(), request.getName()),
            null,
            null,
            nvl(item.getEstimatedPrice()),
            nvl(item.getQuantity()),
            firstNonBlank(request.getCurrencyCode(), currency),
            firstDate(request.getRequiredDate(), item.getCreatedAt()),
            firstNonBlank(request.getStatus(), item.getStatus())
        ));
    }
    private void addValuationPoint(List<PriceAiPricePointDto> points, StockValuation valuation, String currency) {
        points.add(new PriceAiPricePointDto(
            "STOCK_VALUATION",
            valuation.getId(),
            firstNonBlank(valuation.getCode(), valuation.getValuationMethod() != null ? valuation.getValuationMethod().name() : null, valuation.getName()),
            null,
            null,
            nvl(valuation.getUnitCost()),
            nvl(valuation.getQuantity()),
            currency,
            valuation.getCalculatedAt() == null ? firstDate(null, valuation.getCreatedAt()) : valuation.getCalculatedAt().toLocalDate(),
            firstNonBlank(valuation.getStatus(), "CALCULATED")
        ));
    }
    private List<PurchaseOrderItem> purchaseOrderItems(UUID materialId, int limit) {
        return entityManager.createQuery("""
            select item from PurchaseOrderItem item
            where item.isDeleted = false
              and item.tenantId = :tenantId
              and item.companyId = :companyId
              and item.materialId = :materialId
            order by item.createdAt desc
            """, PurchaseOrderItem.class)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .setParameter("materialId", materialId)
            .setMaxResults(limit)
            .getResultList();
    }
    private List<SupplierQuotationItem> quotationItems(UUID materialId, int limit) {
        return entityManager.createQuery("""
            select item from SupplierQuotationItem item
            where item.isDeleted = false
              and item.tenantId = :tenantId
              and item.companyId = :companyId
              and item.materialId = :materialId
            order by item.createdAt desc
            """, SupplierQuotationItem.class)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .setParameter("materialId", materialId)
            .setMaxResults(limit)
            .getResultList();
    }
    private List<PurchaseRequestItem> purchaseRequestItems(UUID materialId, int limit) {
        return entityManager.createQuery("""
            select item from PurchaseRequestItem item
            where item.isDeleted = false
              and item.tenantId = :tenantId
              and item.companyId = :companyId
              and item.materialId = :materialId
            order by item.createdAt desc
            """, PurchaseRequestItem.class)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .setParameter("materialId", materialId)
            .setMaxResults(limit)
            .getResultList();
    }
    private List<StockValuation> stockValuations(UUID materialId, int limit) {
        return entityManager.createQuery("""
            select valuation from StockValuation valuation
            where valuation.isDeleted = false
              and valuation.tenantId = :tenantId
              and valuation.companyId = :companyId
              and valuation.materialId = :materialId
            order by valuation.calculatedAt desc, valuation.createdAt desc
            """, StockValuation.class)
            .setParameter("tenantId", TenantContext.currentTenant())
            .setParameter("companyId", CompanyContext.currentCompany())
            .setParameter("materialId", materialId)
            .setMaxResults(limit)
            .getResultList();
    }
    private <T extends BusinessEntity> T findScoped(Class<T> type, UUID id) {
        if (id == null) {
            return null;
        }
        T entity = entityManager.find(type, id);
        return belongsToCurrentScope(entity) ? entity : null;
    }
    private boolean belongsToCurrentScope(BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId());
    }
    private boolean matches(UUID actual, UUID expected) {
        return expected == null || Objects.equals(actual, expected);
    }
    private LocalDate firstDate(LocalDate value, LocalDateTime fallback) {
        return value != null ? value : fallback == null ? null : fallback.toLocalDate();
    }
    private String supplierName(Supplier supplier) {
        return supplier == null ? null : firstNonBlank(supplier.getLegalName(), supplier.getShortName(), supplier.getName(), supplier.getCode());
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
    record PriceStats(
        BigDecimal minHistoricalPrice,
        BigDecimal maxHistoricalPrice,
        BigDecimal avgHistoricalPrice,
        BigDecimal lastPurchasePrice,
        BigDecimal quotationMinPrice
    ) {
    }
}
