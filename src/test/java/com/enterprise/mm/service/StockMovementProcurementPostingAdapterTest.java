package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort.AcceptedReceipt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockMovementProcurementPostingAdapterTest {
    @Mock private StockMovementService stockMovementService;
    @Mock private StockAccountResolver stockAccountResolver;
    @InjectMocks private StockMovementProcurementPostingAdapter adapter;

    @Test
    void snapshotsResolvedAccountOnPurchaseReceipt() {
        UUID materialId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        when(stockAccountResolver.findForMaterial(materialId)).thenReturn(Optional.of(
            new StockAccountResolver.Resolution(UUID.randomUUID(), "1060", "Returnable packaging",
                UUID.randomUUID(), "MATERIAL_GROUP")));
        StockMovementResponse posted = new StockMovementResponse();
        posted.setId(movementId);
        posted.setMovementDate(LocalDate.of(2026, 11, 6));
        when(stockMovementService.postPurchasingReceipt(any())).thenReturn(posted);

        adapter.postAcceptedReceipt(new AcceptedReceipt("GR-1", "Receipt", materialId,
            UUID.randomUUID(), null, null, "B-1", BigDecimal.valueOf(4),
            BigDecimal.valueOf(125), UUID.randomUUID()));

        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementService).postPurchasingReceipt(movement.capture());
        assertThat(movement.getValue().getAccountCodeSnapshot()).isEqualTo("1060");
    }
}
