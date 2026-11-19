package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.CodeGenerationResponse;
import java.util.List;
import java.util.Locale;

import com.sun.jdi.connect.Connector;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CodeGeneratorService {
    private final JdbcTemplate jdbcTemplate;

    public CodeGeneratorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CodeGenerationResponse generateNextCode(String entityType) {
        String normalizedEntityType = normalizeEntityType(entityType);
        String prefix = getPrefixForEntity(normalizedEntityType);
        int nextNumber = nextNumber(normalizedEntityType, prefix);
        return new CodeGenerationResponse(
            normalizedEntityType,
            prefix,
            prefix + String.format("%05d", nextNumber)
        );
    }

    private String normalizeEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new BadRequestException("entityType is required");
        }
        String normalized = entityType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,79}")) {
            throw new BadRequestException("Invalid entityType");
        }
        return normalized;
    }

    private String getPrefixForEntity(String type) {
        return switch (type) {
            case "ADVANCE_SHIPPING_NOTICE" -> "ASN-";
            case "ADVANCE_SHIPPING_NOTICE_ITEM" -> "ASI-";
            case "APPROVAL_STEP" -> "APS-";
            case "APPROVAL_WORKFLOW" -> "APW-";
            case "ATTACHMENT" -> "ATT-";
            case "AUDIT_LOG" -> "AUD-";
            case "BANK", "BANK_REFERENCE" -> "BNK-";
            case "BUDGET_ITEM" -> "BDG-";
            case "CHART_OF_ACCOUNT" -> "COA-";
            case "CLASSIFIER" -> "CLS-";
            case "CLASSIFIER_TYPE" -> "CLT-";
            case "COMPANY" -> "COM-";
            case "CONSIGNMENT_STOCK_TRANSACTION" -> "CST-";
            case "CONSOLIDATED_DEMAND" -> "CND-";
            case "CONTRACT" -> "CTR-";
            case "CONTRACT_ADDENDUM" -> "CAD-";
            case "CONTRACT_INVOICE" -> "CIN-";
            case "CONTRACT_PAYMENT" -> "CPY-";
            case "CONTRACT_POWER_OF_ATTORNEY" -> "CPA-";
            case "CONTRACT_RECONCILIATION_ACT" -> "CRA-";
            case "CONTRACT_SPECIFICATION" -> "CSP-";
            case "COUNTRY_CODE" -> "CTY-";
            case "CROSS_DOCKING_TASK" -> "CDT-";
            case "CURRENCY" -> "CUR-";
            case "CURRENCY_RATE" -> "CRT-";
            case "CUSTOMS_DECLARATION" -> "CUD-";
            case "DATA_MIGRATION_ROW" -> "DMR-";
            case "DATA_MIGRATION_RUN" -> "DMN-";
            case "DELIVERY_SCHEDULE" -> "DSC-";
            case "DEMAND_FORECAST" -> "DFC-";
            case "DEMAND_PLAN" -> "DPL-";
            case "DEMAND_PLAN_ITEM" -> "DPI-";
            case "DEMAND_PLANNING_CYCLE" -> "DPC-";
            case "DEPARTMENT" -> "DEP-";
            case "DEPARTMENT_TYPE" -> "DPT-";
            case "EMPLOYEE" -> "EMP-";
            case "EQUIPMENT" -> "EQP-";
            case "EXCHANGE_RATE" -> "EXR-";
            case "EXTERNAL_INBOUND_RECORD" -> "EIR-";
            case "IKPU_CODE" -> "IKP-";
            case "IMPORT_DELIVERY" -> "IMD-";
            case "IMPORT_FREIGHT_BOOKING" -> "IFB-";
            case "IMPORT_INSURANCE_POLICY" -> "IIP-";
            case "INCOTERM" -> "INC-";
            case "INTEGRATION_EVENT" -> "INE-";
            case "INTEGRATION_SETTING" -> "INS-";
            case "INVENTORY_ACCOUNTING_RECONCILIATION" -> "IAR-";
            case "INVENTORY_DOCUMENT" -> "IVD-";
            case "INVENTORY_FX_DIFFERENCE" -> "IFX-";
            case "INVENTORY_ITEM" -> "IVI-";
            case "INVENTORY_OPTIMIZATION_RECOMMENDATION" -> "IOR-";
            case "INVENTORY_PERIOD_CLOSE" -> "IPC-";
            case "LABORATORY_TEST" -> "LBT-";
            case "LANDED_COST_ALLOCATION" -> "LCA-";
            case "LANDED_COST_ALLOCATION_LINE" -> "LCL-";
            case "LANDED_COST_COMPONENT" -> "LCC-";
            case "LANDED_LOGISTICS_EVENT" -> "LLE-";
            case "MXIK" -> "MXK-";
            case "MANUFACTURER" -> "MNF-";
            case "MATERIAL" -> "MAT-";
            case "MATERIAL_ANALOG" -> "MTA-";
            case "MATERIAL_CONSUMPTION_NORM" -> "MCN-";
            case "MATERIAL_EQUIPMENT_COMPATIBILITY" -> "MEC-";
            case "MATERIAL_GROUP" -> "MGR-";
            case "MATERIAL_RESERVATION" -> "MRS-";
            case "MOBILE_WAREHOUSE_OPERATION" -> "MWO-";
            case "NON_MOVING_STOCK" -> "NMS-";
            case "NOTIFICATION" -> "NTF-";
            case "POSITION" -> "POS-";
            case "PROCUREMENT_CAMPAIGN" -> "PCM-";
            case "PROCUREMENT_CATEGORY_STRATEGY" -> "PCS-";
            case "PROCUREMENT_PLAN" -> "PPL-";
            case "PRODUCTION_EVIDENCE" -> "PDE-";
            case "PURCHASE_ORDER" -> "PO-";
            case "PURCHASE_ORDER_ITEM" -> "POI-";
            case "PURCHASE_ORDER_REVISION" -> "POR-";
            case "PURCHASE_REQUEST" -> "PR-";
            case "PURCHASE_REQUEST_ITEM" -> "PRI-";
            case "PUTAWAY_RULE" -> "PTR-";
            case "QUALITY_CLAIM" -> "QCL-";
            case "QUALITY_INSPECTION" -> "QIN-";
            case "QUALITY_NONCONFORMANCE" -> "QNC-";
            case "QUALITY_QUARANTINE_LOT" -> "QQL-";
            case "RFQ" -> "RFQ-";
            case "RFQ_ITEM" -> "RFI-";
            case "REFERENCE_MAPPING" -> "RMP-";
            case "RFID_TAG" -> "RFD-";
            case "ROLE" -> "ROL-";
            case "SEGREGATION_OF_DUTY_RULE" -> "SDR-";
            case "SEGREGATION_OF_DUTY_VIOLATION" -> "SDV-";
            case "SERVICE_ACCEPTANCE_ACT" -> "SAA-";
            case "SERVICE_ORDER" -> "SOR-";
            case "SMS_GATEWAY_SETTING" -> "SMS-";
            case "SSO_SETTING" -> "SSO-";
            case "STOCK_BALANCE" -> "STB-";
            case "STOCK_BATCH" -> "SBT-";
            case "STOCK_MOVEMENT" -> "SMV-";
            case "STOCK_VALUATION" -> "SVL-";
            case "STOCK_VALUATION_DOCUMENT" -> "SVD-";
            case "STORAGE_LOCATION" -> "SLC-";
            case "SUPPLIER" -> "SUP-";
            case "SUPPLIER_CERTIFICATE" -> "SPC-";
            case "SUPPLIER_CORRECTIVE_ACTION" -> "SCA-";
            case "SUPPLIER_EVALUATION" -> "SEV-";
            case "SUPPLIER_KYC_CHECK" -> "SKC-";
            case "SUPPLIER_LETTER" -> "SLT-";
            case "SUPPLIER_LICENSE" -> "SLI-";
            case "SUPPLIER_PORTAL_ACCOUNT" -> "SPA-";
            case "SUPPLIER_PORTAL_SUBMISSION" -> "SPS-";
            case "SUPPLIER_QUOTATION" -> "SQU-";
            case "SUPPLIER_QUOTATION_ITEM" -> "SQI-";
            case "SUPPLIER_RELATED_PARTY" -> "SRP-";
            case "TENDER_BID_OPENING_LINE" -> "TBL-";
            case "TENDER_BID_OPENING_PROTOCOL" -> "TBP-";
            case "TENDER_COMMISSION_MEMBER" -> "TCM-";
            case "TENDER_CONFLICT_CHECK" -> "TCC-";
            case "TENDER_EVALUATION_PROTOCOL" -> "TEP-";
            case "TNVED_CODE" -> "TNV-";
            case "TSD_OFFLINE_OPERATION" -> "TSD-";
            case "UNIT_OF_MEASURE" -> "UOM-";
            case "USER" -> "USR-";
            case "VMI_STOCK_AGREEMENT" -> "VMI-";
            case "WAREHOUSE" -> "WH-";
            default -> throw new BadRequestException("Unsupported entityType: " + type);
        };
    }

    private int nextNumber(String entityType, String prefix) {
        String tableName = tableNameForEntity(entityType);
        String sql = "select code from %s where code like ?".formatted(tableName);

        try {
            List<String> existingCodes = jdbcTemplate.queryForList(sql, String.class, prefix + "%");

            int currentMax = existingCodes.stream()
                    .mapToInt(code -> numericSuffix(code, prefix))
                    .max()
                    .orElse(0);

            return currentMax + 1;
        } catch (DataAccessException ex) {
            return 1;
        }
    }

    private int numericSuffix(String code, String prefix){
        if (code == null || !code.startsWith(prefix)){
            return 0;
        }

        String suffix = code.substring(prefix.length());

        if (!suffix.matches("\\d+")){
            return 0;
        }

        try{
            return Integer.parseInt(suffix);
        }catch (NumberFormatException ex) {
            return  0;
        }
    }

    private String tableNameForEntity(String entityType) {
        String singular = entityType.toLowerCase(Locale.ROOT);
        if (singular.endsWith("y") && singular.length() > 1 && !isVowel(singular.charAt(singular.length() - 2))) {
            return singular.substring(0, singular.length() - 1) + "ies";
        }
        if (singular.endsWith("s") || singular.endsWith("x") || singular.endsWith("z")
            || singular.endsWith("ch") || singular.endsWith("sh")) {
            return singular + "es";
        }
        return singular + "s";
    }

    private boolean isVowel(char ch) {
        return ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u';
    }
}
