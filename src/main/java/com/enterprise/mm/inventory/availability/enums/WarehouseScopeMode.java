package com.enterprise.mm.inventory.availability.enums;

public enum WarehouseScopeMode {
    WAREHOUSE_SPECIFIC, // (purchase_request_items) aynan shu omborga tegishli

    COMPANY_LEVEL_DEMAND, // no filter at all

    MIXED // bazi demand hujjatlarda warehuse umuman korsatilmagan
}
