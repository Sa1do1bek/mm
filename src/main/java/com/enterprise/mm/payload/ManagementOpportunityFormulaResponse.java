package com.enterprise.mm.payload;

import java.util.List;

public record ManagementOpportunityFormulaResponse(
    String title,
    String expression,
    String amountMeaning,
    String periodBasis,
    List<Variable> variables
) {
    public record Variable(
        String symbol,
        String description
    ) {
    }
}
