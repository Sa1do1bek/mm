package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.payload.BaseRecordDto;

final class BaseMapper {
    private BaseMapper() {
    }

    static void copy(BusinessEntity entity, BaseRecordDto dto) {
        copyBase(entity, dto);
    }

    static void copyBase(BusinessEntity entity, BaseRecordDto dto) {
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
    }

    static String code(BusinessEntity entity) {
        return entity == null ? null : firstNonBlank(entity.getCode(), entity.getName());
    }

    static String name(BusinessEntity entity) {
        return entity == null ? null : firstNonBlank(entity.getName(), entity.getCode());
    }

    static String materialCode(Material material) {
        return material == null ? null : firstNonBlank(material.getCode(), material.getArticle(), material.getName());
    }

    static String materialName(Material material) {
        return material == null ? null : firstNonBlank(material.getName(), material.getCode(), material.getArticle());
    }

    static String stockBatchCode(StockBatch stockBatch) {
        return stockBatch == null ? null : firstNonBlank(stockBatch.getCode(), stockBatch.getBatchNumber(),
                stockBatch.getSerialNumber(), stockBatch.getName());
    }

    static String stockBatchName(StockBatch stockBatch) {
        return stockBatch == null ? null : firstNonBlank(stockBatch.getName(), stockBatch.getBatchNumber(),
                stockBatch.getSerialNumber(), stockBatch.getCode());
    }

    static String storageBinCode(StorageBin storageBin) {
        return storageBin == null ? null : firstNonBlank(storageBin.getBinCode(), storageBin.getCode(), storageBin.getName());
    }

    static String storageBinName(StorageBin storageBin) {
        return storageBin == null ? null : firstNonBlank(storageBin.getName(), storageBin.getBinCode(), storageBin.getCode());
    }

    static String supplierCode(Supplier supplier) {
        return supplier == null ? null : firstNonBlank(supplier.getCode(), supplier.getShortName(), supplier.getLegalName(),
                supplier.getName());
    }

    static String supplierName(Supplier supplier) {
        return supplier == null ? null : firstNonBlank(supplier.getName(), supplier.getShortName(), supplier.getLegalName(),
                supplier.getCode());
    }

    static String userCode(User user) {
        return user == null ? null : firstNonBlank(user.getUsername(), user.getCode(), user.getEmail(), user.getName());
    }

    static String userName(User user) {
        return user == null ? null : firstNonBlank(user.getFullName(), user.getName(), user.getUsername(), user.getEmail());
    }

    static String employeeCode(Employee employee) {
        return employee == null ? null : firstNonBlank(employee.getPersonnelNumber(), employee.getCode(), employee.getExternalId(),
                employee.getName());
    }

    static String employeeName(Employee employee) {
        return employee == null ? null : firstNonBlank(employee.getName(), employee.getPersonnelNumber(), employee.getCode(),
                employee.getExternalId());
    }

    static String firstNonBlank(String... values) {
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
