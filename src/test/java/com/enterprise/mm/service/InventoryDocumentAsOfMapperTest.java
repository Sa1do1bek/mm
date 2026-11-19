package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.mapper.InventoryDocumentMapper;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.payload.InventoryDocumentCreateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InventoryDocumentAsOfMapperTest {
    @Test void accountingCutoffRemainsSeparateFromCountingDatesAndCaptureTime() {
        var request = new InventoryDocumentCreateRequest();
        request.setStockAsOfDate(LocalDate.of(2026, 10, 1));
        request.setPlannedStartDate(LocalDate.of(2026, 10, 20));
        request.setPlannedEndDate(LocalDate.of(2026, 11, 20));

        var mapper = new InventoryDocumentMapper();
        var entity = mapper.toEntity(request);
        var response = mapper.toResponse(entity);

        assertThat(response.getStockAsOfDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(response.getPlannedStartDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(response.getPlannedEndDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(response.getSnapshotAt()).isNull();
    }

    @Test void movementBridgeIsShownSeparatelyFromPhysicalDifference() {
        var line = new InventoryItem();
        line.setAsOfQuantity(new BigDecimal("10"));
        line.setSystemQuantity(new BigDecimal("7"));
        line.setCountedQuantity(new BigDecimal("6"));
        line.setDifferenceQuantity(new BigDecimal("-1"));

        var response = new InventoryItemMapper().toResponse(line);

        assertThat(response.getMovementDelta()).isEqualByComparingTo("-3");
        assertThat(response.getDifferenceQuantity()).isEqualByComparingTo("-1");
    }
}
