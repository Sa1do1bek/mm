package com.enterprise.mm.service;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class StockUomPolicyTest {
    Material material(String code, MeasurementUnitType type) {
        UnitOfMeasure u=new UnitOfMeasure(); u.setId(UUID.randomUUID()); u.setCode(code); u.setUnitType(type);
        Material m=new Material(); m.setUnitOfMeasureId(u.getId()); m.setUnitOfMeasure(u); return m;
    }
    @Test void sameStockUnitIsValid() { StockUomPolicy.purchasing(material("EA",MeasurementUnitType.QUANTITY), "ea", new BigDecimal("10.0000")); }
    @ParameterizedTest @ValueSource(strings={"BOX","KG","arbitrary",""})
    void rejectsUnsupportedConversion(String unit) { assertThatThrownBy(()->StockUomPolicy.purchasing(material("EA",MeasurementUnitType.QUANTITY),unit,BigDecimal.TEN)).hasMessageContaining("must match"); }
    @ParameterizedTest @ValueSource(strings={"0","-1","0.5","1.00001"})
    void rejectsInvalidEachQuantity(String quantity) { assertThatThrownBy(()->StockUomPolicy.purchasing(material("EA",MeasurementUnitType.QUANTITY),"EA",new BigDecimal(quantity))).isInstanceOf(com.enterprise.mm.error.BusinessException.class); }
    @Test void fractionalUnitsSupportFourPlacesWithoutRounding() {
        Material m=material("KG",MeasurementUnitType.WEIGHT);
        StockUomPolicy.purchasing(m,"KG",new BigDecimal("1.2345"));
        assertThatThrownBy(()->StockUomPolicy.purchasing(m,"KG",new BigDecimal("1.23456"))).hasMessageContaining("not rounded");
    }
    @Test void missingMasterUnitFailsClosed() { assertThatThrownBy(()->StockUomPolicy.quantity(new Material(),BigDecimal.ONE,false)).hasMessageContaining("approved stock UOM"); }

    @Test void explicitlyApprovedUnitsPermitSevenPlacesIncludingFractionalPieces() {
        for (Material m : new Material[]{material("KG", MeasurementUnitType.WEIGHT), material("EA", MeasurementUnitType.QUANTITY)}) {
            m.getUnitOfMeasure().setStockQuantityScale(7);
            BigDecimal exact = new BigDecimal("0.1234567");
            StockUomPolicy.quantity(m, exact, false);
            assertThat(StockUomPolicy.quantityScale(m)).isEqualTo(7);
            assertThat(exact.toPlainString()).isEqualTo("0.1234567");
            assertThatThrownBy(() -> StockUomPolicy.quantity(m, new BigDecimal("0.12345678"), false)).hasMessageContaining("not rounded");
        }
    }
    @Test void requiredSerialTrackingRemainsIntegralWithAnApprovedFractionalUnit() {
        Material m=material("EA", MeasurementUnitType.QUANTITY);
        m.getUnitOfMeasure().setStockQuantityScale(7);
        m.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        assertThat(StockUomPolicy.quantityScale(m)).isZero();
        assertThatThrownBy(()->StockUomPolicy.quantity(m,new BigDecimal("0.5"),false)).hasMessageContaining("0 decimal places");
    }
    @Test void scaleBoundsAndIntegerCapacityAreEnforcedWithoutRounding() {
        Material m=material("KG", MeasurementUnitType.WEIGHT);
        for (int invalid : new int[]{-1,8}) {
            m.getUnitOfMeasure().setStockQuantityScale(invalid);
            assertThatThrownBy(()->StockUomPolicy.quantityScale(m)).hasMessageContaining("between 0 and 7");
        }
        m.getUnitOfMeasure().setStockQuantityScale(7);
        StockUomPolicy.quantity(m,new BigDecimal("999999999999999.1234567"),false);
        assertThatThrownBy(()->StockUomPolicy.quantity(m,new BigDecimal("1000000000000000"),false)).hasMessageContaining("15 integer digits");
    }

}
