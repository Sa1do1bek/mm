package com.enterprise.mm.warehouse.execution.service;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.service.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.transfer.TransferProgressService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
class TransferWorkspaceContextTest {
    @ParameterizedTest @ValueSource(strings = {"receive", "tasks"})
    void assignedReceivingWarehouseOffersReceiveEvenWhenMoreStockCanStillBeDispatched(String action) {
        var transfers=mock(TransferDocumentService.class);
        var scope=mock(RoleDataScopeService.class);
        var destination=UUID.randomUUID();
        when(scope.effectiveWarehouseId(null)).thenReturn(destination);
        var service=new WarehouseExecutionWorkspaceService(mock(IssueRequestService.class),mock(MaterialReservationService.class),transfers,mock(MaterialReturnService.class),
            mock(AuditLogRepository.class),mock(IntegrationEventRepository.class),mock(UserRepository.class),mock(DepartmentRepository.class),scope,mock(DemandPlanItemRepository.class),mock(AssetManagementSettingsRepository.class));
        var d=new TransferDocumentResponse();d.setId(UUID.randomUUID());d.setStatus("IN_TRANSIT");d.setLines(List.of());d.setAllowedActions(List.of("dispatch","receive"));
        d.setSourceWarehouseId(UUID.randomUUID());d.setDestinationWarehouseId(destination);
        d.setUnitSummaries(List.of(
            new TransferProgressService.UnitSummary("KG", "kg", new java.math.BigDecimal("150"), new java.math.BigDecimal("150"), java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("150"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO),
            new TransferProgressService.UnitSummary("L", "l", new java.math.BigDecimal("60"), new java.math.BigDecimal("60"), java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("60"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        d.setInTransitAmount(new java.math.BigDecimal("12345.6789")); d.setBookCurrencyCode("UZS");
        d.setProgress(new TransferProgressService.Progress(true,true,false,List.of("UNDISPATCHED_REMAINDER","UNRESOLVED_TRANSIT")));
        when(transfers.search(any(),any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(d)));
        var result=service.queue(com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.DocumentType.TRANSFER,
            new com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueFilter(null,List.of(),null,action),org.springframework.data.domain.PageRequest.of(0,10));
        assertThat(result.items().getFirst().nextAction()).isEqualTo("RECEIVE");
        assertThat(result.items().getFirst().blockerCode()).isEqualTo("UNRESOLVED_TRANSIT");
        assertThat(result.appliedFilters().warehouseId()).isEqualTo(destination);
        var item = result.items().getFirst();
        assertThat(item.primaryQuantity()).isNull();
        assertThat(item.unitSummaries()).isEqualTo(d.getUnitSummaries());
        assertThat(item.inTransitAmount()).isEqualByComparingTo("12345.6789");
        assertThat(item.bookCurrencyCode()).isEqualTo("UZS");
    }
    @Test void detailIsIndependentOfQueueAndExplainsEveryCloseBlocker() {
        var transfers=mock(TransferDocumentService.class);
        var service=new WarehouseExecutionWorkspaceService(mock(IssueRequestService.class),mock(MaterialReservationService.class),transfers,mock(MaterialReturnService.class),
            mock(AuditLogRepository.class),mock(IntegrationEventRepository.class),mock(UserRepository.class),mock(DepartmentRepository.class),mock(RoleDataScopeService.class),mock(DemandPlanItemRepository.class),mock(AssetManagementSettingsRepository.class));
        var id=UUID.randomUUID();var d=new TransferDocumentResponse();d.setId(id);d.setVersion(8L);d.setStatus("DISCREPANCY_REVIEW");d.setLines(List.of());d.setAllowedActions(List.of());
        d.setProgress(new TransferProgressService.Progress(false,true,false,List.of("UNRESOLVED_TRANSIT","OPEN_EXCEPTIONS")));
        when(transfers.get(id)).thenReturn(d);
        var context=service.transferContext(id);
        assertThat(context.document().getId()).isEqualTo(id);
        assertThat(context.blockers()).extracting(b->b.code()).containsExactly("UNRESOLVED_TRANSIT","OPEN_EXCEPTIONS");
        assertThat(context.blockers()).allSatisfy(b->{assertThat(b.labelKey()).contains(b.code());assertThat(b.resolutionLink()).contains(id.toString());assertThat(b.version()).isEqualTo(8L);});
        verify(transfers).get(id);verify(transfers).receiptContext(d);verifyNoMoreInteractions(transfers);
    }
}
