package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** An internal transaction-scoped capability. No request field or reference text can create it. */
@Service @RequiredArgsConstructor
public class InventoryAdjustmentPermitService {
    private record Permit(UUID documentId,long revision,String tenantId,UUID companyId) {}
    private final ThreadLocal<Permit> active=new ThreadLocal<>();
    private final com.enterprise.mm.service.inventory.InventoryRevisionService revisions;
    private final InventoryItemRepository items;
    private final InventoryScopeService scope;
    @Transactional(propagation=Propagation.MANDATORY)
    public <T>T withApprovedCount(UUID documentId,long countRevision,Supplier<T> posting) {
        var d=revisions.lock(documentId);
        if(!"APPROVED".equals(d.getStatus())||d.getApprovedCountRevision()==null||d.getApprovedCountRevision()!=countRevision||d.getCountRevision()!=countRevision)throw new BusinessException("Current count revision is not approved");
        scope.lock(d);
        var previous=active.get();active.set(new Permit(documentId,countRevision,d.getTenantId(),d.getCompanyId()));
        try{return posting.get();}finally{if(previous==null)active.remove();else active.set(previous);}
    }
    public boolean allows(StockMovement movement,InventoryDocument frozen) {
        var p=active.get();
        if(p==null||!p.documentId().equals(frozen.getId())||!p.documentId().equals(movement.getReferenceId())||!"InventoryDocument".equals(movement.getReferenceType())
            ||!p.companyId().equals(CompanyContext.currentCompany())||!p.tenantId().equals(TenantContext.currentTenant())||!p.companyId().equals(movement.getCompanyId())||!"APPROVED".equals(frozen.getStatus())
            ||!Objects.equals(frozen.getApprovedCountRevision(),p.revision())||frozen.getCountRevision()!=p.revision()||movement.getSourceInventoryItemId()==null)return false;
        return items.findByIdAndIsDeletedFalse(movement.getSourceInventoryItemId()).filter(i->p.documentId().equals(i.getInventoryDocumentId())&&p.companyId().equals(i.getCompanyId())&&p.tenantId().equals(i.getTenantId())
            && Objects.equals(i.getMaterialId(),movement.getMaterialId())&&Objects.equals(i.getWarehouseId()==null?frozen.getWarehouseId():i.getWarehouseId(),movement.getWarehouseId())
            && i.getCountedQuantity()!=null && i.getSystemQuantity()!=null && i.getLinkedStockMovementId()==null
            && i.getCountedQuantity().subtract(i.getSystemQuantity()).abs().compareTo(movement.getQuantity())==0
            && (i.getCountedQuantity().compareTo(i.getSystemQuantity())>0 ? movement.getMovementType()==com.enterprise.mm.enums.MovementType.ADJUSTMENT && i.getUnitCost().compareTo(movement.getUnitCost())==0 : i.getCountedQuantity().compareTo(i.getSystemQuantity())<0 && movement.getMovementType()==com.enterprise.mm.enums.MovementType.WRITE_OFF)
            && Objects.equals(frozen.getPostingDate(),movement.getMovementDate())
            && Objects.equals(i.getStorageLocationId(),movement.getStorageLocationId())&&Objects.equals(i.getStorageBinId(),movement.getStorageBinId())&&Objects.equals(i.getStockBatchId(),movement.getStockBatchId())).isPresent();
    }
}
