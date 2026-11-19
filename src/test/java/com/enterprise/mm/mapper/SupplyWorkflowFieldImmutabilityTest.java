package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.CustomsDeclaration;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.ImportFreightBooking;
import com.enterprise.mm.entity.ImportInsurancePolicy;
import com.enterprise.mm.entity.LandedCostComponent;
import com.enterprise.mm.payload.CustomsDeclarationUpdateRequest;
import com.enterprise.mm.payload.ImportDeliveryUpdateRequest;
import com.enterprise.mm.payload.ImportFreightBookingUpdateRequest;
import com.enterprise.mm.payload.ImportInsurancePolicyUpdateRequest;
import com.enterprise.mm.payload.LandedCostComponentUpdateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplyWorkflowFieldImmutabilityTest {

    @Test
    void importDeliveryUpdatePreservesWorkflowOwnedFields() {
        UUID customsDeclarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        LocalDate actualArrivalDate = LocalDate.of(2026, 4, 10);
        LocalDateTime lastEventAt = LocalDateTime.of(2026, 4, 10, 14, 30);
        ImportDelivery entity = new ImportDelivery();
        entity.setStatus("IN_TRANSIT");
        entity.setBaseGoodsAmount(new BigDecimal("1000.00"));
        entity.setActualArrivalDate(actualArrivalDate);
        entity.setCustomsDeclarationId(customsDeclarationId);
        entity.setLogisticsCostAmount(new BigDecimal("100.00"));
        entity.setCustomsCostAmount(new BigDecimal("200.00"));
        entity.setLandedCostAmount(new BigDecimal("1300.00"));
        entity.setCurrentMilestone("BORDER_CROSSING");
        entity.setLastEventAt(lastEventAt);
        entity.setRiskFlag(false);
        entity.setRiskReason("No active risk");

        ImportDeliveryUpdateRequest request = new ImportDeliveryUpdateRequest();
        request.setBaseGoodsAmount(new BigDecimal("1250.00"));
        request.setStatus("CLOSED");
        request.setActualArrivalDate(LocalDate.of(2030, 1, 1));
        request.setCustomsDeclarationId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        request.setLogisticsCostAmount(new BigDecimal("9999.00"));
        request.setCustomsCostAmount(new BigDecimal("9999.00"));
        request.setLandedCostAmount(new BigDecimal("9999.00"));
        request.setCurrentMilestone("CLOSED");
        request.setLastEventAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        request.setRiskFlag(true);
        request.setRiskReason("Injected risk");

        new ImportDeliveryMapper().updateEntity(entity, request);

        assertThat(entity.getBaseGoodsAmount()).isEqualByComparingTo("1250.00");
        assertThat(entity.getStatus()).isEqualTo("IN_TRANSIT");
        assertThat(entity.getActualArrivalDate()).isEqualTo(actualArrivalDate);
        assertThat(entity.getCustomsDeclarationId()).isEqualTo(customsDeclarationId);
        assertThat(entity.getLogisticsCostAmount()).isEqualByComparingTo("100.00");
        assertThat(entity.getCustomsCostAmount()).isEqualByComparingTo("200.00");
        assertThat(entity.getLandedCostAmount()).isEqualByComparingTo("1300.00");
        assertThat(entity.getCurrentMilestone()).isEqualTo("BORDER_CROSSING");
        assertThat(entity.getLastEventAt()).isEqualTo(lastEventAt);
        assertThat(entity.isRiskFlag()).isFalse();
        assertThat(entity.getRiskReason()).isEqualTo("No active risk");
    }

    @Test
    void freightUpdatePreservesWorkflowOwnedFields() {
        UUID landedCostComponentId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LocalDate actualPickupDate = LocalDate.of(2026, 4, 5);
        LocalDate actualDeliveryDate = LocalDate.of(2026, 4, 12);
        LocalDateTime postedAt = LocalDateTime.of(2026, 4, 13, 9, 0);
        ImportFreightBooking entity = new ImportFreightBooking();
        entity.setStatus("DELIVERED");
        entity.setFreightAmount(new BigDecimal("500.00"));
        entity.setActualPickupDate(actualPickupDate);
        entity.setActualDeliveryDate(actualDeliveryDate);
        entity.setTotalFreightAmount(new BigDecimal("575.00"));
        entity.setLandedCostComponentId(landedCostComponentId);
        entity.setPostedAt(postedAt);

        ImportFreightBookingUpdateRequest request = new ImportFreightBookingUpdateRequest();
        request.setFreightAmount(new BigDecimal("600.00"));
        request.setStatus("DRAFT");
        request.setActualPickupDate(LocalDate.of(2030, 1, 1));
        request.setActualDeliveryDate(LocalDate.of(2030, 1, 2));
        request.setTotalFreightAmount(new BigDecimal("9999.00"));
        request.setLandedCostComponentId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        request.setPostedAt(LocalDateTime.of(2030, 1, 3, 0, 0));

        new ImportFreightBookingMapper().updateEntity(entity, request);

        assertThat(entity.getFreightAmount()).isEqualByComparingTo("600.00");
        assertThat(entity.getStatus()).isEqualTo("DELIVERED");
        assertThat(entity.getActualPickupDate()).isEqualTo(actualPickupDate);
        assertThat(entity.getActualDeliveryDate()).isEqualTo(actualDeliveryDate);
        assertThat(entity.getTotalFreightAmount()).isEqualByComparingTo("575.00");
        assertThat(entity.getLandedCostComponentId()).isEqualTo(landedCostComponentId);
        assertThat(entity.getPostedAt()).isEqualTo(postedAt);
    }

    @Test
    void insuranceUpdatePreservesWorkflowOwnedFields() {
        UUID landedCostComponentId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        LocalDateTime activatedAt = LocalDateTime.of(2026, 4, 1, 8, 0);
        LocalDateTime claimedAt = LocalDateTime.of(2026, 4, 15, 10, 0);
        LocalDateTime closedAt = LocalDateTime.of(2026, 4, 20, 16, 0);
        ImportInsurancePolicy entity = new ImportInsurancePolicy();
        entity.setStatus("CLOSED");
        entity.setPremiumAmount(new BigDecimal("40.00"));
        entity.setLandedCostComponentId(landedCostComponentId);
        entity.setActivatedAt(activatedAt);
        entity.setClaimedAt(claimedAt);
        entity.setClosedAt(closedAt);

        ImportInsurancePolicyUpdateRequest request = new ImportInsurancePolicyUpdateRequest();
        request.setPremiumAmount(new BigDecimal("45.00"));
        request.setStatus("DRAFT");
        request.setLandedCostComponentId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        request.setActivatedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        request.setClaimedAt(LocalDateTime.of(2030, 1, 2, 0, 0));
        request.setClosedAt(LocalDateTime.of(2030, 1, 3, 0, 0));

        new ImportInsurancePolicyMapper().updateEntity(entity, request);

        assertThat(entity.getPremiumAmount()).isEqualByComparingTo("45.00");
        assertThat(entity.getStatus()).isEqualTo("CLOSED");
        assertThat(entity.getLandedCostComponentId()).isEqualTo(landedCostComponentId);
        assertThat(entity.getActivatedAt()).isEqualTo(activatedAt);
        assertThat(entity.getClaimedAt()).isEqualTo(claimedAt);
        assertThat(entity.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    void customsUpdatePreservesWorkflowOwnedFields() {
        LocalDateTime submittedAt = LocalDateTime.of(2026, 4, 7, 9, 0);
        LocalDateTime releasedAt = LocalDateTime.of(2026, 4, 9, 17, 0);
        CustomsDeclaration entity = new CustomsDeclaration();
        entity.setStatus("RELEASED");
        entity.setDutyAmount(new BigDecimal("100.00"));
        entity.setTotalCustomsAmount(new BigDecimal("300.00"));
        entity.setSubmittedAt(submittedAt);
        entity.setReleasedAt(releasedAt);

        CustomsDeclarationUpdateRequest request = new CustomsDeclarationUpdateRequest();
        request.setDutyAmount(new BigDecimal("120.00"));
        request.setStatus("DRAFT");
        request.setTotalCustomsAmount(new BigDecimal("9999.00"));
        request.setSubmittedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        request.setReleasedAt(LocalDateTime.of(2030, 1, 2, 0, 0));

        new CustomsDeclarationMapper().updateEntity(entity, request);

        assertThat(entity.getDutyAmount()).isEqualByComparingTo("120.00");
        assertThat(entity.getStatus()).isEqualTo("RELEASED");
        assertThat(entity.getTotalCustomsAmount()).isEqualByComparingTo("300.00");
        assertThat(entity.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(entity.getReleasedAt()).isEqualTo(releasedAt);
    }

    @Test
    void landedCostUpdatePreservesWorkflowOwnedFields() {
        LocalDateTime postedAt = LocalDateTime.of(2026, 4, 11, 11, 30);
        LandedCostComponent entity = new LandedCostComponent();
        entity.setStatus("POSTED");
        entity.setAmount(new BigDecimal("50.00"));
        entity.setPostedAt(postedAt);

        LandedCostComponentUpdateRequest request = new LandedCostComponentUpdateRequest();
        request.setAmount(new BigDecimal("60.00"));
        request.setStatus("DRAFT");
        request.setPostedAt(LocalDateTime.of(2030, 1, 1, 0, 0));

        new LandedCostComponentMapper().updateEntity(entity, request);

        assertThat(entity.getAmount()).isEqualByComparingTo("60.00");
        assertThat(entity.getStatus()).isEqualTo("POSTED");
        assertThat(entity.getPostedAt()).isEqualTo(postedAt);
    }
}
