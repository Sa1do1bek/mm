package com.enterprise.mm.erp.warehouse.outbound;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.warehouse.holding.*;
import com.enterprise.mm.warehouse.posting.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WarehouseOutboundHooksTest {
    @Test void centralFinalizerFlushesPersistedAllocationsBeforeCaptureAndPropagatesFailure(){
        var source=mock(StockMovementService.class,CALLS_REAL_METHODS);var repository=mock(StockMovementRepository.class);var entityManager=mock(jakarta.persistence.EntityManager.class);var outbound=mock(WarehouseOutboundService.class);when(outbound.captureEnabled()).thenReturn(true);ReflectionTestUtils.setField(source,"repository",repository);ReflectionTestUtils.setField(source,"entityManager",entityManager);ReflectionTestUtils.setField(source,"warehouseOutbound",outbound);var movement=new StockMovement();movement.setId(UUID.randomUUID());movement.setMovementType(MovementType.WRITE_OFF);
        ReflectionTestUtils.invokeMethod(source,"finalizePostedMovement",movement);var order=inOrder(repository,entityManager,outbound);order.verify(repository).save(movement);order.verify(outbound).captureEnabled();order.verify(entityManager).flush();order.verify(outbound).captureStockMovement(movement.getId());assertThat(movement.getPostedAt()).isNotNull();
        doThrow(new IllegalStateException("outbox unavailable")).when(outbound).captureStockMovement(movement.getId());assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(source,"finalizePostedMovement",movement)).isInstanceOf(IllegalStateException.class);
    }
    @Test void accountPostingCapturesAfterCommitEvidenceWithoutLegacyRequiresNewNotification(){
        TenantContext.setTenant("hook");CompanyContext.setCompany(UUID.randomUUID());try{var store=mock(PostingEvidenceStore.class);var legacy=mock(com.enterprise.mm.erp.ErpExchangeListener.class);var journal=new PostingJournalService(store,null,legacy);var outbound=mock(WarehouseOutboundService.class);ReflectionTestUtils.setField(journal,"warehouseOutbound",outbound);UUID resource=UUID.randomUUID();var command=new PostingCommand("WAREHOUSE","ISSUE","OWNED",resource,"key",LocalDate.of(2025,4,20),1,new ObjectMapper().createObjectNode());var rule=new PostingEvidenceStore.RuleEvidence(new PostingRule(UUID.randomUUID(),"WAREHOUSE","ISSUE","OWNED",1,LocalDate.of(2025,1,1),null,true,"1010","UZS"),"{}");when(store.find(any())).thenReturn(Optional.empty());when(store.rules(anyString(),any(),any())).thenReturn(List.of(rule));when(store.reserve(any(),anyString(),any(),any(),any())).thenReturn(true);var receipt=journal.execute(command,r->List.of(new PostingEffect(PostingEffectType.STOCK,UUID.randomUUID(),UUID.randomUUID(),"1010","PCS",BigDecimal.ONE.negate(),BigDecimal.ZERO,null)));var order=inOrder(store,outbound);order.verify(store).complete(eq(receipt.batchId()),any(),eq(receipt));order.verify(outbound).capturePosting(command,receipt);verifyNoInteractions(legacy);}finally{TenantContext.clear();CompanyContext.clear();}
    }
    @Test void holdingConsumptionCapturesOnlyRegistryStateAfterSaveAndFlush(){
        var repository=mock(WarehouseHoldingRepository.class);var handovers=mock(WarehouseHoldingHandoverRepository.class);var service=new WarehouseHoldingService(repository,handovers,mock(com.enterprise.mm.service.IdempotencyService.class));var outbound=mock(WarehouseOutboundService.class);when(outbound.captureEnabled()).thenReturn(true);ReflectionTestUtils.setField(service,"warehouseOutbound",outbound);var holding=new WarehouseHolding();holding.setId(UUID.randomUUID());holding.setActiveQuantity(new BigDecimal("3"));when(repository.findById(holding.getId())).thenReturn(Optional.of(holding));when(repository.save(holding)).thenReturn(holding);var result=service.consumeHolding(new HoldingCommands.ConsumeHoldingCommand("key",holding.getId(),BigDecimal.ONE,"test",LocalDate.of(2025,4,20)));var order=inOrder(repository,outbound);order.verify(repository).save(holding);order.verify(outbound).captureEnabled();order.verify(repository).flush();order.verify(outbound).captureHolding(holding.getId());assertThat(result).isNotNull();verify(outbound,never()).captureStockMovement(any());verify(outbound,never()).capturePosting(any(),any());
    }
}
