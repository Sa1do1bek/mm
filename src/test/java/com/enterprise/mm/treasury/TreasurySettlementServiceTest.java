package com.enterprise.mm.treasury;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class TreasurySettlementServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode fact(){return mapper.createObjectNode().put("nativePaymentId","BANK:EXECUTION-1").put("amount","10.50").put("currency","UZS").put("paymentDate","2026-11-17").put("sourceKind","BANK_STATEMENT").put("finality","FINAL");}
    @Test void bankIdentityNormalizesAcrossChannelsAndMalformedFactsCannotBecomeMoney() {
        var p=fact();assertThat(TreasurySettlementService.validate(p,false).id()).isEqualTo("bank:execution-1");
        p.put("finality","PROVISIONAL");assertThatThrownBy(()->TreasurySettlementService.validate(p,false)).hasMessageContaining("Final");
        var badDate=fact().put("paymentDate","invalid");assertThatThrownBy(()->TreasurySettlementService.validate(badDate,false)).hasMessageContaining("paymentDate");
    }
    @Test void explicitTestEvidenceIsNeverAcceptedByProductionValidator() {
        var p=fact().put("sourceKind","TEST_FIXTURE");assertThatThrownBy(()->TreasurySettlementService.validate(p,false)).hasMessageContaining("bank");
        assertThat(TreasurySettlementService.validate(p,false,true).source()).isEqualTo("TEST_FIXTURE");
    }
    @Test void decimalScaleAndReversalIdentityAreConstrained() {
        for(String value:new String[]{"0","-10","1e2","1.00001","1000000000000000"}){var p=fact().put("amount",value);assertThatThrownBy(()->TreasurySettlementService.validate(p,false));}
        var self=fact().put("originalPaymentId","bank:execution-1");assertThatThrownBy(()->TreasurySettlementService.validate(self,true)).hasMessageContaining("different");
    }
}
