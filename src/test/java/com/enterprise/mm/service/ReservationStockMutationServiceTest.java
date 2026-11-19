package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.InsufficientStockException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReservationStockMutationServiceTest {
    private final ReservationStockMutationService service = new ReservationStockMutationService();

    @Test
    void reserveMovesAvailableToReservedWithoutChangingOnHandOrValue() {
        StockBalance balance = balance("100", "70", "30", "1000");

        var mutation = service.reserve(balance, new BigDecimal("20"));

        assertThat(balance.getQuantity()).isEqualByComparingTo("100");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("50");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("50");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("1000");
        assertThat(mutation.availableDelta()).isEqualByComparingTo("-20");
        assertThat(mutation.reservedDelta()).isEqualByComparingTo("20");
    }

    @Test
    void releaseReturnsReservedToAvailableExactlyOnce() {
        StockBalance balance = balance("100", "70", "30", "1000");

        var mutation = service.release(balance, new BigDecimal("10"));

        assertThat(balance.getQuantity()).isEqualByComparingTo("100");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("80");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("20");
        assertThat(mutation.availableDelta()).isEqualByComparingTo("10");
        assertThat(mutation.reservedDelta()).isEqualByComparingTo("-10");
    }

    @Test
    void consumeReducesOnHandAndReservedWhileAvailableStaysNetOfReservation() {
        StockBalance balance = balance("100", "70", "30", "1000");

        var mutation = service.consume(balance, new BigDecimal("10"));

        assertThat(balance.getQuantity()).isEqualByComparingTo("90");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("70");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("20");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("900");
        assertThat(mutation.onHandDelta()).isEqualByComparingTo("-10");
        assertThat(mutation.availableDelta()).isZero();
        assertThat(mutation.reservedDelta()).isEqualByComparingTo("-10");
        assertThat(mutation.totalValueDelta()).isEqualByComparingTo("-100");
    }

    @Test
    void mutationRejectsInsufficientAvailableOrReservedStock() {
        StockBalance balance = balance("100", "5", "10", "1000");

        assertThatThrownBy(() -> service.reserve(balance, new BigDecimal("6")))
            .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> service.release(balance, new BigDecimal("11")))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.consume(balance, new BigDecimal("11")))
            .isInstanceOf(BusinessException.class);
    }

    private StockBalance balance(String onHand, String available, String reserved, String value) {
        StockBalance balance = new StockBalance();
        balance.setQuantity(new BigDecimal(onHand));
        balance.setAvailableQuantity(new BigDecimal(available));
        balance.setReservedQuantity(new BigDecimal(reserved));
        balance.setTotalValue(new BigDecimal(value));
        return balance;
    }

    @Test
    void sevenPlaceReservationReleaseAndConsumptionPreserveExactQuantities() {
        var balance = balance("1.1234567", "1.1234567", "0", "100");
        var unit = new com.enterprise.mm.entity.UnitOfMeasure(); unit.setId(java.util.UUID.randomUUID()); unit.setCode("EA");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY); unit.setStockQuantityScale(7);
        var material = new com.enterprise.mm.entity.Material(); material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit);
        balance.setMaterial(material);
        service.reserve(balance,new BigDecimal("0.1234567"));
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("0.1234567");
        service.release(balance,new BigDecimal("0.0000007"));
        service.consume(balance,new BigDecimal("0.1234560"));
        assertThat(balance.getQuantity()).isEqualByComparingTo("1.0000007");
        assertThat(balance.getReservedQuantity()).isZero();
        assertThatThrownBy(()->service.reserve(balance,new BigDecimal("0.12345678"))).hasMessageContaining("not rounded");
        unit.setStockQuantityScale(null);
        assertThatThrownBy(()->service.reserve(balance,new BigDecimal("0.5"))).hasMessageContaining("0 decimal places");
    }
}
