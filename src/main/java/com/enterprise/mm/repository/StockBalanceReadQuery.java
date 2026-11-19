package com.enterprise.mm.repository;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.StockExpiryStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;

/** Shared SQL filtering and ordering for the balance table and complete XLSX export. */
@Repository @RequiredArgsConstructor
public class StockBalanceReadQuery {
    private final EntityManager em;
    private static final Set<String> SORT_FIELDS = Set.of("daysUntilExpiry", "expirationDate", "expiryStatus", "materialCode",
        "materialName", "materialGroupCode", "materialGroupName", "warehouseCode", "warehouseName", "storageLocationName",
        "storageBinName", "stockBatchNumber", "quantity", "availableQuantity", "reservedQuantity", "quarantineQuantity",
        "totalValue", "receivedDate", "manufacturedOn", "balanceAt", "createdAt", "unitCode", "unitLabel", "manufacturerBatchNumber", "id");

    public void validate(StockBalanceSearchRequest r, LocalDate today) {
        var o = options(r);
        if (o.getExpiresWithinDays() != null) {
            if (o.getExpiresWithinDays() < 0) throw new BadRequestException("expiresWithinDays must be nonnegative");
            try { today.plusDays(o.getExpiresWithinDays()); }
            catch (java.time.DateTimeException ex) { throw new BadRequestException("Expiry horizon is outside supported date range"); }
            if (o.getExpiresWithinDays() > 365000) throw new BadRequestException("expiresWithinDays must not exceed 365000");
        }
        if (o.getExpiryDateFrom() != null && o.getExpiryDateTo() != null && o.getExpiryDateFrom().isAfter(o.getExpiryDateTo()))
            throw new BadRequestException("expiryDateFrom must not exceed expiryDateTo");
        range(o.getQuantityMin(), o.getQuantityMax(), "quantity");
        range(o.getAvailableQuantityMin(), o.getAvailableQuantityMax(), "availableQuantity");
        range(o.getTotalValueMin(), o.getTotalValueMax(), "totalValue");
        if (r.expiryStatus() != null && r.expiryStatus().stream().anyMatch(Objects::isNull)) throw new BadRequestException("expiryStatus contains an empty value");
        if (o.getSortBy() != null && !SORT_FIELDS.contains(o.getSortBy())) throw new BadRequestException("Unsupported sortBy: " + o.getSortBy());
        if (o.getSortDir() != null && !Set.of("ASC", "DESC").contains(o.getSortDir().toUpperCase(Locale.ROOT)))
            throw new BadRequestException("sortDir must be ASC or DESC");
    }
    private void range(BigDecimal min, BigDecimal max, String field) {
        if (min != null && max != null && min.compareTo(max) > 0) throw new BadRequestException(field + "Min must not exceed Max");
    }
    public Page<StockBalance> search(String tenant, UUID company, SearchCriteria scope, StockBalanceSearchRequest r, LocalDate today, Pageable page) {
        validate(r, today);
        var cb = em.getCriteriaBuilder(); var cq = cb.createQuery(StockBalance.class); var root = cq.from(StockBalance.class);
        var joins = joins(root); fetch(root);
        cq.select(root).where(predicates(cb, root, joins, tenant, company, scope, r, today));
        cq.orderBy(orders(cb, root, joins, r, page.getSort(), today));
        var query = em.createQuery(cq);
        if (page.isPaged()) { query.setFirstResult(Math.toIntExact(page.getOffset())); query.setMaxResults(page.getPageSize()); }
        List<StockBalance> rows = query.getResultList();
        var count = cb.createQuery(Long.class); var cr = count.from(StockBalance.class);
        count.select(cb.count(cr)).where(predicates(cb, cr, joins(cr), tenant, company, scope, r, today));
        long total = em.createQuery(count).getSingleResult();
        return new PageImpl<>(rows, page, total);
    }
    /** Complete address-level counts use the same scope and predicates as stock list/detail reads. */
    public List<WarehouseLayoutStockDtos.LocationStock> layoutLocations(String tenant, UUID company,
        SearchCriteria scope, StockBalanceSearchRequest request, LocalDate today) {
        validate(request, today);
        var cb = em.getCriteriaBuilder(); var cq = cb.createTupleQuery(); var root = cq.from(StockBalance.class);
        var j = joins(root);
        var location = root.<UUID>get("storageLocationId"); var bin = root.<UUID>get("storageBinId");
        var locationName = j.location.<String>get("name"); var binName = j.bin.<String>get("name");
        var description = j.location.<String>get("description");
        cq.multiselect(location, locationName, bin, binName, cb.count(root), description,
            cb.greatest(root.<java.time.LocalDateTime>get("balanceAt")))
            .where(predicates(cb, root, j, tenant, company, scope, request, today))
            .groupBy(location, locationName, bin, binName, description);
        return em.createQuery(cq).getResultList().stream().map(row -> {
            UUID locationId = row.get(0, UUID.class), binId = row.get(2, UUID.class);
            String source = row.get(5, String.class);
            var precision = binId != null ? WarehouseLayoutStockDtos.Precision.BIN
                : locationId == null || (source != null && source.startsWith("WarehouseAggregate:"))
                    ? WarehouseLayoutStockDtos.Precision.WAREHOUSE : WarehouseLayoutStockDtos.Precision.LOCATION;
            return new WarehouseLayoutStockDtos.LocationStock(locationId, row.get(1, String.class),
                binId, row.get(3, String.class), row.get(4, Long.class), precision, row.get(6, java.time.LocalDateTime.class));
        }).toList();
    }
    public void stream(String tenant, UUID company, SearchCriteria scope, StockBalanceSearchRequest r, LocalDate today, Consumer<StockBalance> action) {
        validate(r, today);
        var cb = em.getCriteriaBuilder(); var cq = cb.createQuery(StockBalance.class); var root = cq.from(StockBalance.class);
        var joins = joins(root); fetch(root);
        cq.select(root).where(predicates(cb, root, joins, tenant, company, scope, r, today));
        cq.orderBy(orders(cb, root, joins, r, Sort.unsorted(), today));
        var query = em.createQuery(cq).setHint("org.hibernate.fetchSize", 500);
        int[] count = {0};
        try (var results = query.getResultStream()) {
            results.forEach(row -> { action.accept(row); if (++count[0] % 500 == 0) em.clear(); });
        }
    }
    private record Joins(Join<?,?> material, Join<?,?> group, Join<?,?> batch, Join<?,?> warehouse, Join<?,?> location, Join<?,?> bin, Join<?,?> unit) {}
    private Joins joins(Root<StockBalance> e) {
        var m = e.join("material", JoinType.LEFT);
        return new Joins(m, m.join("materialGroup", JoinType.LEFT), e.join("stockBatch", JoinType.LEFT),
            e.join("warehouse", JoinType.LEFT), e.join("storageLocation", JoinType.LEFT), e.join("storageBin", JoinType.LEFT), m.join("unitOfMeasure", JoinType.LEFT));
    }
    private void fetch(Root<StockBalance> e) {
        var m = e.fetch("material", JoinType.LEFT); m.fetch("materialGroup", JoinType.LEFT); m.fetch("unitOfMeasure", JoinType.LEFT);
        e.fetch("stockBatch", JoinType.LEFT); e.fetch("warehouse", JoinType.LEFT);
        e.fetch("storageLocation", JoinType.LEFT); e.fetch("storageBin", JoinType.LEFT);
    }
    private Expression<LocalDate> expiry(CriteriaBuilder cb, Joins j) {
        return cb.<LocalDate>coalesce().value(j.batch.get("expiresOn")).value(j.batch.get("expirationDate"));
    }
    private Predicate[] predicates(CriteriaBuilder cb, Root<StockBalance> e, Joins j, String tenant, UUID company,
        SearchCriteria scope, StockBalanceSearchRequest r, LocalDate today) {
        List<Predicate> p = new ArrayList<>(); var o = options(r); var expiry = expiry(cb, j);
        p.add(cb.equal(e.get("tenantId"), tenant)); eq(cb,p,e.get("companyId"),company);
        if (!scope.includeDeleted()) p.add(cb.isFalse(e.get("isDeleted")));
        if (scope.hasWarehouseIds()) p.add(e.get("warehouseId").in(scope.warehouseIds()));
        else eq(cb,p,e.get("warehouseId"),scope.warehouseId());
        eq(cb,p,e.get("stockBatchId"),r.stockBatchId());
        eq(cb,p,j.material.get("materialGroupId"),r.materialGroupId());
        if (scope.hasMaterialIds()) p.add(e.get("materialId").in(scope.materialIds()));
        if (scope.hasStatus()) p.add(e.get("status").in(scope.status()));
        if (scope.fromDateTime()!=null) p.add(cb.greaterThanOrEqualTo(e.get("createdAt"),scope.fromDateTime()));
        if (scope.toDateTime()!=null) p.add(cb.lessThanOrEqualTo(e.get("createdAt"),scope.toDateTime()));
        eq(cb,p,e.get("id"),r.id()); eq(cb,p,e.get("isDeleted"),r.deleted()); eq(cb,p,e.get("version"),r.version());
        eq(cb,p,e.get("materialId"),r.materialId()); eq(cb,p,e.get("storageLocationId"),r.storageLocationId()); eq(cb,p,e.get("storageBinId"),r.storageBinId());
        eq(cb,p,e.get("quantity"),r.quantity()); eq(cb,p,e.get("availableQuantity"),r.availableQuantity()); eq(cb,p,e.get("reservedQuantity"),r.reservedQuantity());
        eq(cb,p,e.get("quarantineQuantity"),r.quarantineQuantity()); eq(cb,p,e.get("totalValue"),r.totalValue()); eq(cb,p,e.get("balanceAt"),r.balanceAt());
        like(cb,p,e.get("code"),r.code()); like(cb,p,e.get("name"),r.name()); like(cb,p,e.get("description"),r.description());
        like(cb,p,e.get("createdBy"),r.createdBy()); like(cb,p,e.get("updatedBy"),r.updatedBy());
        if (r.search()!=null && !r.search().isBlank()) {
            String pattern = "%"+r.search().trim().toLowerCase(Locale.ROOT)+"%";
            p.add(cb.or(cb.like(cb.lower(e.get("code")),pattern),cb.like(cb.lower(e.get("name")),pattern),
                cb.like(cb.lower(j.material.get("code")),pattern),cb.like(cb.lower(j.material.get("name")),pattern),cb.like(cb.lower(j.batch.get("batchNumber")),pattern)));
        }
        Predicate positive = cb.greaterThan(e.get("quantity"), BigDecimal.ZERO);
        Predicate warning = cb.and(positive,cb.lessThanOrEqualTo(expiry,today.plusDays(30)));
        if (r.expiryWarning()!=null) p.add(r.expiryWarning() ? warning : cb.or(cb.isNull(expiry),cb.not(warning)));
        if (r.expiryStatus()!=null && !r.expiryStatus().isEmpty()) {
            List<Predicate> statuses = new ArrayList<>();
            for (var status : r.expiryStatus()) statuses.add(switch(status) {
                case UNKNOWN -> cb.isNull(expiry); case EXPIRED -> cb.lessThan(expiry,today);
                case EXPIRES_TODAY -> cb.equal(expiry,today);
                case EXPIRING_SOON -> cb.and(cb.greaterThan(expiry,today),cb.lessThanOrEqualTo(expiry,today.plusDays(30)));
                case VALID -> cb.greaterThan(expiry,today.plusDays(30));
            });
            p.add(cb.and(positive,cb.or(statuses.toArray(Predicate[]::new))));
        }
        if (o.getExpiresWithinDays()!=null) {
            p.add(positive); p.add(cb.lessThanOrEqualTo(expiry,today.plusDays(o.getExpiresWithinDays())));
            if (!o.isIncludeExpired()) p.add(cb.greaterThanOrEqualTo(expiry,today));
        }
        if (Boolean.TRUE.equals(o.getPositiveOnly())) p.add(positive);
        if (o.getExpiryDateFrom()!=null) p.add(cb.greaterThanOrEqualTo(expiry,o.getExpiryDateFrom()));
        if (o.getExpiryDateTo()!=null) p.add(cb.lessThanOrEqualTo(expiry,o.getExpiryDateTo()));
        bounds(cb,p,e.get("quantity"),o.getQuantityMin(),o.getQuantityMax()); bounds(cb,p,e.get("availableQuantity"),o.getAvailableQuantityMin(),o.getAvailableQuantityMax());
        bounds(cb,p,e.get("totalValue"),o.getTotalValueMin(),o.getTotalValueMax());
        if (o.getHasReserved()!=null) p.add(o.getHasReserved()?cb.greaterThan(e.get("reservedQuantity"),BigDecimal.ZERO):cb.lessThanOrEqualTo(e.get("reservedQuantity"),BigDecimal.ZERO));
        if (o.getHasQuarantine()!=null) p.add(o.getHasQuarantine()?cb.greaterThan(e.get("quarantineQuantity"),BigDecimal.ZERO):cb.lessThanOrEqualTo(e.get("quarantineQuantity"),BigDecimal.ZERO));
        eq(cb,p,j.material.get("unitOfMeasureId"),o.getUnitOfMeasureId());
        return p.toArray(Predicate[]::new);
    }
    private List<Order> orders(CriteriaBuilder cb, Root<StockBalance> e, Joins j, StockBalanceSearchRequest r, Sort fallback, LocalDate today) {
        var o=options(r); List<Sort.Order> requested = new ArrayList<>();
        if (o.getSortBy()!=null) requested.add(new Sort.Order(o.getSortDir()==null?Sort.Direction.ASC:Sort.Direction.fromString(o.getSortDir()),o.getSortBy()));
        else if (fallback.isSorted()) fallback.forEach(requested::add);
        else requested.add(new Sort.Order(o.getSortDir()==null?Sort.Direction.ASC:Sort.Direction.fromString(o.getSortDir()),"daysUntilExpiry"));
        List<Order> result=new ArrayList<>();
        for(var order:requested) {
            if(!SORT_FIELDS.contains(order.getProperty())) throw new BadRequestException("Unsupported sort field: "+order.getProperty());
            Expression<?> value=switch(order.getProperty()) {
                case "daysUntilExpiry", "expirationDate" -> expiry(cb,j);
                case "expiryStatus" -> cb.<Integer>selectCase().when(cb.isNull(expiry(cb,j)),4).when(cb.lessThan(expiry(cb,j),today),0)
                    .when(cb.equal(expiry(cb,j),today),1).when(cb.lessThanOrEqualTo(expiry(cb,j),today.plusDays(30)),2).otherwise(3);
                case "materialCode" -> j.material.get("code"); case "materialName" -> localizedName(cb, j.material);
                case "materialGroupCode" -> j.group.get("code"); case "materialGroupName" -> localizedName(cb, j.group);
                case "warehouseCode" -> j.warehouse.get("code"); case "warehouseName" -> localizedName(cb, j.warehouse);
                case "storageLocationName" -> localizedName(cb, j.location); case "storageBinName" -> localizedName(cb, j.bin);
                case "unitCode", "unitLabel" -> localizedUnit(cb,j.unit);
                case "manufacturerBatchNumber" -> j.batch.get("manufacturerBatchNumber");
                case "stockBatchNumber" -> j.batch.get("batchNumber"); case "receivedDate", "manufacturedOn" -> j.batch.get(order.getProperty());
                default -> e.get(order.getProperty());
            };
            result.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(value),1).otherwise(0)));
            result.add(order.isAscending()?cb.asc(value):cb.desc(value));
        }
        result.add(cb.asc(e.get("id"))); return result;
    }
    private Expression<String> localizedName(CriteriaBuilder cb, Join<?,?> owner) {
        String language = org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage();
        return cb.function("multilingual_name_value", String.class, owner.get("name"), cb.literal(language));
    }
    private Expression<String> localizedUnit(CriteriaBuilder cb,Join<?,?> unit) {
        String language=org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage();
        String preferred=switch(language) {case "uz"->"measurementNameShortUz";case "ru"->"measurementNameShortRu";default->"measurementNameShort";};
        return cb.<String>coalesce()
            .value(cb.nullif(cb.trim(unit.get(preferred)),""))
            .value(cb.nullif(cb.trim(unit.get("measurementNameShort")),""))
            .value(cb.nullif(cb.trim(unit.get("measurementNameShortRu")),""))
            .value(cb.nullif(cb.trim(unit.get("measurementNameShortUz")),""))
            .value(localizedName(cb,unit));
    }
    private StockBalanceViewOptions options(StockBalanceSearchRequest r) { return r.options()==null?new StockBalanceViewOptions():r.options(); }
    private void eq(CriteriaBuilder cb,List<Predicate> p,Expression<?> field,Object value) { if(value!=null)p.add(cb.equal(field,value)); }
    private void like(CriteriaBuilder cb,List<Predicate> p,Expression<String> field,String value) { if(value!=null&&!value.isBlank())p.add(cb.like(cb.lower(field),"%"+value.toLowerCase(Locale.ROOT)+"%")); }
    private void bounds(CriteriaBuilder cb,List<Predicate> p,Expression<BigDecimal> field,BigDecimal min,BigDecimal max) {
        if(min!=null)p.add(cb.greaterThanOrEqualTo(field,min)); if(max!=null)p.add(cb.lessThanOrEqualTo(field,max));
    }
}
