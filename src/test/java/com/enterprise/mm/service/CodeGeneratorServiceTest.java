package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.CodeGenerationResponse;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CodeGeneratorServiceTest {
    @Mock
    private JdbcTemplate jdbcTemplate;

    private CodeGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new CodeGeneratorService(jdbcTemplate);
    }

    @ParameterizedTest
    @MethodSource("supportedEntityPrefixes")
    void generateNextCodeReturnsSupportedPrefixAndNextSequence(String entityType, String expectedPrefix) {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of(expectedPrefix + "00007"));

        CodeGenerationResponse response = service.generateNextCode(entityType);

        assertThat(response.entityType()).isEqualTo(entityType);
        assertThat(response.prefix()).isEqualTo(expectedPrefix);
        assertThat(response.code()).isEqualTo(expectedPrefix + "00008");
    }

    @Test
    void generateNextCodeNormalizesInputAndUsesPluralizedTableName() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("PO-00002", "PO-00012", "PO-ABC", "OLD-99999"));

        CodeGenerationResponse response = service.generateNextCode(" purchase-order ");

        assertThat(response.entityType()).isEqualTo("PURCHASE_ORDER");
        assertThat(response.prefix()).isEqualTo("PO-");
        assertThat(response.code()).isEqualTo("PO-00013");
        verify(jdbcTemplate).queryForList("select code from purchase_orders where code like ?", String.class, "PO-%");
    }

    @Test
    void generateNextCodeFallsBackToFirstSequenceWhenTableCannotBeRead() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenThrow(new QueryTimeoutException("metadata unavailable"));

        CodeGenerationResponse response = service.generateNextCode("currency");

        assertThat(response.entityType()).isEqualTo("CURRENCY");
        assertThat(response.prefix()).isEqualTo("CUR-");
        assertThat(response.code()).isEqualTo("CUR-00001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1MATERIAL", "MATERIAL;DROP", "A"})
    void generateNextCodeRejectsInvalidEntityTypeSyntax(String entityType) {
        assertThatThrownBy(() -> service.generateNextCode(entityType))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining(entityType.isBlank() ? "entityType is required" : "Invalid entityType");
    }

    @Test
    void generateNextCodeRejectsUnsupportedEntityType() {
        assertThatThrownBy(() -> service.generateNextCode("NOT_SUPPORTED"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Unsupported entityType: NOT_SUPPORTED");
    }

    private static Stream<Arguments> supportedEntityPrefixes() {
        return Stream.of(
            Arguments.of("ADVANCE_SHIPPING_NOTICE", "ASN-"),
            Arguments.of("ADVANCE_SHIPPING_NOTICE_ITEM", "ASI-"),
            Arguments.of("APPROVAL_STEP", "APS-"),
            Arguments.of("APPROVAL_WORKFLOW", "APW-"),
            Arguments.of("ATTACHMENT", "ATT-"),
            Arguments.of("AUDIT_LOG", "AUD-"),
            Arguments.of("BANK", "BNK-"),
            Arguments.of("BANK_REFERENCE", "BNK-"),
            Arguments.of("BUDGET_ITEM", "BDG-"),
            Arguments.of("CHART_OF_ACCOUNT", "COA-"),
            Arguments.of("CLASSIFIER", "CLS-"),
            Arguments.of("CLASSIFIER_TYPE", "CLT-"),
            Arguments.of("COMPANY", "COM-"),
            Arguments.of("CONSIGNMENT_STOCK_TRANSACTION", "CST-"),
            Arguments.of("CONSOLIDATED_DEMAND", "CND-"),
            Arguments.of("CONTRACT", "CTR-"),
            Arguments.of("CONTRACT_ADDENDUM", "CAD-"),
            Arguments.of("CONTRACT_INVOICE", "CIN-"),
            Arguments.of("CONTRACT_PAYMENT", "CPY-"),
            Arguments.of("CONTRACT_POWER_OF_ATTORNEY", "CPA-"),
            Arguments.of("CONTRACT_RECONCILIATION_ACT", "CRA-"),
            Arguments.of("CONTRACT_SPECIFICATION", "CSP-"),
            Arguments.of("COUNTRY_CODE", "CTY-"),
            Arguments.of("CROSS_DOCKING_TASK", "CDT-"),
            Arguments.of("CURRENCY", "CUR-"),
            Arguments.of("CURRENCY_RATE", "CRT-"),
            Arguments.of("CUSTOMS_DECLARATION", "CUD-"),
            Arguments.of("DATA_MIGRATION_ROW", "DMR-"),
            Arguments.of("DATA_MIGRATION_RUN", "DMN-"),
            Arguments.of("DELIVERY_SCHEDULE", "DSC-"),
            Arguments.of("DEMAND_FORECAST", "DFC-"),
            Arguments.of("DEMAND_PLAN", "DPL-"),
            Arguments.of("DEMAND_PLAN_ITEM", "DPI-"),
            Arguments.of("DEMAND_PLANNING_CYCLE", "DPC-"),
            Arguments.of("DEPARTMENT", "DEP-"),
            Arguments.of("DEPARTMENT_TYPE", "DPT-"),
            Arguments.of("EMPLOYEE", "EMP-"),
            Arguments.of("EQUIPMENT", "EQP-"),
            Arguments.of("EXCHANGE_RATE", "EXR-"),
            Arguments.of("EXTERNAL_INBOUND_RECORD", "EIR-"),
            Arguments.of("IKPU_CODE", "IKP-"),
            Arguments.of("IMPORT_DELIVERY", "IMD-"),
            Arguments.of("IMPORT_FREIGHT_BOOKING", "IFB-"),
            Arguments.of("IMPORT_INSURANCE_POLICY", "IIP-"),
            Arguments.of("INCOTERM", "INC-"),
            Arguments.of("INTEGRATION_EVENT", "INE-"),
            Arguments.of("INTEGRATION_SETTING", "INS-"),
            Arguments.of("INVENTORY_ACCOUNTING_RECONCILIATION", "IAR-"),
            Arguments.of("INVENTORY_DOCUMENT", "IVD-"),
            Arguments.of("INVENTORY_FX_DIFFERENCE", "IFX-"),
            Arguments.of("INVENTORY_ITEM", "IVI-"),
            Arguments.of("INVENTORY_OPTIMIZATION_RECOMMENDATION", "IOR-"),
            Arguments.of("INVENTORY_PERIOD_CLOSE", "IPC-"),
            Arguments.of("LABORATORY_TEST", "LBT-"),
            Arguments.of("LANDED_COST_ALLOCATION", "LCA-"),
            Arguments.of("LANDED_COST_ALLOCATION_LINE", "LCL-"),
            Arguments.of("LANDED_COST_COMPONENT", "LCC-"),
            Arguments.of("LANDED_LOGISTICS_EVENT", "LLE-"),
            Arguments.of("MXIK", "MXK-"),
            Arguments.of("MANUFACTURER", "MNF-"),
            Arguments.of("MATERIAL", "MAT-"),
            Arguments.of("MATERIAL_ANALOG", "MTA-"),
            Arguments.of("MATERIAL_CONSUMPTION_NORM", "MCN-"),
            Arguments.of("MATERIAL_EQUIPMENT_COMPATIBILITY", "MEC-"),
            Arguments.of("MATERIAL_GROUP", "MGR-"),
            Arguments.of("MATERIAL_RESERVATION", "MRS-"),
            Arguments.of("MOBILE_WAREHOUSE_OPERATION", "MWO-"),
            Arguments.of("NON_MOVING_STOCK", "NMS-"),
            Arguments.of("NOTIFICATION", "NTF-"),
            Arguments.of("POSITION", "POS-"),
            Arguments.of("PROCUREMENT_CAMPAIGN", "PCM-"),
            Arguments.of("PROCUREMENT_CATEGORY_STRATEGY", "PCS-"),
            Arguments.of("PROCUREMENT_PLAN", "PPL-"),
            Arguments.of("PRODUCTION_EVIDENCE", "PDE-"),
            Arguments.of("PURCHASE_ORDER", "PO-"),
            Arguments.of("PURCHASE_ORDER_ITEM", "POI-"),
            Arguments.of("PURCHASE_ORDER_REVISION", "POR-"),
            Arguments.of("PURCHASE_REQUEST", "PR-"),
            Arguments.of("PURCHASE_REQUEST_ITEM", "PRI-"),
            Arguments.of("PUTAWAY_RULE", "PTR-"),
            Arguments.of("QUALITY_CLAIM", "QCL-"),
            Arguments.of("QUALITY_INSPECTION", "QIN-"),
            Arguments.of("QUALITY_NONCONFORMANCE", "QNC-"),
            Arguments.of("QUALITY_QUARANTINE_LOT", "QQL-"),
            Arguments.of("RFQ", "RFQ-"),
            Arguments.of("RFQ_ITEM", "RFI-"),
            Arguments.of("REFERENCE_MAPPING", "RMP-"),
            Arguments.of("RFID_TAG", "RFD-"),
            Arguments.of("ROLE", "ROL-"),
            Arguments.of("SEGREGATION_OF_DUTY_RULE", "SDR-"),
            Arguments.of("SEGREGATION_OF_DUTY_VIOLATION", "SDV-"),
            Arguments.of("SERVICE_ACCEPTANCE_ACT", "SAA-"),
            Arguments.of("SERVICE_ORDER", "SOR-"),
            Arguments.of("SMS_GATEWAY_SETTING", "SMS-"),
            Arguments.of("SSO_SETTING", "SSO-"),
            Arguments.of("STOCK_BALANCE", "STB-"),
            Arguments.of("STOCK_BATCH", "SBT-"),
            Arguments.of("STOCK_MOVEMENT", "SMV-"),
            Arguments.of("STOCK_VALUATION", "SVL-"),
            Arguments.of("STOCK_VALUATION_DOCUMENT", "SVD-"),
            Arguments.of("STORAGE_LOCATION", "SLC-"),
            Arguments.of("SUPPLIER", "SUP-"),
            Arguments.of("SUPPLIER_CERTIFICATE", "SPC-"),
            Arguments.of("SUPPLIER_CORRECTIVE_ACTION", "SCA-"),
            Arguments.of("SUPPLIER_EVALUATION", "SEV-"),
            Arguments.of("SUPPLIER_KYC_CHECK", "SKC-"),
            Arguments.of("SUPPLIER_LETTER", "SLT-"),
            Arguments.of("SUPPLIER_LICENSE", "SLI-"),
            Arguments.of("SUPPLIER_PORTAL_ACCOUNT", "SPA-"),
            Arguments.of("SUPPLIER_PORTAL_SUBMISSION", "SPS-"),
            Arguments.of("SUPPLIER_QUOTATION", "SQU-"),
            Arguments.of("SUPPLIER_QUOTATION_ITEM", "SQI-"),
            Arguments.of("SUPPLIER_RELATED_PARTY", "SRP-"),
            Arguments.of("TENDER_BID_OPENING_LINE", "TBL-"),
            Arguments.of("TENDER_BID_OPENING_PROTOCOL", "TBP-"),
            Arguments.of("TENDER_COMMISSION_MEMBER", "TCM-"),
            Arguments.of("TENDER_CONFLICT_CHECK", "TCC-"),
            Arguments.of("TENDER_EVALUATION_PROTOCOL", "TEP-"),
            Arguments.of("TNVED_CODE", "TNV-"),
            Arguments.of("TSD_OFFLINE_OPERATION", "TSD-"),
            Arguments.of("UNIT_OF_MEASURE", "UOM-"),
            Arguments.of("USER", "USR-"),
            Arguments.of("VMI_STOCK_AGREEMENT", "VMI-"),
            Arguments.of("WAREHOUSE", "WH-")
        );
    }
}
