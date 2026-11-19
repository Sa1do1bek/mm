package com.enterprise.mm.service;

import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.MaterialReservationEvent;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.payload.MaterialReservationEventResponse;
import com.enterprise.mm.repository.MaterialReservationEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialReservationEventService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private final MaterialReservationEventRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public MaterialReservationEvent append(
        MaterialReservation reservation,
        StockBalance balance,
        String eventType,
        String idempotencyKey,
        Long expectedVersion,
        BigDecimal quantity,
        ReservationStockMutationService.Mutation mutation,
        String statusFrom,
        String reason,
        String evidenceReference
    ) {
        MaterialReservationEvent event = new MaterialReservationEvent();
        event.setTenantId(reservation.getTenantId());
        event.setCompanyId(reservation.getCompanyId());
        event.setCreatedAt(LocalDateTime.now(clock));
        event.setCreatedBy(username());
        event.setReservationId(reservation.getId());
        event.setSequenceNo(repository.maxSequence(
            reservation.getTenantId(), reservation.getCompanyId(), reservation.getId()) + 1);
        event.setEventType(eventType);
        event.setIdempotencyKey(idempotencyKey);
        event.setExpectedVersion(expectedVersion);
        event.setReservationVersion(reservation.getVersion() == null ? 0L : reservation.getVersion());
        event.setStockBalanceId(balance.getId());
        event.setMaterialId(reservation.getMaterialId());
        event.setWarehouseId(reservation.getWarehouseId());
        event.setStorageLocationId(reservation.getStorageLocationId());
        event.setStorageBinId(reservation.getStorageBinId());
        event.setStockBatchId(reservation.getStockBatchId());
        event.setDispositionSnapshot(reservation.getDispositionSnapshot());
        event.setUomIdSnapshot(reservation.getUomIdSnapshot());
        event.setUomCodeSnapshot(reservation.getUomCodeSnapshot());
        event.setQuantity(quantity);
        event.setOnHandDelta(mutation.onHandDelta());
        event.setAvailableDelta(mutation.availableDelta());
        event.setReservedDelta(mutation.reservedDelta());
        event.setTotalValueDelta(mutation.totalValueDelta());
        event.setStatusFrom(statusFrom);
        event.setStatusTo(reservation.getStatus());
        event.setReason(blankToNull(reason));
        event.setEvidenceReference(blankToNull(evidenceReference));
        return repository.saveAndFlush(event);
    }

    @Transactional(readOnly = true)
    public List<MaterialReservationEventResponse> list(MaterialReservation reservation) {
        return repository.findByTenantIdAndCompanyIdAndReservationIdOrderBySequenceNoAsc(
                reservation.getTenantId(), reservation.getCompanyId(), reservation.getId())
            .stream()
            .map(this::response)
            .toList();
    }

    private MaterialReservationEventResponse response(MaterialReservationEvent event) {
        return new MaterialReservationEventResponse(
            event.getId(), event.getSequenceNo(), event.getEventType(), event.getCreatedAt(), event.getCreatedBy(),
            event.getStockBalanceId(), event.getMaterialId(), event.getWarehouseId(), event.getStorageLocationId(),
            event.getStorageBinId(), event.getStockBatchId(), event.getDispositionSnapshot(), event.getUomIdSnapshot(),
            event.getUomCodeSnapshot(), event.getQuantity(), event.getOnHandDelta(), event.getAvailableDelta(),
            event.getReservedDelta(), event.getTotalValueDelta(), event.getStatusFrom(), event.getStatusTo(),
            event.getReason(), event.getEvidenceReference(), event.getReservationVersion()
        ).withUnitShortName(unitDisplay.shortName(event.getTenantId(), event.getCompanyId(), event.getUomIdSnapshot(), event.getUomCodeSnapshot()));
    }

    private String username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
