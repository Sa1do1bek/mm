package com.enterprise.mm.web;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.ConsolidatedDemand;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.Incoterm;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.LandedCostComponent;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.ProcurementCategoryStrategy;
import com.enterprise.mm.entity.ProcurementPlan;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.entity.TenderBidOpeningProtocol;
import com.enterprise.mm.entity.TenderCommissionMember;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ReferenceDisplayEnricher {
    private final EntityManager entityManager;

    private static final Map<String, Class<?>> REFERENCE_TYPES = Map.ofEntries(
        Map.entry("application", SupplierAccreditationApplication.class),
        Map.entry("asn", AdvanceShippingNotice.class),
        Map.entry("budgetItem", BudgetItem.class),
        Map.entry("carrierSupplier", Supplier.class),
        Map.entry("categoryStrategy", ProcurementCategoryStrategy.class),
        Map.entry("commission", Commission.class),
        Map.entry("commissionMember", TenderCommissionMember.class),
        Map.entry("company", Company.class),
        Map.entry("consolidatedDemand", ConsolidatedDemand.class),
        Map.entry("contract", Contract.class),
        Map.entry("correctiveAction", SupplierCorrectiveAction.class),
        Map.entry("demandPlan", DemandPlan.class),
        Map.entry("demandPlanItem", DemandPlanItem.class),
        Map.entry("department", Department.class),
        Map.entry("employee", Employee.class),
        Map.entry("destinationWarehouse", Warehouse.class),
        Map.entry("forwarderSupplier", Supplier.class),
        Map.entry("importDelivery", ImportDelivery.class),
        Map.entry("inspectorEmployee", Employee.class),
        Map.entry("incoterm", Incoterm.class),
        Map.entry("insurerSupplier", Supplier.class),
        Map.entry("inventoryDocument", InventoryDocument.class),
        Map.entry("landedCostComponent", LandedCostComponent.class),
        Map.entry("linkedContract", Contract.class),
        Map.entry("linkedPurchaseOrder", PurchaseOrder.class),
        Map.entry("linkedStockMovement", StockMovement.class),
        Map.entry("linkedSupplier", Supplier.class),
        Map.entry("material", Material.class),
        Map.entry("materialGroup", MaterialGroup.class),
        Map.entry("ownerEmployee", Employee.class),
        Map.entry("parentApplication", SupplierAccreditationApplication.class),
        Map.entry("portalAccount", SupplierPortalAccount.class),
        Map.entry("procurementPlan", ProcurementPlan.class),
        Map.entry("protocol", TenderBidOpeningProtocol.class),
        Map.entry("purchaseOrder", PurchaseOrder.class),
        Map.entry("purchaseOrderItem", PurchaseOrderItem.class),
        Map.entry("purchaseRequest", PurchaseRequest.class),
        Map.entry("purchaseRequestItem", PurchaseRequestItem.class),
        Map.entry("qualityClaim", QualityClaim.class),
        Map.entry("claim", QualityClaim.class),
        Map.entry("qualityInspection", QualityInspection.class),
        Map.entry("qualityNonconformance", QualityNonconformance.class),
        Map.entry("quotation", SupplierQuotation.class),
        Map.entry("recommendedQuotation", SupplierQuotation.class),
        Map.entry("recommendedSupplier", Supplier.class),
        Map.entry("reference", ReferenceMapping.class),
        Map.entry("relatedParty", SupplierRelatedParty.class),
        Map.entry("receiptStorageLocation", StorageLocation.class),
        Map.entry("receiptWarehouse", Warehouse.class),
        Map.entry("replacementMaterial", Material.class),
        Map.entry("responsibleEmployee", Employee.class),
        Map.entry("responsibleUser", User.class),
        Map.entry("rfq", RFQ.class),
        Map.entry("rfqItem", RFQItem.class),
        Map.entry("serviceAct", ServiceAcceptanceAct.class),
        Map.entry("serviceOrder", ServiceOrder.class),
        Map.entry("serviceRequest", ServiceRequest.class),
        Map.entry("stockBatch", StockBatch.class),
        Map.entry("stockMovement", StockMovement.class),
        Map.entry("storageBin", StorageBin.class),
        Map.entry("storageLocation", StorageLocation.class),
        Map.entry("supplier", Supplier.class),
        Map.entry("targetStorageBin", StorageBin.class),
        Map.entry("targetStorageLocation", StorageLocation.class),
        Map.entry("targetWarehouse", Warehouse.class),
        Map.entry("tnvedCode", TnvedCode.class),
        Map.entry("unitOfMeasure", UnitOfMeasure.class),
        Map.entry("user", User.class),
        Map.entry("warehouse", Warehouse.class)
    );

    @Transactional(readOnly = true)
    public void enrich(Object body) {
        enrichValue(body, new IdentityHashMap<>(), new IdentityHashMap<>());
    }

    private void enrichValue(Object value, IdentityHashMap<Object, Boolean> visited, Map<CacheKey, Optional<Object>> cache) {
        if (value == null || isSimpleValue(value)) {
            return;
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (value instanceof ApiResponse<?> response) {
            enrichValue(response.data(), visited, cache);
            return;
        }
        if (value instanceof PageResponse<?> page) {
            page.content().forEach(item -> enrichValue(item, visited, cache));
            return;
        }
        if (value instanceof Page<?> page) {
            page.getContent().forEach(item -> enrichValue(item, visited, cache));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> enrichValue(item, visited, cache));
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                enrichValue(Array.get(value, i), visited, cache);
            }
            return;
        }
        if (!isPayloadValue(value)) {
            return;
        }
        enrichObject(value, cache);
        for (Field field : fields(value.getClass())) {
            Object nested = read(field, value);
            if (nested != value) {
                enrichValue(nested, visited, cache);
            }
        }
    }

    private void enrichObject(Object dto, Map<CacheKey, Optional<Object>> cache) {
        Map<String, Field> fields = fieldMap(dto.getClass());
        for (Map.Entry<String, Class<?>> entry : REFERENCE_TYPES.entrySet()) {
            String prefix = entry.getKey();
            Field idField = fields.get(prefix + "Id");
            Field nameField = fields.get(prefix + "Name");
            if (idField == null || nameField == null || hasText(read(nameField, dto))) {
                continue;
            }
            Object idValue = read(idField, dto);
            if (!(idValue instanceof UUID id)) {
                continue;
            }
            resolve(entry.getValue(), id, cache)
                .map(this::displayName)
                .filter(ReferenceDisplayEnricher::hasText)
                .ifPresent(name -> write(nameField, dto, name));
        }
    }

    private Optional<Object> resolve(
        Class<?> entityType,
        UUID id,
        Map<CacheKey, Optional<Object>> cache
    ) {
        CacheKey key = new CacheKey(entityType, id);
        return cache.computeIfAbsent(key, ignored -> {
            Object entity = entityManager.find(entityType, id);
            if (entity == null || isDeleted(entity) || !sameTenant(entity)) {
                return Optional.empty();
            }
            return Optional.of(entity);
        });
    }

    private boolean sameTenant(Object entity) {
        return !(entity instanceof BusinessEntity businessEntity)
            || TenantContext.currentTenant().equals(businessEntity.getTenantId());
    }

    private boolean isDeleted(Object entity) {
        if (entity instanceof BusinessEntity businessEntity) {
            return businessEntity.isDeleted();
        }
        Object deleted = readNoThrow(entity, "isDeleted");
        return Boolean.TRUE.equals(deleted);
    }

    private String displayName(Object entity) {
        if (entity instanceof Company company) {
            return firstNonBlank(company.getName(), company.getShortName(), company.getLegalName());
        }
        if (entity instanceof Supplier supplier) {
            return firstNonBlank(supplier.getName(), supplier.getShortName(), supplier.getLegalName());
        }
        if (entity instanceof User user) {
            return firstNonBlank(user.getFullName(), user.getName(), user.getUsername(), user.getEmail());
        }
        if (entity instanceof StockBatch stockBatch) {
            return firstNonBlank(stockBatch.getName(), stockBatch.getBatchNumber(), stockBatch.getSerialNumber());
        }
        if (entity instanceof StorageBin storageBin) {
            return firstNonBlank(storageBin.getName(), storageBin.getBinCode());
        }
        if (entity instanceof Employee employee) {
            return firstNonBlank(employee.getName(), employee.getPersonnelNumber());
        }
        if (entity instanceof UnitOfMeasure unitOfMeasure) {
            return firstNonBlank(unitOfMeasure.getName(), unitOfMeasure.getMeasurementNameLong(), unitOfMeasure.getMeasurementNameShort());
        }
        if (entity instanceof BusinessEntity businessEntity) {
            return firstNonBlank(businessEntity.getName());
        }
        return firstNonBlank(String.valueOf(entity));
    }

    private static Object readNoThrow(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static boolean isPayloadValue(Object value) {
        Package pkg = value.getClass().getPackage();
        return pkg != null
            && pkg.getName().startsWith("com.enterprise.mm")
            && !pkg.getName().contains(".entity");
    }

    private static boolean isSimpleValue(Object value) {
        Class<?> type = value.getClass();
        return type.isPrimitive()
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Character
            || value instanceof UUID
            || value instanceof Enum<?>
            || value instanceof Temporal;
    }

    private static Map<String, Field> fieldMap(Class<?> type) {
        Map<String, Field> result = new java.util.HashMap<>();
        for (Field field : fields(type)) {
            result.putIfAbsent(field.getName(), field);
        }
        return result;
    }

    private static List<Field> fields(Class<?> type) {
        java.util.ArrayList<Field> result = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    result.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static void write(Field field, Object target, String value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException ignored) {
            // Best-effort display enrichment; the original response remains valid.
        }
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record CacheKey(Class<?> type, UUID id) {
    }
}
