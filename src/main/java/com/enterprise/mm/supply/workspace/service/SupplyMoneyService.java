package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SupplyMoneyService {
    public MoneyBreakdown group(List<SupplyCommitmentRecord.MoneyValue> groupedOriginalCurrencyValues) {
        List<SupplyCommitmentRecord.MoneyValue> source = groupedOriginalCurrencyValues == null
            ? List.of()
            : groupedOriginalCurrencyValues;
        List<UnavailableReason> reasons = new ArrayList<>();
        List<MoneyValue> values = source.stream()
            .sorted(Comparator.comparing(
                SupplyCommitmentRecord.MoneyValue::currencyCode,
                Comparator.nullsLast(String::compareTo)))
            .map(value -> {
                if (value.currencyCode() == null) {
                    addOnce(reasons, "MISSING_CURRENCY", "supply.workspace.money.currencyMissing");
                } else if (!isIsoCurrency(value.currencyCode())) {
                    addOnce(reasons, "INVALID_CURRENCY", "supply.workspace.money.currencyInvalid");
                }
                if (value.amount() == null) {
                    addOnce(reasons, "INCOMPLETE_OPEN_AMOUNT", "supply.workspace.money.amountIncomplete");
                }
                return new MoneyValue(
                    value.amount(), value.currencyCode(), null, null, null, null, null, false);
            })
            .toList();
        SupplyDataQuality availability = reasons.isEmpty()
            ? SupplyDataQuality.AUTHORITATIVE
            : SupplyDataQuality.PARTIAL;
        return new MoneyBreakdown(values, availability, reasons);
    }

    private void addOnce(List<UnavailableReason> reasons, String code, String messageKey) {
        if (reasons.stream().noneMatch(reason -> code.equals(reason.code()))) {
            reasons.add(new UnavailableReason(code, messageKey));
        }
    }

    static boolean isIsoCurrency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) return false;
        try {
            return java.util.Currency.getInstance(value).getCurrencyCode().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record MoneyBreakdown(
        List<MoneyValue> values,
        SupplyDataQuality availability,
        List<UnavailableReason> reasons
    ) {
        public MoneyBreakdown {
            values = values == null ? List.of() : List.copyOf(values);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
