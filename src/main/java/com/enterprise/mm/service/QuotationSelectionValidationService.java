package com.enterprise.mm.service;

import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuotationSelectionValidationService {
    private final TenderProcessService tenderProcessService;

    public void validateSelection(SupplierQuotation selected, RFQ rfq, List<SupplierQuotation> quotations, String reason) {
        validateTenderSelection(rfq, selected, quotations);
        tenderProcessService.validateReadyForSelection(rfq, selected);
    }

    public SelectionReadiness readiness(SupplierQuotation quotation, RFQ rfq, List<SupplierQuotation> quotations) {
        try {
            validateSelection(quotation, rfq, quotations, null);
            return SelectionReadiness.available();
        } catch (BusinessException ex) {
            return SelectionReadiness.notReady(ex.getMessage());
        }
    }

    public void validateTenderSelection(RFQ rfq, SupplierQuotation selected, List<SupplierQuotation> quotations) {
        String procedure = rfq.getTenderProcedureType() == null ? "REQUEST_FOR_QUOTATION" : rfq.getTenderProcedureType();
        String rule = rfq.getSelectionRule() == null ? "LOWEST_PRICE" : rfq.getSelectionRule();
        int minRequired = rfq.getMinQuotationsRequired() == null ? ("SINGLE_SUPPLIER".equals(procedure) ? 1 : 2) : rfq.getMinQuotationsRequired();
        if (quotations.size() < minRequired) {
            throw new BusinessException("Tender procedure requires at least " + minRequired + " quotations");
        }
        if ("SINGLE_SUPPLIER".equals(procedure) || "SINGLE_SUPPLIER".equals(rule)) {
            return;
        }
        if ("LOWEST_PRICE".equals(rule)) {
            BigDecimal lowest = quotations.stream()
                .map(quotation -> amount(quotation.getTotalAmount()))
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
            if (amount(selected.getTotalAmount()).compareTo(lowest) != 0) {
                throw new BusinessException("Selection rule LOWEST_PRICE requires the lowest price quotation");
            }
        }
        if ("BEST_SCORE".equals(rule)) {
            BigDecimal best = quotations.stream()
                .map(quotation -> amount(quotation.getTotalScore()))
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
            if (amount(selected.getTotalScore()).compareTo(best) != 0) {
                throw new BusinessException("Selection rule BEST_SCORE requires the highest scored quotation");
            }
        }
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record SelectionReadiness(boolean ready, String blockedReason) {
        public static SelectionReadiness available() {
            return new SelectionReadiness(true, null);
        }

        public static SelectionReadiness notReady(String reason) {
            return new SelectionReadiness(false, reason);
        }
    }
}
