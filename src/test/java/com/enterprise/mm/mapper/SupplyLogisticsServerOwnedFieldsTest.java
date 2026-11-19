package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.LandedLogisticsEvent;
import com.enterprise.mm.payload.DeliveryScheduleCreateRequest;
import com.enterprise.mm.payload.DeliveryScheduleUpdateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventCreateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SupplyLogisticsServerOwnedFieldsTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DeliveryScheduleMapper deliveryScheduleMapper = new DeliveryScheduleMapper();
    private final LandedLogisticsEventMapper logisticsEventMapper = new LandedLogisticsEventMapper();

    @Test
    void deliveryScheduleCreateJsonCannotBindServerOwnedWorkflowFields() throws Exception {
        DeliveryScheduleCreateRequest request = objectMapper.readValue("""
            {
              "status": "RECEIVED",
              "receivedQuantity": 8,
              "receivedAmount": 80,
              "overdueFlag": true,
              "controlledAt": "2025-01-01T12:00:00",
              "plannedQuantity": 10
            }
            """, DeliveryScheduleCreateRequest.class);

        assertThat(request.getStatus()).isNull();
        assertThat(request.getReceivedQuantity()).isNull();
        assertThat(request.getReceivedAmount()).isNull();
        assertThat(request.isOverdueFlag()).isFalse();
        assertThat(request.getControlledAt()).isNull();
        assertThat(request.getPlannedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void deliveryScheduleUpdateJsonCannotBindServerOwnedWorkflowFields() throws Exception {
        DeliveryScheduleUpdateRequest request = objectMapper.readValue("""
            {
              "status": "CANCELLED",
              "receivedQuantity": 8,
              "receivedAmount": 80,
              "overdueFlag": true,
              "controlledAt": "2025-01-01T12:00:00",
              "plannedAmount": 100
            }
            """, DeliveryScheduleUpdateRequest.class);

        assertThat(request.getStatus()).isNull();
        assertThat(request.getReceivedQuantity()).isNull();
        assertThat(request.getReceivedAmount()).isNull();
        assertThat(request.isOverdueFlag()).isFalse();
        assertThat(request.getControlledAt()).isNull();
        assertThat(request.getPlannedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void deliveryScheduleMapperIgnoresServerOwnedWorkflowFieldsOnCreateAndUpdate() {
        DeliveryScheduleCreateRequest create = new DeliveryScheduleCreateRequest();
        create.setStatus("RECEIVED");
        create.setReceivedQuantity(BigDecimal.valueOf(8));
        create.setReceivedAmount(BigDecimal.valueOf(80));
        create.setOverdueFlag(true);
        create.setControlledAt(LocalDateTime.of(2025, 1, 1, 12, 0));

        DeliverySchedule created = deliveryScheduleMapper.toEntity(create);

        assertThat(created.getStatus()).isNull();
        assertThat(created.getReceivedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getReceivedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.isOverdueFlag()).isFalse();
        assertThat(created.getControlledAt()).isNull();

        DeliveryScheduleUpdateRequest update = new DeliveryScheduleUpdateRequest();
        update.setStatus("CANCELLED");
        update.setReceivedQuantity(BigDecimal.ONE);
        update.setReceivedAmount(BigDecimal.TEN);
        update.setOverdueFlag(false);
        update.setControlledAt(LocalDateTime.of(2030, 1, 1, 0, 0));
        created.setStatus("PARTIALLY_RECEIVED");
        created.setReceivedQuantity(BigDecimal.valueOf(4));
        created.setReceivedAmount(BigDecimal.valueOf(40));
        created.setOverdueFlag(true);
        created.setControlledAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        deliveryScheduleMapper.updateEntity(created, update);

        assertThat(created.getStatus()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(created.getReceivedQuantity()).isEqualByComparingTo("4");
        assertThat(created.getReceivedAmount()).isEqualByComparingTo("40");
        assertThat(created.isOverdueFlag()).isTrue();
        assertThat(created.getControlledAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void logisticsEventJsonCannotBindServerOwnedStatusOrResolutionTime() throws Exception {
        LandedLogisticsEventCreateRequest create = objectMapper.readValue("""
            {"status":"RESOLVED","resolvedAt":"2025-01-01T12:00:00","eventType":"BOOKED"}
            """, LandedLogisticsEventCreateRequest.class);
        LandedLogisticsEventUpdateRequest update = objectMapper.readValue("""
            {"status":"RESOLVED","resolvedAt":"2025-01-01T12:00:00","eventType":"BOOKED"}
            """, LandedLogisticsEventUpdateRequest.class);

        assertThat(create.getStatus()).isNull();
        assertThat(create.getResolvedAt()).isNull();
        assertThat(update.getStatus()).isNull();
        assertThat(update.getResolvedAt()).isNull();
    }

    @Test
    void logisticsEventMapperIgnoresServerOwnedStatusOrResolutionTime() {
        LocalDateTime attackerTime = LocalDateTime.of(2025, 1, 1, 12, 0);
        LandedLogisticsEventCreateRequest create = new LandedLogisticsEventCreateRequest();
        create.setStatus("RESOLVED");
        create.setResolvedAt(attackerTime);

        LandedLogisticsEvent created = logisticsEventMapper.toEntity(create);

        assertThat(created.getStatus()).isNull();
        assertThat(created.getResolvedAt()).isNull();

        LandedLogisticsEventUpdateRequest update = new LandedLogisticsEventUpdateRequest();
        update.setStatus("RESOLVED");
        update.setResolvedAt(attackerTime);
        LocalDateTime originalResolution = LocalDateTime.of(2026, 1, 1, 0, 0);
        created.setStatus("RECORDED");
        created.setResolvedAt(originalResolution);

        logisticsEventMapper.updateEntity(created, update);

        assertThat(created.getStatus()).isEqualTo("RECORDED");
        assertThat(created.getResolvedAt()).isEqualTo(originalResolution);
    }
}
