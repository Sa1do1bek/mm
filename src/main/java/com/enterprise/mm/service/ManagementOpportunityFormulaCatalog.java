package com.enterprise.mm.service;

import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.payload.ManagementOpportunityFormulaResponse;
import java.util.List;

public final class ManagementOpportunityFormulaCatalog {
    private ManagementOpportunityFormulaCatalog() {
    }

    public static ManagementOpportunityFormulaResponse describe(ManagementOpportunityType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case PROCUREMENT_PRICE_VARIANCE -> new ManagementOpportunityFormulaResponse(
                "Procurement price variance",
                "max(actualUnitPrice - approvedMarketUnitPrice, 0) * acceptedQuantity",
                "Extra purchase cost versus the approved market baseline.",
                "Snapshot at the run asOfDate; no weekly proration is applied.",
                List.of(
                    variable("actualUnitPrice", "Unit price from the purchase order line."),
                    variable("approvedMarketUnitPrice", "Approved market unit price effective at the run asOfDate."),
                    variable("acceptedQuantity", "Received quantity less rejected and damaged quantity.")
                )
            );
            case EXCESS_CARRYING_COST -> new ManagementOpportunityFormulaResponse(
                "Excess carrying cost",
                "bookValue * annualCarryingCostRate * eligibleDays / 365",
                "Estimated cost of holding non-moving stock for the eligible period.",
                "Annual carrying rate prorated by eligibleDays. A weekly view is produced only when eligibleDays is 7.",
                List.of(
                    variable("bookValue", "Book value of the non-moving stock case line."),
                    variable("annualCarryingCostRate", "Active management opportunity policy rate, for example 0.20 for 20%."),
                    variable("eligibleDays", "Days from lastMovementAt to asOfDate, or monthsWithoutMovement * 30 when lastMovementAt is missing.")
                )
            );
            case NON_MOVING_RECOVERY_GAP -> new ManagementOpportunityFormulaResponse(
                "Non-moving recovery gap",
                "max(bookValue - allocatedExpectedRecoveryAmount, 0)",
                "Book value that is not expected to be recovered from the approved sell/write-off decision.",
                "Snapshot at the run asOfDate; no weekly proration is applied.",
                List.of(
                    variable("bookValue", "Book value of the non-moving stock case line."),
                    variable("allocatedExpectedRecoveryAmount", "Decision expected recovery allocated to the line by book value, or by quantity when recoveryAllocationBasis is QUANTITY.")
                )
            );
            case SUPPLIER_DELAY_EXPOSURE -> new ManagementOpportunityFormulaResponse(
                "Supplier delay exposure",
                "lateDays * dailyImpactRate * attributableShare",
                "Estimated business impact attributable to an overdue supplier schedule.",
                "Calculated for days late as of the run asOfDate; no annual /365 proration is applied.",
                List.of(
                    variable("lateDays", "Days between scheduleDate and asOfDate."),
                    variable("dailyImpactRate", "Daily impact amount from the active management opportunity policy."),
                    variable("attributableShare", "Share of the impact attributable to the supplier delay.")
                )
            );
        };
    }

    private static ManagementOpportunityFormulaResponse.Variable variable(String symbol, String description) {
        return new ManagementOpportunityFormulaResponse.Variable(symbol, description);
    }
}
