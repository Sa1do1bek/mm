package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.mapper.StockBalanceMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockBalancePrecisionResponseTest {
    private final StockBalanceMapper mapper = new StockBalanceMapper();
    @Test void approvedStockPrecisionAndHumanLabelDoNotChangeRecoveryIdentityOrQuantity() {
        var unit=new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setCode("1C-UOM-source-identity");
        unit.setMeasurementNameShort("шт [1C 0001]"); unit.setMeasurementNameShortRu("шт");
        unit.setUnitType(MeasurementUnitType.QUANTITY); unit.setStockQuantityScale(7);
        var material=new Material(); material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit);
        var balance=new StockBalance(); balance.setMaterial(material); balance.setQuantity(new BigDecimal("0.1234567"));
        var response=mapper.toResponse(balance);
        assertThat(response.getQuantityScale()).isEqualTo(7);
        assertThat(response.getUnitLabel()).isEqualTo("шт");
        assertThat(response.getUnitCode()).isEqualTo("1C-UOM-source-identity");
        assertThat(response.getQuantity()).isEqualByComparingTo("0.1234567");
        var batch=new StockBatch(); batch.setSerialNumber("serial-1"); balance.setStockBatch(batch);
        assertThat(mapper.toResponse(balance).getQuantityScale()).isZero();
    }
    @Test void absentLegacyPrecisionMetadataRetainsFourPlaceResponse() {
        assertThat(mapper.toResponse(new StockBalance()).getQuantityScale()).isEqualTo(4);
    }
}
