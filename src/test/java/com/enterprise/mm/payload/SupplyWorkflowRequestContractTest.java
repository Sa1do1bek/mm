package com.enterprise.mm.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SupplyWorkflowRequestContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void importDeliveryUpdateRequestIgnoresWorkflowOwnedJsonFields() throws Exception {
        ImportDeliveryUpdateRequest request = objectMapper.readValue("""
                {
                  "baseGoodsAmount": 1250.00,
                  "status": "CLOSED",
                  "actualArrivalDate": "2030-01-01",
                  "customsDeclarationId": "00000000-0000-0000-0000-000000000099",
                  "logisticsCostAmount": 9999.00,
                  "customsCostAmount": 9999.00,
                  "landedCostAmount": 9999.00,
                  "currentMilestone": "CLOSED",
                  "lastEventAt": "2030-01-01T00:00:00",
                  "riskFlag": true,
                  "riskReason": "Injected risk"
                }
                """, ImportDeliveryUpdateRequest.class);

        assertThat(request.getBaseGoodsAmount()).isEqualByComparingTo("1250.00");
        assertThat(request.getStatus()).isNull();
        assertThat(request.getActualArrivalDate()).isNull();
        assertThat(request.getCustomsDeclarationId()).isNull();
        assertThat(request.getLogisticsCostAmount()).isNull();
        assertThat(request.getCustomsCostAmount()).isNull();
        assertThat(request.getLandedCostAmount()).isNull();
        assertThat(request.getCurrentMilestone()).isNull();
        assertThat(request.getLastEventAt()).isNull();
        assertThat(request.isRiskFlag()).isFalse();
        assertThat(request.getRiskReason()).isNull();
    }

    @Test
    void freightBookingUpdateRequestIgnoresWorkflowOwnedJsonFields() throws Exception {
        ImportFreightBookingUpdateRequest request = objectMapper.readValue("""
                {
                  "freightAmount": 600.00,
                  "status": "DELIVERED",
                  "actualPickupDate": "2030-01-01",
                  "actualDeliveryDate": "2030-01-02",
                  "totalFreightAmount": 9999.00,
                  "landedCostComponentId": "00000000-0000-0000-0000-000000000099",
                  "postedAt": "2030-01-03T00:00:00"
                }
                """, ImportFreightBookingUpdateRequest.class);

        assertThat(request.getFreightAmount()).isEqualByComparingTo("600.00");
        assertThat(request.getStatus()).isNull();
        assertThat(request.getActualPickupDate()).isNull();
        assertThat(request.getActualDeliveryDate()).isNull();
        assertThat(request.getTotalFreightAmount()).isNull();
        assertThat(request.getLandedCostComponentId()).isNull();
        assertThat(request.getPostedAt()).isNull();
    }

    @Test
    void insurancePolicyUpdateRequestIgnoresWorkflowOwnedJsonFields() throws Exception {
        ImportInsurancePolicyUpdateRequest request = objectMapper.readValue("""
                {
                  "premiumAmount": 45.00,
                  "status": "CLOSED",
                  "landedCostComponentId": "00000000-0000-0000-0000-000000000099",
                  "activatedAt": "2030-01-01T00:00:00",
                  "claimedAt": "2030-01-02T00:00:00",
                  "closedAt": "2030-01-03T00:00:00"
                }
                """, ImportInsurancePolicyUpdateRequest.class);

        assertThat(request.getPremiumAmount()).isEqualByComparingTo("45.00");
        assertThat(request.getStatus()).isNull();
        assertThat(request.getLandedCostComponentId()).isNull();
        assertThat(request.getActivatedAt()).isNull();
        assertThat(request.getClaimedAt()).isNull();
        assertThat(request.getClosedAt()).isNull();
    }

    @Test
    void customsDeclarationUpdateRequestIgnoresWorkflowOwnedJsonFields() throws Exception {
        CustomsDeclarationUpdateRequest request = objectMapper.readValue("""
                {
                  "dutyAmount": 120.00,
                  "status": "RELEASED",
                  "totalCustomsAmount": 9999.00,
                  "submittedAt": "2030-01-01T00:00:00",
                  "releasedAt": "2030-01-02T00:00:00"
                }
                """, CustomsDeclarationUpdateRequest.class);

        assertThat(request.getDutyAmount()).isEqualByComparingTo("120.00");
        assertThat(request.getStatus()).isNull();
        assertThat(request.getTotalCustomsAmount()).isNull();
        assertThat(request.getSubmittedAt()).isNull();
        assertThat(request.getReleasedAt()).isNull();
    }

    @Test
    void landedCostComponentUpdateRequestIgnoresWorkflowOwnedJsonFields() throws Exception {
        LandedCostComponentUpdateRequest request = objectMapper.readValue("""
                {
                  "amount": 60.00,
                  "status": "POSTED",
                  "postedAt": "2030-01-01T00:00:00"
                }
                """, LandedCostComponentUpdateRequest.class);

        assertThat(request.getAmount()).isEqualByComparingTo("60.00");
        assertThat(request.getStatus()).isNull();
        assertThat(request.getPostedAt()).isNull();
    }
}
