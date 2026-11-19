package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.mapper.InventoryDocumentMapper;
import org.junit.jupiter.api.Test;

class InventoryDocumentNumberTest {
    @Test void displayNumberPreservesNameAndInternalReferencesAndDoesNotTruncateLargeNumbers() {
        var document = new InventoryDocument(); document.setName("Warehouse annual count");
        document.setCode("INVENTORYDOCUMENT-af75e3bd"); document.setInventorySequence(42L);
        var response = new InventoryDocumentMapper().toResponse(document);
        assertThat(response.getInventoryNumber()).isEqualTo("INV-000042");
        assertThat(response.getName()).isEqualTo("Warehouse annual count");
        assertThat(response.getCode()).isEqualTo("INVENTORYDOCUMENT-af75e3bd");
        document.setInventorySequence(1000001L); assertThat(document.getInventoryNumber()).isEqualTo("INV-1000001");
    }
}
