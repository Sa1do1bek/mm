package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.mapper.InventoryItemMapper;
import com.enterprise.mm.repository.InventoryItemRepository;
import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryScanResolutionServiceTest {
    @Test void duplicateBarcodesReturnEveryInScopeStockKeyIncludingSerialPrecision() {
        var access=mock(InventoryAccessService.class);var items=mock(InventoryItemRepository.class);
        var document=new InventoryDocument();document.setId(UUID.randomUUID());document.setTenantId("tenant");document.setCompanyId(UUID.randomUUID());
        var first=line(document,"Material A","Batch A",null);var second=line(document,"Material B","Batch B","SERIAL-1");
        var foreign=line(document,"Outside company","Other",null);foreign.setCompanyId(UUID.randomUUID());
        when(access.document(document.getId())).thenReturn(document);when(items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse("tenant",document.getId())).thenReturn(List.of(first,second,foreign));
        var service=new InventoryScanResolutionService(access,items,new InventoryItemMapper());
        assertThat(service.resolve(document.getId(),"COMMON-BARCODE")).extracting(o->o.materialName()).containsExactlyInAnyOrder("Material A","Material B");
        var serial=service.resolve(document.getId(),"SERIAL-1");assertThat(serial).hasSize(1);assertThat(serial.getFirst().lineId()).isEqualTo(second.getId());assertThat(serial.getFirst().quantityScale()).isZero();
        assertThat(service.resolve(document.getId(),"unknown")).isEmpty();
    }
    private InventoryItem line(InventoryDocument document,String name,String batchNumber,String serial) {
        var material=new Material();material.setName(name);material.setBarcode("COMMON-BARCODE");
        var batch=new StockBatch();batch.setBatchNumber(batchNumber);batch.setSerialNumber(serial);
        var line=new InventoryItem();line.setId(UUID.randomUUID());line.setCompanyId(document.getCompanyId());line.setMaterial(material);line.setStockBatch(batch);return line;
    }
}
