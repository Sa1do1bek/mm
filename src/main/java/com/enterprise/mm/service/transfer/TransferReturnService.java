package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.TransferExceptionDtos.ReturnReceipt;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A quarantine return is a real linked reverse transfer with its own valued transit. */
@Service @RequiredArgsConstructor @Transactional
public class TransferReturnService {
    private final TransferDocumentRepository transfers;
    private final TransferDocumentLineRepository lines;
    private final TransferShipmentRepository shipments;
    private final TransferShipmentAllocationRepository allocations;
    private final TransferAllocationClearanceRepository clearances;
    private final StockMovementRepository movements;
    private final TransferPostingService posting;
    private final TransferTransitLedgerService transit;
    public UUID dispatch(TransferException c,LocalDate date,String key){
        if(date==null)throw new BusinessException("Actual return dispatch date is required");
        var original=transfers.findScopedForUpdate(c.getTransferDocumentId(),c.getTenantId(),c.getCompanyId()).orElseThrow();
        var origin=allocations.findById(c.getAllocationId()).orElseThrow();
        var receipt=clearances.findById(c.getQuarantineClearanceId()).orElseThrow();var received=movements.findById(receipt.getMovementId()).orElseThrow();
        var reverse=new TransferDocument();reverse.setName("Return of "+original.getName());reverse.setCode("TR-RETURN-"+UUID.randomUUID().toString().substring(0,8));
        reverse.setReturnOfTransferId(original.getId());reverse.setSourceWarehouseId(original.getDestinationWarehouseId());reverse.setDestinationWarehouseId(original.getSourceWarehouseId());
        reverse.setSourceLocationId(received.getStorageLocationId());reverse.setDestinationLocationId(original.getSourceLocationId());reverse.setPlannedDate(date);reverse.setStatus("APPROVED");transfers.saveAndFlush(reverse);
        var line=new TransferDocumentLine();line.setName("Quarantine stock return");line.setTransferDocumentId(reverse.getId());line.setMaterialId(origin.getMaterialId());line.setPlannedQuantity(c.getQuantity());
        line.setSourceStockBatchId(receipt.getDestinationStockBatchId());line.setSourceStorageLocationId(received.getStorageLocationId());lines.saveAndFlush(line);
        var shipment=new TransferShipment();shipment.setName("Physical quarantine return");shipment.setTransferDocumentId(reverse.getId());shipment.setDispatchedDate(date);shipment.setOperationKey(key);shipments.saveAndFlush(shipment);
        var movement=new StockMovement();movement.setMaterialId(origin.getMaterialId());movement.setWarehouseId(reverse.getSourceWarehouseId());movement.setStorageLocationId(received.getStorageLocationId());
        movement.setStorageBinId(received.getStorageBinId());movement.setStockBatchId(receipt.getDestinationStockBatchId());movement.setMovementDate(date);movement.setQuantity(c.getQuantity());
        posting.post(new TransferPostingContext(reverse.getId(),shipment.getId(),origin.getId(),TransferPostingContext.Kind.QUARANTINE_RETURN_DISPATCH,line.getId()),movement);
        line.setDispatchedQuantity(c.getQuantity());line.setInTransitQuantity(c.getQuantity());lines.save(line);
        reverse.setStatus("IN_TRANSIT");reverse.setDispatchedDate(date);reverse.setUpdatedAt(LocalDateTime.now());transfers.saveAndFlush(reverse);return reverse.getId();
    }
    public void receive(TransferException c,ReturnReceipt command,String key){
        if(command.receivedDate()==null)throw new BusinessException("Actual return receipt date is required");
        var reverse=transfers.findScopedForUpdate(c.getReverseTransferId(),c.getTenantId(),c.getCompanyId()).orElseThrow();
        var facts=allocations.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(c.getTenantId(),c.getCompanyId(),reverse.getId());
        BigDecimal remaining=command.quantity();
        var links=new ArrayList<>(c.getRelatedDocumentIds());
        for(var a:facts){
            if(remaining.signum()==0)break;var quantity=remaining.min(transit.balance(a.getId()).quantity());if(quantity.signum()==0)continue;
            var m=new StockMovement();m.setMaterialId(a.getMaterialId());m.setWarehouseId(reverse.getDestinationWarehouseId());m.setStorageLocationId(command.storageLocationId());m.setStorageBinId(command.storageBinId());
            m.setMovementDate(command.receivedDate());m.setQuantity(quantity);var result=posting.post(new TransferPostingContext(reverse.getId(),a.getShipmentId(),a.getId(),TransferPostingContext.Kind.QUARANTINE_RECEIPT),m);
            links.add(result.movementId());remaining=remaining.subtract(quantity);
        }
        if(remaining.signum()!=0)throw new BusinessException("Return quantity exceeds outstanding reverse shipment");c.setRelatedDocumentIds(links);
        var unresolved=facts.stream().map(a->transit.balance(a.getId()).quantity()).reduce(BigDecimal.ZERO,BigDecimal::add);
        var line=lines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(c.getTenantId(),reverse.getId()).getFirst();
        line.setInTransitQuantity(unresolved);line.setReceivedQuantity(line.getDispatchedQuantity().subtract(unresolved));lines.save(line);
        reverse.setStatus(unresolved.signum()==0?"RECEIVED":"IN_TRANSIT");reverse.setReceivedDate(command.receivedDate());reverse.setUpdatedAt(LocalDateTime.now());transfers.saveAndFlush(reverse);
    }
}
