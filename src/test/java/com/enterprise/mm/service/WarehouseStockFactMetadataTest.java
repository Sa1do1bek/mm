package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class WarehouseStockFactMetadataTest {
    @Test void sourceAndDocumentReferenceRemainDistinctIncludingSeparators() {
        var payload = WarehouseStockFactMetadata.snapshot(" OOO Octo · Workshop ", "001");
        assertThat(WarehouseStockFactMetadata.source(payload, "ignored")).isEqualTo("OOO Octo · Workshop");
    }
    @Test void documentNoteAloneDoesNotBecomeTheSource() {
        assertThat(WarehouseStockFactMetadata.source(WarehouseStockFactMetadata.snapshot(null, "001"), "001")).isNull();
    }
    @Test void legacyManualFactsKeepTheirSavedOperationText() {
        assertThat(WarehouseStockFactMetadata.source(null, "OOO Octo · 001")).isEqualTo("OOO Octo · 001");
        assertThat(WarehouseStockFactMetadata.source(null, " ")).isNull();
    }
}
