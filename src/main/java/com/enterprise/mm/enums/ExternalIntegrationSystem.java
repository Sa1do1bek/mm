package com.enterprise.mm.enums;

import com.enterprise.mm.error.BusinessException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum ExternalIntegrationSystem {
    FI("FI / Accounting", "Financial postings for stock, services, valuation and accruals", List.of("STOCK_RECEIPT_POSTED", "STOCK_ISSUE_POSTED", "SERVICE_ACCEPTED", "VALUATION_RECALCULATED")),
    TREASURY("Treasury", "Payment requests, cash plan and payment execution handoff", List.of("PO_SENT", "SERVICE_ACCEPTED", "PAYMENT_REQUEST")),
    BUDGETING("Budgeting", "Budget checks, commitments and consumption control", List.of("PR_SUBMITTED", "PR_APPROVED", "CONTRACT_LIMIT_CONSUMED")),
    PRODUCTION("Production", "Material demand, reservations and production consumption", List.of("MRP_SHORTAGE_DETECTED", "STOCK_ISSUE_POSTED", "MATERIAL_AVAILABLE")),
    HR("HR", "Departments, positions, employees and approval responsibility sync", List.of("ORG_STRUCTURE_SYNCED", "SYNC_REQUESTED", "DEPARTMENT_SYNC", "POSITION_SYNC", "EMPLOYEE_SYNC", "APPROVER_SYNC")),
    EDO("EDO", "Electronic document exchange for contracts, PO and acceptance acts", List.of("CONTRACT_ACTIVE", "PO_SENT", "SERVICE_ACT_ACCEPTED")),
    E_IMZO("E-IMZO", "Digital signature verification and signed document envelope exchange", List.of("SIGNATURE_VERIFY", "DOCUMENT_SIGNED")),
    ETTN("ETTN", "Electronic transport consignment and goods movement exchange", List.of("GOODS_RECEIPT", "TRANSPORT_DOCUMENT")),
    SOLIQ("Soliq Service", "Soliq Service API for electronic tax invoices, taxpayer status and fiscal documents", List.of("TAX_INVOICE", "SUPPLIER_TAX_STATUS", "DOCUMENT_REGISTER")),
    DIDOX("Didox", "Didox EDI API for invoices, contracts, acts and incoming/outgoing document exchange", List.of("DOCUMENT_REGISTER", "TAX_INVOICE", "INCOMING_DOCUMENTS", "SUPPLIER_STATUS")),
    FAKTURA_UZ("Faktura.uz", "Faktura.uz API for electronic invoice and document register import", List.of("IMPORT_DOCUMENT", "TAX_INVOICE", "DOCUMENT_REGISTER", "SUPPLIER_STATUS")),
    CHATGPT("ChatGPT / OpenAI", "OpenAI API for procurement assistant, document analysis and ERP recommendations", List.of("CHAT_COMPLETION", "DOCUMENT_SUMMARY", "PROCUREMENT_ASSISTANT")),
    CLAUDE("Claude", "Anthropic Claude API for document reasoning, supplier analysis and contract review", List.of("CHAT_COMPLETION", "DOCUMENT_SUMMARY", "CONTRACT_REVIEW")),
    GEMINI("Gemini", "Google Gemini API for multimodal assistance, document analysis and operational recommendations", List.of("GENERATE_CONTENT", "DOCUMENT_SUMMARY", "PROCUREMENT_ASSISTANT")),
    EXCHANGE("Exchange", "Commodity exchange RFQ, bid and quotation handoff", List.of("RFQ_PUBLISHED", "QUOTATION_RECEIVED")),
    ERP("ERP Integration Core", "Canonical material master delivery to the ERP Integration Core", List.of("MATERIAL_MASTER_CHANGED")),
    WMS("External WMS", "Warehouse tasks, locations, barcode operations and stock sync", List.of("RECEIPT_TASK", "TRANSFER_TASK", "STOCK_BALANCE_SYNC")),
    SCADA("SCADA", "Industrial process consumption and sensor-driven stock events", List.of("CONSUMPTION_SIGNAL", "PRODUCTION_RECEIPT")),
    LIMS("LIMS", "Laboratory information management system for QA test requests and results", List.of("LIMS_LAB_TEST_REQUESTED", "LIMS_LAB_RESULT_RECEIVED")),
    ONE_C("1C", "Enterprise accounting and inventory exchange with 1C", List.of("MASTER_DATA_SYNC", "STOCK_MOVEMENT_SYNC", "VALUATION_SYNC")),
    KIMYOSANOAT("Kimyosanoat", "Exit-weighed shipment documents", List.of("EXIT_WEIGHED_ISSUES"));

    private final String displayName;
    private final String description;
    private final List<String> capabilities;

    ExternalIntegrationSystem(String displayName, String description, List<String> capabilities) {
        this.displayName = displayName;
        this.description = description;
        this.capabilities = capabilities;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public List<String> capabilities() {
        return capabilities;
    }

    public static ExternalIntegrationSystem from(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Arrays.stream(values())
            .filter(system -> system.name().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Unknown integration system: " + raw));
    }
}
