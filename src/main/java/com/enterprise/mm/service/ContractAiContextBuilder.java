package com.enterprise.mm.service;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractAddendum;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ContractPowerOfAttorney;
import com.enterprise.mm.entity.ContractReconciliationAct;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.Supplier;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ContractAiContextBuilder {
    private ContractAiContextBuilder() {
    }
    static Map<String, Object> context(
        Contract contract,
        Supplier supplier,
        ContractAiMetrics metrics,
        List<ContractInvoice> invoices,
        List<ContractPayment> payments,
        List<ContractReconciliationAct> acts,
        List<ContractPowerOfAttorney> attorneys,
        List<ContractAddendum> addendums,
        List<DeliverySchedule> schedules,
        List<ContractAiFindingDto> findings
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", TenantContext.currentTenant());
        out.put("companyId", CompanyContext.currentCompany());
        out.put("contract", map(
            "id", contract.getId(),
            "number", firstNonBlank(contract.getContractNumber(), contract.getCode()),
            "status", contract.getStatus(),
            "startDate", contract.getStartDate(),
            "endDate", contract.getEndDate(),
            "limitAmount", metrics.limitAmount(),
            "usedAmount", metrics.usedAmount(),
            "currencyCode", contract.getCurrencyCode()
        ));
        if (supplier != null) {
            out.put("supplier", map(
                "id", supplier.getId(),
                "name", firstNonBlank(supplier.getLegalName(), supplier.getShortName(), supplier.getName()),
                "status", supplier.getStatus(),
                "category", firstNonBlank(supplier.getCategory(), ""),
                "rating", nvl(supplier.getRating())
            ));
        }
        out.put("metrics", map(
            "utilizationPercent", metrics.utilizationPercent(),
            "totalInvoices", metrics.totalInvoices(),
            "totalPayments", metrics.totalPayments(),
            "unpaidAmount", metrics.unpaidAmount(),
            "overdueInvoices", metrics.overdueInvoices(),
            "expiredPowersOfAttorney", metrics.expiredPowersOfAttorney(),
            "overdueSchedules", metrics.overdueSchedules()
        ));
        out.put("documents", map(
            "invoices", invoices.stream().map(ContractAiContextBuilder::invoiceContext).toList(),
            "payments", payments.stream().map(ContractAiContextBuilder::paymentContext).toList(),
            "reconciliationActs", acts.stream().map(ContractAiContextBuilder::actContext).toList(),
            "powersOfAttorney", attorneys.stream().map(ContractAiContextBuilder::attorneyContext).toList(),
            "addendums", addendums.stream().map(ContractAiContextBuilder::addendumContext).toList(),
            "deliverySchedules", schedules.stream().map(ContractAiContextBuilder::scheduleContext).toList()
        ));
        out.put("ruleFindings", findings);
        return out;
    }
    private static Map<String, Object> invoiceContext(ContractInvoice invoice) {
        return map(
            "number", firstNonBlank(invoice.getInvoiceNumber(), invoice.getCode()),
            "status", invoice.getStatus(),
            "invoiceDate", invoice.getInvoiceDate(),
            "dueDate", invoice.getDueDate(),
            "amount", nvl(invoice.getAmount()),
            "currencyCode", invoice.getCurrencyCode()
        );
    }
    private static Map<String, Object> paymentContext(ContractPayment payment) {
        return map(
            "number", firstNonBlank(payment.getPaymentNumber(), payment.getCode()),
            "status", payment.getStatus(),
            "paymentDate", payment.getPaymentDate(),
            "amount", nvl(payment.getAmount()),
            "currencyCode", payment.getCurrencyCode(),
            "paymentMethod", firstNonBlank(payment.getPaymentMethod(), "")
        );
    }
    private static Map<String, Object> actContext(ContractReconciliationAct act) {
        return map(
            "number", firstNonBlank(act.getActNumber(), act.getCode()),
            "status", act.getStatus(),
            "periodStart", act.getPeriodStart(),
            "periodEnd", act.getPeriodEnd(),
            "actDate", act.getActDate(),
            "closingBalance", nvl(act.getClosingBalance())
        );
    }
    private static Map<String, Object> attorneyContext(ContractPowerOfAttorney attorney) {
        return map(
            "number", firstNonBlank(attorney.getAttorneyNumber(), attorney.getCode()),
            "status", attorney.getStatus(),
            "issuedTo", firstNonBlank(attorney.getIssuedTo(), ""),
            "issueDate", attorney.getIssueDate(),
            "validTo", attorney.getValidTo(),
            "amountLimit", nvl(attorney.getAmountLimit())
        );
    }
    private static Map<String, Object> addendumContext(ContractAddendum addendum) {
        return map(
            "number", firstNonBlank(addendum.getAddendumNumber(), addendum.getCode()),
            "status", addendum.getStatus(),
            "addendumDate", addendum.getAddendumDate(),
            "addendumType", firstNonBlank(addendum.getAddendumType(), ""),
            "newEndDate", addendum.getNewEndDate(),
            "limitDelta", nvl(addendum.getLimitDelta()),
            "newLimitAmount", nvl(addendum.getNewLimitAmount())
        );
    }
    private static Map<String, Object> scheduleContext(DeliverySchedule schedule) {
        return map(
            "status", schedule.getStatus(),
            "scheduleDate", schedule.getScheduleDate(),
            "plannedQuantity", nvl(schedule.getPlannedQuantity()),
            "plannedAmount", nvl(schedule.getPlannedAmount()),
            "receivedQuantity", nvl(schedule.getReceivedQuantity()),
            "receivedAmount", nvl(schedule.getReceivedAmount()),
            "overdueFlag", schedule.isOverdueFlag()
        );
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length - 1; index += 2) {
            out.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return out;
    }
    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private static String firstNonBlank(String... values) {
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
}
