package com.enterprise.mm.service.inventory;

import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.payload.InventoryWorkspaceDtos.StockKeyOption;
import com.enterprise.mm.repository.InventoryItemRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryScanResolutionService {
    private final InventoryAccessService access;
    private final InventoryItemRepository items;
    private final InventoryItemMapper mapper;

    @Transactional(readOnly = true)
    public List<StockKeyOption> resolve(UUID documentId, String code) {
        var document = access.document(documentId);
        if (code == null || code.isBlank() || code.length() > 240) throw new BadRequestException("Enter a material, batch, barcode or serial code");
        String scanned = code.trim();
        return items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(document.getTenantId(), documentId).stream()
            .filter(i -> document.getCompanyId().equals(i.getCompanyId())).filter(i -> matches(i, scanned))
            .map(mapper::toResponse)
            .sorted(Comparator.comparing(i -> i.getId().toString()))
            .map(i -> new StockKeyOption(i.getId(), i.getMaterialName(), i.getUnitCode(), i.getWarehouseName(),
                i.getStorageLocationName(), i.getStorageBinName(), i.getBatchNumber(), i.getSerialNumber(), i.getQuantityScale()).withUnitShortName(i.getUnitShortName()))
            .toList();
    }
    private boolean matches(InventoryItem line, String scanned) {
        var material = line.getMaterial(); var batch = line.getStockBatch();
        return material != null && matches(scanned, material.getCode(), material.getBarcode(), material.getQrCode(), material.getRfidTag())
            || batch != null && matches(scanned, batch.getBatchNumber(), batch.getSerialNumber());
    }
    private boolean matches(String code, String... candidates) { return Arrays.stream(candidates).anyMatch(c -> c != null && code.equalsIgnoreCase(c)); }
}
