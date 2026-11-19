package com.enterprise.mm.service;

import com.enterprise.mm.entity.Bank;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.Classifier;
import com.enterprise.mm.entity.ClassifierType;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.CurrencyRate;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.DepartmentType;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.entity.IkpuCode;
import com.enterprise.mm.entity.Incoterm;
import com.enterprise.mm.entity.Manufacturer;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialAnalog;
import com.enterprise.mm.entity.MaterialConsumptionNorm;
import com.enterprise.mm.entity.MaterialEquipmentCompatibility;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.Position;
import com.enterprise.mm.entity.RfidTag;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import java.util.Map;
import java.util.UUID;

final class ReferenceMappingMetadata {
    private ReferenceMappingMetadata() {
    }

    static Map<String, ReferenceType> referenceTypes() {
        return Map.ofEntries(
            Map.entry("MATERIAL", new ReferenceType("MATERIAL", Material.class, "getArticle")),
            Map.entry("EQUIPMENT", new ReferenceType("EQUIPMENT", Equipment.class, "getExternalEquipmentId")),
            Map.entry("MATERIAL_ANALOG", new ReferenceType("MATERIAL_ANALOG", MaterialAnalog.class, "getAnalogType")),
            Map.entry("MATERIAL_EQUIPMENT_COMPATIBILITY", new ReferenceType("MATERIAL_EQUIPMENT_COMPATIBILITY", MaterialEquipmentCompatibility.class, "getCompatibilityType")),
            Map.entry("MATERIAL_CONSUMPTION_NORM", new ReferenceType("MATERIAL_CONSUMPTION_NORM", MaterialConsumptionNorm.class, "getNormType")),
            Map.entry("MANUFACTURER", new ReferenceType("MANUFACTURER", Manufacturer.class, "getCountry")),
            Map.entry("MATERIAL_GROUP", new ReferenceType("MATERIAL_GROUP", MaterialGroup.class, null)),
            Map.entry("UNIT_OF_MEASURE", new ReferenceType("UNIT_OF_MEASURE", UnitOfMeasure.class, "getCode")),
            Map.entry("WAREHOUSE", new ReferenceType("WAREHOUSE", Warehouse.class, null)),
            Map.entry("STORAGE_LOCATION", new ReferenceType("STORAGE_LOCATION", StorageLocation.class, "getBinCode")),
            Map.entry("STOCK_BATCH", new ReferenceType("STOCK_BATCH", StockBatch.class, "getBatchNumber")),
            Map.entry("CURRENCY", new ReferenceType("CURRENCY", Currency.class, "getIsoCode")),
            Map.entry("CURRENCY_RATE", new ReferenceType("CURRENCY_RATE", CurrencyRate.class, "getCurrencyCode")),
            Map.entry("EXCHANGE_RATE", new ReferenceType("EXCHANGE_RATE", ExchangeRate.class, "getSource")),
            Map.entry("CLASSIFIER", new ReferenceType("CLASSIFIER", Classifier.class, "getClassifierType")),
            Map.entry("CLASSIFIER_TYPE", new ReferenceType("CLASSIFIER_TYPE", ClassifierType.class, null)),
            Map.entry("VALUATION_METHOD", new ReferenceType("VALUATION_METHOD", Classifier.class, null)),
            Map.entry("INCOTERM", new ReferenceType("INCOTERM", Incoterm.class, "getModeScope")),
            Map.entry("IKPU_CODE", new ReferenceType("IKPU_CODE", IkpuCode.class, "getIkpuCode")),
            Map.entry("MXIK_CODE", new ReferenceType("MXIK_CODE", MXIK.class, "getKod")),
            Map.entry("TNVED_CODE", new ReferenceType("TNVED_CODE", TnvedCode.class, "getTnvedCode")),
            Map.entry("BUDGET_ITEM", new ReferenceType("BUDGET_ITEM", BudgetItem.class, null)),
            Map.entry("DEPARTMENT", new ReferenceType("DEPARTMENT", Department.class, "getExternalId")),
            Map.entry("DEPARTMENT_TYPE", new ReferenceType("DEPARTMENT_TYPE", DepartmentType.class, "getLevelCode")),
            Map.entry("POSITION", new ReferenceType("POSITION", Position.class, "getExternalId")),
            Map.entry("EMPLOYEE", new ReferenceType("EMPLOYEE", Employee.class, "getPersonnelNumber")),
            Map.entry("SUPPLIER", new ReferenceType("SUPPLIER", Supplier.class, "getTaxId")),
            Map.entry("BANK", new ReferenceType("BANK", Bank.class, "getMfo")),
            Map.entry("RFID_TAG", new ReferenceType("RFID_TAG", RfidTag.class, "getTagValue"))
        );
    }

