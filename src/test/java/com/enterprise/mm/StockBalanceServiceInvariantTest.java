package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.StockBalanceCreateRequest;
import com.enterprise.mm.payload.StockBalanceUpdateRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.StockBalanceService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StockBalanceServiceInvariantTest {

    @Autowired StockBalanceService stockBalanceService;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;

    private Material material;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("stock-invariant-" + UUID.randomUUID());
        CompanyContext.setCompany(UUID.randomUUID());
        material = material("MAT-" + suffix());
        warehouse = warehouse("WH-" + suffix());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void manualCreateRejectsNegativeFinancialSeed() {
        StockBalanceCreateRequest request = zeroRequest(material.getId(), warehouse.getId());
        request.setQuantity(BigDecimal.ONE.negate());

        assertThatThrownBy(() -> stockBalanceService.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must be created by posted stock movements");
    }

    @Test
    void nonEmptyBalanceCannotBeSoftDeleted() {
        StockBalance balance = persistedBalance(BigDecimal.ONE);

        assertThatThrownBy(() -> stockBalanceService.softDelete(balance.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");
        assertThat(stockBalanceRepository.findAnyById(balance.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void logicalKeyCannotBeRewrittenThroughGenericUpdate() {
        StockBalance balance = persistedBalance(BigDecimal.ZERO);
        Warehouse otherWarehouse = warehouse("WH-OTHER-" + suffix());
        StockBalanceUpdateRequest request = updateRequest(balance, otherWarehouse.getId());

        assertThatThrownBy(() -> stockBalanceService.update(balance.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("warehouseId is immutable");
        assertThat(stockBalanceRepository.findAnyById(balance.getId()).orElseThrow().getWarehouseId())
            .isEqualTo(warehouse.getId());
    }

    @Test
    void softDeletedBalanceCannotBeRestoredIntoTheActiveKeyspace() {
        StockBalance balance = persistedBalance(BigDecimal.ZERO);
        balance.softDelete();
        stockBalanceRepository.saveAndFlush(balance);

        assertThatThrownBy(() -> stockBalanceService.restore(balance.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("restore is not supported");
        assertThat(stockBalanceRepository.findAnyById(balance.getId()).orElseThrow().isDeleted()).isTrue();
    }

    private StockBalance persistedBalance(BigDecimal quantity) {
        StockBalance balance = new StockBalance();
        balance.setCode("BAL-" + suffix());
        balance.setName(balance.getCode());
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(quantity);
        balance.setAvailableQuantity(quantity);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(quantity);
        return stockBalanceRepository.saveAndFlush(balance);
    }

    private StockBalanceCreateRequest zeroRequest(UUID materialId, UUID warehouseId) {
        StockBalanceCreateRequest request = new StockBalanceCreateRequest();
        request.setMaterialId(materialId);
        request.setWarehouseId(warehouseId);
        request.setQuantity(BigDecimal.ZERO);
        request.setAvailableQuantity(BigDecimal.ZERO);
        request.setReservedQuantity(BigDecimal.ZERO);
        request.setQuarantineQuantity(BigDecimal.ZERO);
        request.setTotalValue(BigDecimal.ZERO);
        return request;
    }

    private StockBalanceUpdateRequest updateRequest(StockBalance balance, UUID warehouseId) {
        StockBalanceUpdateRequest request = new StockBalanceUpdateRequest();
        request.setCode(balance.getCode());
        request.setName(balance.getName());
        request.setStatus(balance.getStatus());
        request.setDescription(balance.getDescription());
        request.setMaterialId(balance.getMaterialId());
        request.setWarehouseId(warehouseId);
        request.setStorageLocationId(balance.getStorageLocationId());
        request.setStorageBinId(balance.getStorageBinId());
        request.setStockBatchId(balance.getStockBatchId());
        request.setQuantity(balance.getQuantity());
        request.setAvailableQuantity(balance.getAvailableQuantity());
        request.setReservedQuantity(balance.getReservedQuantity());
        request.setQuarantineQuantity(balance.getQuarantineQuantity());
        request.setTotalValue(balance.getTotalValue());
        request.setBalanceAt(balance.getBalanceAt());
        return request;
    }

    private Material material(String code) {
        Material entity = new Material();
        entity.setCode(code);
        entity.setName(code);
        entity.setStatus("ACTIVE");
        return materialRepository.saveAndFlush(entity);
    }

    private Warehouse warehouse(String code) {
        Warehouse entity = new Warehouse();
        entity.setCode(code);
        entity.setName(code);
        entity.setStatus("ACTIVE");
        return warehouseRepository.saveAndFlush(entity);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
