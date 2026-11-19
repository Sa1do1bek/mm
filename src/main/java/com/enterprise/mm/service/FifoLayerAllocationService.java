package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.FifoLayerAllocationResponse;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FifoLayerAllocationService {
    private final StockMovementRepository movementRepository;
    private final StockCostMovementRepository costMovementRepository;
    private final InventoryCostLayerRepository costLayerRepository;

    @Transactional(readOnly = true)
    public FifoLayerAllocationResponse get(UUID movementId) {
        StockMovement movement = movementRepository.findByIdAndIsDeletedFalse(movementId)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .filter(entity -> CompanyContext.currentCompany().equals(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Stock movement not found: " + movementId));
        List<FifoLayerAllocationResponse.Allocation> allocations = costMovementRepository
            .findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), movement.getId()
            ).stream()
            .filter(costMovement -> costMovement.getCostLayerId() != null)
            .map(costMovement -> {
                InventoryCostLayer layer = costLayerRepository.findByIdAndIsDeletedFalse(costMovement.getCostLayerId())
                    .orElseThrow(() -> new NotFoundException("Inventory cost layer not found: " + costMovement.getCostLayerId()));
                return new FifoLayerAllocationResponse.Allocation(
                    layer.getId(),
                    layer.getSourceDocumentId(),
                    layer.getReceivedAt() == null ? null : layer.getReceivedAt().toLocalDate(),
                    costMovement.getQuantity(),
                    costMovement.getUnitCost(),
                    costMovement.getTotalCost(),
                    layer.getRemainingQuantity()
                );
            })
            .toList();
        return new FifoLayerAllocationResponse(movementId, allocations);
    }
}
