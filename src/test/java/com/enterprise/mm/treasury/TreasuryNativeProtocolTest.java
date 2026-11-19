package com.enterprise.mm.treasury;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TreasuryNativeProtocolTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void financialFactsRequireDecimalStringsFinalBankEvidenceAndStableIdentity() throws Exception {
        var fact=mapper.readTree("""
            {"nativePaymentId":"bank:1","amount":"40.00","currency":"UZS","paymentDate":"2026-11-17","finality":"FINAL","sourceKind":"BANK_STATEMENT"}
            """);
        assertThat(TreasurySettlementService.validate(fact,false).amount()).isEqualByComparingTo("40");
        var changed=fact.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("amount",40);
        assertThatThrownBy(()->TreasurySettlementService.validate(changed,false)).hasMessageContaining("decimal string");
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("amount","40").put("sourceKind","STATUS");
        assertThatThrownBy(()->TreasurySettlementService.validate(changed,false)).hasMessageContaining("bank");
        assertThatThrownBy(()->TreasurySettlementService.validate(fact,true)).hasMessageContaining("originalPaymentId");
    }
    @Test void payloadHashesCoverEveryByteIncludingAmount() {
        assertThat(TreasuryTaskService.hash("{\"amount\":\"40\"}")).isNotEqualTo(TreasuryTaskService.hash("{\"amount\":\"60\"}"));
        assertThat(TreasuryTaskService.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
    @Test void deliveredSourceEventIsResolvedTransport() {
        assertThat(com.enterprise.mm.enums.IntegrationStatus.fromLegacy("DELIVERED")).isEqualTo(com.enterprise.mm.enums.IntegrationStatus.SUCCEEDED);
        assertThat(com.enterprise.mm.common.IntegrationRecoveryStatus.fromEventStatus("DELIVERED")).isEqualTo(com.enterprise.mm.common.IntegrationRecoveryStatus.RESOLVED);
    }
}
