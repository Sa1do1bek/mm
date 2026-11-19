package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationBatchRequest;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationPushResult;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationRequest;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.service.EnterpriseIntegrationApiService;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class    EnterpriseIntegrationApiController {
    private final EnterpriseIntegrationApiService service;

    @PostMapping("/accounting/stock-receipts")
    public ApiResponse<IntegrationEventResponse> accountingStockReceipt(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "STOCK_RECEIPT_POSTED", request, idempotencyKey);
    }

    @PostMapping("/accounting/stock-issues")
    public ApiResponse<IntegrationEventResponse> accountingStockIssue(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "STOCK_ISSUE_POSTED", request, idempotencyKey);
    }

    @PostMapping("/accounting/stock-transfers")
    public ApiResponse<IntegrationEventResponse> accountingStockTransfer(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "STOCK_TRANSFER_POSTED", request, idempotencyKey);
    }

    @PostMapping("/accounting/inventory-adjustments")
    public ApiResponse<IntegrationEventResponse> accountingInventoryAdjustment(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "INVENTORY_ADJUSTMENT_POSTED", request, idempotencyKey);
    }

    @PostMapping("/accounting/service-acceptances")
    public ApiResponse<IntegrationEventResponse> accountingServiceAcceptance(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "SERVICE_ACCEPTED", request, idempotencyKey);
    }

    @PostMapping("/accounting/vendor-invoices")
    public ApiResponse<IntegrationEventResponse> accountingVendorInvoice(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "VENDOR_INVOICE_REGISTERED", request, idempotencyKey);
    }

    @PostMapping("/accounting/valuation-postings")
    public ApiResponse<IntegrationEventResponse> accountingValuationPosting(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "VALUATION_RECALCULATED", request, idempotencyKey);
    }

    @PostMapping("/accounting/accruals")
    public ApiResponse<IntegrationEventResponse> accountingAccrual(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.FI, "ACCRUAL_REQUIRED", request, idempotencyKey);
    }

    @PostMapping("/accounting/batch")
    public ApiResponse<EnterpriseIntegrationPushResult> accountingBatch(@Valid @RequestBody EnterpriseIntegrationBatchRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return batch(ExternalIntegrationSystem.FI, "ACCOUNTING_POSTING", request, idempotencyKey);
    }

    @PostMapping("/treasury/payment-requests")
    public ApiResponse<IntegrationEventResponse> treasuryPaymentRequest(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.TREASURY, "PAYMENT_REQUEST", request, idempotencyKey);
    }

    @PostMapping("/treasury/payment-cancellations")
    public ApiResponse<IntegrationEventResponse> treasuryPaymentCancellation(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.TREASURY, "PAYMENT_CANCELLED", request, idempotencyKey);
    }

    @PostMapping("/treasury/payment-status")
    public ApiResponse<IntegrationEventResponse> treasuryPaymentStatus(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.TREASURY, "PAYMENT_STATUS_RECEIVED", request, idempotencyKey);
    }

    @PostMapping("/treasury/po-commitments")
    public ApiResponse<IntegrationEventResponse> treasuryPoCommitment(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.TREASURY, "PO_COMMITMENT", request, idempotencyKey);
    }

    @PostMapping("/treasury/contract-schedules")
    public ApiResponse<IntegrationEventResponse> treasuryContractSchedule(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.TREASURY, "CONTRACT_PAYMENT_SCHEDULE", request, idempotencyKey);
    }

    @PostMapping("/treasury/payment-register")
    public ApiResponse<IntegrationEventResponse> treasuryPaymentRegister(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.TREASURY, "PAYMENT_REGISTER", request, idempotencyKey);
    }

    @PostMapping("/budgeting/checks")
    public ApiResponse<IntegrationEventResponse> budgetingCheck(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.BUDGETING, "BUDGET_CHECK_REQUESTED", request, idempotencyKey);
    }

    @PostMapping("/budgeting/commitments")
    public ApiResponse<IntegrationEventResponse> budgetingCommitment(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.BUDGETING, "PO_COMMITMENT", request, idempotencyKey);
    }

    @PostMapping("/budgeting/consumptions")
    public ApiResponse<IntegrationEventResponse> budgetingConsumption(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.BUDGETING, "CONTRACT_LIMIT_CONSUMED", request, idempotencyKey);
    }

    @PostMapping("/budgeting/releases")
    public ApiResponse<IntegrationEventResponse> budgetingRelease(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.BUDGETING, "COMMITMENT_RELEASED", request, idempotencyKey);
    }

    @PostMapping("/budgeting/budget-lines")
    public ApiResponse<IntegrationEventResponse> budgetingLine(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.BUDGETING, "BUDGET_LINE_SYNC", request, idempotencyKey);
    }

    @PostMapping("/production/material-demands")
    public ApiResponse<IntegrationEventResponse> productionMaterialDemand(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.PRODUCTION, "MATERIAL_DEMAND", request, idempotencyKey);
    }

    @PostMapping("/production/reservations")
    public ApiResponse<IntegrationEventResponse> productionReservation(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.PRODUCTION, "MATERIAL_RESERVATION", request, idempotencyKey);
    }

    @PostMapping("/production/issues")
    public ApiResponse<IntegrationEventResponse> productionIssue(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.PRODUCTION, "STOCK_ISSUE_POSTED", request, idempotencyKey);
    }

    @PostMapping("/production/receipts")
    public ApiResponse<IntegrationEventResponse> productionReceipt(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.PRODUCTION, "PRODUCTION_RECEIPT", request, idempotencyKey);
    }

    @PostMapping("/production/orders")
    public ApiResponse<IntegrationEventResponse> productionOrder(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.PRODUCTION, "PRODUCTION_ORDER", request, idempotencyKey);
    }

    @PostMapping("/production/availability")
    public ApiResponse<IntegrationEventResponse> productionAvailability(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.PRODUCTION, "MATERIAL_AVAILABLE", request, idempotencyKey);
    }

    @PostMapping("/hr/sync-requests")
    public ApiResponse<IntegrationEventResponse> hrSyncRequest(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.HR, "SYNC_REQUESTED", request, idempotencyKey);
    }

    @PostMapping("/hr/organization-units")
    public ApiResponse<IntegrationEventResponse> hrOrganizationUnit(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.HR, "DEPARTMENT_SYNC", request, idempotencyKey);
    }

    @PostMapping("/hr/positions")
    public ApiResponse<IntegrationEventResponse> hrPosition(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.HR, "POSITION_SYNC", request, idempotencyKey);
    }

    @PostMapping("/hr/employees")
    public ApiResponse<IntegrationEventResponse> hrEmployee(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.HR, "EMPLOYEE_SYNC", request, idempotencyKey);
    }

    @PostMapping("/hr/approval-responsibilities")
    public ApiResponse<IntegrationEventResponse> hrApprover(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.HR, "APPROVER_SYNC", request, idempotencyKey);
    }

    @PostMapping("/hr/timesheets")
    public ApiResponse<IntegrationEventResponse> hrTimesheet(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.HR, "TIMESHEET_SYNC", request, idempotencyKey);
    }

    @PostMapping("/edo/contracts")
    public ApiResponse<IntegrationEventResponse> edoContract(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "CONTRACT_ACTIVE", request, idempotencyKey);
    }

    @PostMapping("/edo/purchase-orders")
    public ApiResponse<IntegrationEventResponse> edoPurchaseOrder(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "PO_SENT", request, idempotencyKey);
    }

    @PostMapping("/edo/service-acts")
    public ApiResponse<IntegrationEventResponse> edoServiceAct(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "SERVICE_ACT_ACCEPTED", request, idempotencyKey);
    }

    @PostMapping("/edo/letters")
    public ApiResponse<IntegrationEventResponse> edoLetter(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "LETTER_SENT", request, idempotencyKey);
    }

    @PostMapping("/edo/invoices")
    public ApiResponse<IntegrationEventResponse> edoInvoice(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "INVOICE_SENT", request, idempotencyKey);
    }

    @PostMapping("/edo/powers-of-attorney")
    public ApiResponse<IntegrationEventResponse> edoPowerOfAttorney(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "POWER_OF_ATTORNEY_SENT", request, idempotencyKey);
    }

    @PostMapping("/edo/reconciliation-acts")
    public ApiResponse<IntegrationEventResponse> edoReconciliationAct(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "RECONCILIATION_ACT_SENT", request, idempotencyKey);
    }

    @PostMapping("/edo/document-status")
    public ApiResponse<IntegrationEventResponse> edoDocumentStatus(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EDO, "DOCUMENT_STATUS_REQUESTED", request, idempotencyKey);
    }

    @PostMapping("/one-c/master-data")
    public ApiResponse<IntegrationEventResponse> oneCMasterData(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ONE_C, "MASTER_DATA_SYNC", request, idempotencyKey);
    }

    @PostMapping("/one-c/stock-movements")
    public ApiResponse<IntegrationEventResponse> oneCStockMovement(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ONE_C, "STOCK_MOVEMENT_SYNC", request, idempotencyKey);
    }

    @PostMapping("/one-c/valuation")
    public ApiResponse<IntegrationEventResponse> oneCValuation(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ONE_C, "VALUATION_SYNC", request, idempotencyKey);
    }

    @PostMapping("/one-c/accounting-entries")
    public ApiResponse<IntegrationEventResponse> oneCAccountingEntry(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ONE_C, "ACCOUNTING_ENTRY", request, idempotencyKey);
    }

    @PostMapping("/wms/receipt-tasks")
    public ApiResponse<IntegrationEventResponse> wmsReceiptTask(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.WMS, "RECEIPT_TASK", request, idempotencyKey);
    }

    @PostMapping("/wms/transfer-tasks")
    public ApiResponse<IntegrationEventResponse> wmsTransferTask(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.WMS, "TRANSFER_TASK", request, idempotencyKey);
    }

    @PostMapping("/wms/transfer-receipts")
    public ApiResponse<IntegrationEventResponse> wmsTransferReceipt(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.WMS, "TRANSFER_RECEIPT", request, idempotencyKey);
    }

    @PostMapping("/wms/stock-balances")
    public ApiResponse<IntegrationEventResponse> wmsStockBalance(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.WMS, "STOCK_BALANCE_SYNC", request, idempotencyKey);
    }

    @PostMapping("/wms/barcode-events")
    public ApiResponse<IntegrationEventResponse> wmsBarcodeEvent(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.WMS, "BARCODE_EVENT", request, idempotencyKey);
    }

    @PostMapping("/scada/consumption-signals")
    public ApiResponse<IntegrationEventResponse> scadaConsumptionSignal(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.SCADA, "CONSUMPTION_SIGNAL", request, idempotencyKey);
    }

    @PostMapping("/scada/production-receipts")
    public ApiResponse<IntegrationEventResponse> scadaProductionReceipt(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.SCADA, "PRODUCTION_RECEIPT", request, idempotencyKey);
    }

    @PostMapping("/ettn/goods-receipts")
    public ApiResponse<IntegrationEventResponse> ettnGoodsReceipt(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ETTN, "GOODS_RECEIPT", request, idempotencyKey);
    }

    @PostMapping("/ettn/transport-documents")
    public ApiResponse<IntegrationEventResponse> ettnTransportDocument(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ETTN, "TRANSPORT_DOCUMENT", request, idempotencyKey);
    }

    @PostMapping("/ettn/status")
    public ApiResponse<IntegrationEventResponse> ettnStatus(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.ETTN, "STATUS_REQUESTED", request, idempotencyKey);
    }

    @PostMapping("/exchange/rfq-published")
    public ApiResponse<IntegrationEventResponse> exchangeRfqPublished(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EXCHANGE, "RFQ_PUBLISHED", request, idempotencyKey);
    }

    @PostMapping("/exchange/quotation-received")
    public ApiResponse<IntegrationEventResponse> exchangeQuotationReceived(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EXCHANGE, "QUOTATION_RECEIVED", request, idempotencyKey);
    }

    @PostMapping("/exchange/bids")
    public ApiResponse<IntegrationEventResponse> exchangeBid(@Valid @RequestBody(required = false) EnterpriseIntegrationRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return queue(ExternalIntegrationSystem.EXCHANGE, "BID_SYNC", request, idempotencyKey);
    }

    private ApiResponse<IntegrationEventResponse> queue(ExternalIntegrationSystem system, String eventType, EnterpriseIntegrationRequest request, String idempotencyKey) {
        return ApiResponse.ok(service.queue(system, eventType, request, idempotencyKey));
    }

    private ApiResponse<EnterpriseIntegrationPushResult> batch(ExternalIntegrationSystem system, String eventType, EnterpriseIntegrationBatchRequest request, String idempotencyKey) {
        return ApiResponse.ok(service.queueBatch(system, eventType, request, idempotencyKey));
    }
}