    static Map<String, PayloadReferenceField> payloadReferenceFields() {
        return Map.ofEntries(
            Map.entry("materialId", new PayloadReferenceField("MATERIAL", true)),
            Map.entry("materialCode", new PayloadReferenceField("MATERIAL", false)),
            Map.entry("analogMaterialId", new PayloadReferenceField("MATERIAL", true)),
            Map.entry("replacementMaterialId", new PayloadReferenceField("MATERIAL", true)),
            Map.entry("equipmentId", new PayloadReferenceField("EQUIPMENT", true)),
            Map.entry("materialGroupId", new PayloadReferenceField("MATERIAL_GROUP", true)),
            Map.entry("unitOfMeasureId", new PayloadReferenceField("UNIT_OF_MEASURE", true)),
            Map.entry("manufacturer", new PayloadReferenceField("MANUFACTURER", false)),
            Map.entry("warehouseId", new PayloadReferenceField("WAREHOUSE", true)),
            Map.entry("targetWarehouseId", new PayloadReferenceField("WAREHOUSE", true)),
            Map.entry("storageLocationId", new PayloadReferenceField("STORAGE_LOCATION", true)),
            Map.entry("targetStorageLocationId", new PayloadReferenceField("STORAGE_LOCATION", true)),
            Map.entry("stockBatchId", new PayloadReferenceField("STOCK_BATCH", true)),
            Map.entry("currencyId", new PayloadReferenceField("CURRENCY", true)),
            Map.entry("currencyCode", new PayloadReferenceField("CURRENCY", false)),
            Map.entry("baseCurrencyCode", new PayloadReferenceField("CURRENCY", false)),
            Map.entry("exchangeRateId", new PayloadReferenceField("EXCHANGE_RATE", true)),
            Map.entry("exchangeRateRef", new PayloadReferenceField("EXCHANGE_RATE", true)),
            Map.entry("classifierType", new PayloadReferenceField("CLASSIFIER_TYPE", false)),
            Map.entry("valuationMethod", new PayloadReferenceField("VALUATION_METHOD", false)),
            Map.entry("incotermId", new PayloadReferenceField("INCOTERM", true)),
            Map.entry("incotermCode", new PayloadReferenceField("INCOTERM", false)),
            Map.entry("ikpuCodeId", new PayloadReferenceField("IKPU_CODE", true)),
            Map.entry("ikpuCode", new PayloadReferenceField("IKPU_CODE", false)),
            Map.entry("mxikCode", new PayloadReferenceField("MXIK_CODE", false)),
            Map.entry("tnvedCodeId", new PayloadReferenceField("TNVED_CODE", true)),
            Map.entry("tnvedCode", new PayloadReferenceField("TNVED_CODE", false)),
            Map.entry("budgetItemId", new PayloadReferenceField("BUDGET_ITEM", true)),
            Map.entry("departmentId", new PayloadReferenceField("DEPARTMENT", true)),
            Map.entry("parentDepartmentId", new PayloadReferenceField("DEPARTMENT", true)),
            Map.entry("positionId", new PayloadReferenceField("POSITION", true)),
            Map.entry("employeeId", new PayloadReferenceField("EMPLOYEE", true)),
            Map.entry("managerEmployeeId", new PayloadReferenceField("EMPLOYEE", true)),
            Map.entry("supplierId", new PayloadReferenceField("SUPPLIER", true))
        );
    }

    record ReferenceType(String code, Class<?> entityClass, String preferredCodeGetter) {
    }

    record PayloadReferenceField(String referenceEntityType, boolean id) {
    }

    record PayloadReference(String referenceEntityType, UUID referenceId, String referenceCode) {
    }
}
