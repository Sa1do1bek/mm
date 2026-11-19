package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.error.BadRequestException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryCountMutationServiceTest {
    @Test void zeroIsAnObservationAndBlankRequiresAnExplicitClear() {
        assertThatCode(()->InventoryCountMutationService.validateQuantity(BigDecimal.ZERO,false,4,false)).doesNotThrowAnyException();
        assertThatCode(()->InventoryCountMutationService.validateQuantity(null,true,4,false)).doesNotThrowAnyException();
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(null,false,4,false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(BigDecimal.ZERO,true,4,false)).isInstanceOf(BadRequestException.class);
    }
    @Test void negativeOverscaleOverflowAndFractionalSerialObservationsAreRejected() {
        for(String value:new String[]{"-1","1.00001","1000000000000000"})assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(new BigDecimal(value),false,4,false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(new BigDecimal("0.5"),false,0,true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(new BigDecimal("2"),false,0,true)).isInstanceOf(BadRequestException.class);
        assertThatCode(()->InventoryCountMutationService.validateQuantity(new BigDecimal("1.0000"),false,0,true)).doesNotThrowAnyException();
    }

    @Test void sevenPlaceCountsUseConfiguredUnitPrecisionWhileLegacyCountsStillRejectIt() {
        BigDecimal exact = new BigDecimal("12.1234567");
        assertThatCode(()->InventoryCountMutationService.validateQuantity(exact,false,7,false)).doesNotThrowAnyException();
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(exact,false,4,false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(new BigDecimal("12.12345678"),false,7,false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateQuantity(new BigDecimal("0.5"),false,7,true)).isInstanceOf(BadRequestException.class);
    }
    @Test void countingResponseAndValidationShareTheApprovedPiecePrecisionAndSerialOverride() {
        var unit = new com.enterprise.mm.entity.UnitOfMeasure(); unit.setId(java.util.UUID.randomUUID()); unit.setCode("EA");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY); unit.setStockQuantityScale(7);
        var material = new com.enterprise.mm.entity.Material(); material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit);
        var line = new com.enterprise.mm.entity.InventoryItem(); line.setMaterial(material);
        var mapper = new com.enterprise.mm.mapper.InventoryItemMapper();
        assertThat(InventoryCountMutationService.quantityScale(line)).isEqualTo(7);
        assertThat(mapper.toResponse(line).getQuantityScale()).isEqualTo(7);
        line.setObservedSerialNumber("serial-1");
        assertThat(InventoryCountMutationService.quantityScale(line)).isZero();
        assertThat(mapper.toResponse(line).getQuantityScale()).isZero();
    }

}
