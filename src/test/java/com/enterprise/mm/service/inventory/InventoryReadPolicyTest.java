package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryReadPolicyTest {
    @Test void blindProjectionRemovesDerivedQuantityAndValueWithoutMutatingOriginal() throws Exception {
        var row=new InventoryItemResponse();row.setSystemQuantity(new BigDecimal("100"));row.setCountedQuantity(new BigDecimal("97"));
        row.setAsOfQuantity(new BigDecimal("120")); row.setAsOfValue(new BigDecimal("1200")); row.setMovementDelta(new BigDecimal("-20"));
        row.setDifferenceQuantity(new BigDecimal("-3"));row.setDifferenceAmount(new BigDecimal("45"));row.setUnitCost(new BigDecimal("15"));
        row.setLineStatus(InventoryLineStatus.DISCREPANCY);row.setStatus("DISCREPANCY");row.setDecisionType(InventoryDiscrepancyDecisionType.WRITE_OFF_SHORTAGE);row.setDecisionComment("Write off three missing pieces");
        row.setCountEvidenceCount(2);
        var visible=InventoryReadPolicy.project(row,false);
        assertThat(visible.getSystemQuantity()).isNull();assertThat(visible.getDifferenceQuantity()).isNull();assertThat(visible.getDifferenceAmount()).isNull();assertThat(visible.getUnitCost()).isNull();
        assertThat(visible.getAsOfQuantity()).isNull(); assertThat(visible.getAsOfValue()).isNull(); assertThat(visible.getMovementDelta()).isNull();
        assertThat(visible.getCountedQuantity()).isEqualByComparingTo("97");assertThat(visible.getLineStatus()).isEqualTo(InventoryLineStatus.COUNTED);
        assertThat(visible.getCountEvidenceCount()).isEqualTo(2);
        assertThat(new ObjectMapper().writeValueAsString(visible)).doesNotContain("systemQuantity","asOfQuantity","asOfValue","movementDelta","differenceQuantity","differenceAmount","unitCost","decisionType","Write off","DISCREPANCY");
        assertThat(row.getSystemQuantity()).isEqualByComparingTo("100");assertThat(row.getLineStatus()).isEqualTo(InventoryLineStatus.DISCREPANCY);
    }
    @Test void blindHistoryRetainsObservationsAndHidesReviewReasonsAndDerivedState() {
        var history=new InventoryLineHistoryResponse();history.setAction("RESOLVE_DISCREPANCY");history.setNewCountedQuantity(BigDecimal.ZERO);history.setNewDifferenceQuantity(BigDecimal.TEN.negate());history.setNewStatus(InventoryLineStatus.RESOLVED);history.setReason("Ten missing");
        var visible=InventoryReadPolicy.project(history,false);
        assertThat(visible.getNewDifferenceQuantity()).isNull();assertThat(visible.getReason()).isNull();assertThat(visible.getAction()).isEqualTo("REVIEW_UPDATED");assertThat(visible.getNewStatus()).isEqualTo(InventoryLineStatus.COUNTED);
    }
}
