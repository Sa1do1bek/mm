package com.enterprise.mm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MetricStatus;
import com.enterprise.mm.payload.MaterialStockDetailDtos.*;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialStockDetailQuery;
import com.enterprise.mm.service.MaterialCommandViewAccessPolicy;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import com.enterprise.mm.service.MaterialStockDetailService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MaterialStockDetailTest extends MaterialStockDetailScenarios {
    @Override void initializeDatabase() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:detail-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    }
}

abstract class MaterialStockDetailScenarios {
    abstract void initializeDatabase();

    JdbcTemplate jdbc;
    MaterialStockDetailService service;
    MaterialCommandViewScopeGuard guard;
    InventoryAccountingSettingsRepository settings;
    final UUID company = UUID.randomUUID(), material = UUID.randomUUID(), warehouse = UUID.randomUUID();

    @BeforeEach
    void setup() {
        initializeDatabase();
        guard = mock(MaterialCommandViewScopeGuard.class);
        settings = mock(InventoryAccountingSettingsRepository.class);
        service = new MaterialStockDetailService(guard, new MaterialCommandViewAccessPolicy(),
            new MaterialStockDetailQuery(new NamedParameterJdbcTemplate(jdbc)), settings,
            Clock.fixed(Instant.parse("2026-10-26T07:00:00Z"), ZoneId.of("Asia/Tashkent")));
        // Only the read model's tables are required; this exercises real SQL rather than mocked query results.
        for (String sql : List.of(
            "create table if not exists warehouses (id uuid primary key, tenant_id varchar, company_id uuid, code varchar, name varchar, is_delete boolean default false)",
            "create table if not exists suppliers (id uuid primary key, tenant_id varchar, company_id uuid, code varchar, name varchar, is_delete boolean default false)",
            "create table if not exists purchase_orders (id uuid primary key, tenant_id varchar, company_id uuid, supplier_id uuid, code varchar, is_delete boolean default false)",
            "create table if not exists goods_receipts (id uuid primary key, tenant_id varchar, company_id uuid, code varchar, is_delete boolean default false)",
            "create table if not exists goods_receipt_lines (id uuid primary key, tenant_id varchar, company_id uuid, goods_receipt_id uuid, material_id uuid, supplier_id uuid, stock_movement_id uuid, is_delete boolean default false)",
            "create table if not exists goods_receipt_batches (id uuid primary key, tenant_id varchar, company_id uuid, goods_receipt_line_id uuid, stock_movement_id uuid, is_delete boolean default false)",
            "create table if not exists stock_batches (id uuid primary key, tenant_id varchar, company_id uuid, material_id uuid, batch_number varchar, is_delete boolean default false)",
            "create table if not exists stock_balances (id uuid primary key, tenant_id varchar, company_id uuid, material_id uuid, warehouse_id uuid, stock_batch_id uuid, quantity numeric(22,7), available_quantity numeric(22,7), reserved_quantity numeric(22,7), quarantine_quantity numeric(22,7), total_value numeric(19,4), is_delete boolean default false)",
            "create table if not exists stock_movements (id uuid primary key, tenant_id varchar, company_id uuid, material_id uuid, warehouse_id uuid, target_warehouse_id uuid, stock_batch_id uuid, target_stock_batch_id uuid, status varchar, quantity numeric(22,7), unit_cost numeric(19,6), movement_date date, posted_at timestamp, reference_type varchar, reference_id uuid, source_unit_price_snapshot numeric(19,6), source_currency_code_snapshot varchar, source_exchange_rate_snapshot numeric(19,8), is_delete boolean default false)",
            "create table if not exists warehouse_posting_batches (id uuid primary key, tenant_id varchar, company_id uuid, status varchar)",
            "create table if not exists warehouse_posting_effects (batch_id uuid, material_id uuid, warehouse_id uuid, stock_batch_id uuid, effect_type varchar)",
            "create table if not exists inventory_cost_layers (id uuid primary key, tenant_id varchar, company_id uuid, material_id uuid, warehouse_id uuid, stock_batch_id uuid, parent_cost_layer_id uuid, source_warehouse_id uuid, source_movement_id uuid, layer_type varchar, received_quantity numeric(22,7), remaining_quantity numeric(22,7), total_cost numeric(19,4), currency_code varchar, received_at timestamp, is_delete boolean default false)",
            "create table if not exists stock_movement_allocations (id uuid primary key, tenant_id varchar, company_id uuid, material_id uuid, movement_id uuid, balance_id uuid, allocation_sequence int, allocation_role varchar, source_warehouse_id_snapshot uuid, target_warehouse_id_snapshot uuid, source_stock_batch_id_snapshot uuid, target_stock_batch_id_snapshot uuid, source_batch_number_snapshot varchar, target_batch_number_snapshot varchar, source_storage_location_id_snapshot uuid, target_storage_location_id_snapshot uuid, source_storage_bin_id_snapshot uuid, target_storage_bin_id_snapshot uuid, on_hand_delta numeric(22,7), available_delta numeric(22,7), reserved_delta numeric(22,7), quarantine_delta numeric(22,7), total_value_delta numeric(19,4), unit_cost_snapshot numeric(19,6), currency_code_snapshot varchar)",
            "create table if not exists stock_movement_allocation_cost_layers (id uuid primary key, tenant_id varchar, company_id uuid, allocation_id uuid, lineage_sequence int, lineage_role varchar, cost_layer_id uuid, parent_cost_layer_id_snapshot uuid, quantity_delta numeric(22,7), value_delta numeric(19,4), unit_cost_snapshot numeric(19,6), currency_code_snapshot varchar)"
        )) jdbc.execute(sql);
        auth("ADMIN");
        Material m = new Material(); m.setId(material); m.setCode("M-1"); m.setName("Material");
        when(guard.requireGoods(eq(material), any())).thenAnswer(invocation -> new MaterialScope("t", company,
            material, m, invocation.getArgument(1)));
        var config = new InventoryAccountingSettings(); config.setBaseCurrencyCode("UZS");
        when(settings.findByTenantIdAndCompanyIdAndIsDeletedFalse("t", company)).thenReturn(Optional.of(config));
        jdbc.update("insert into warehouses(id,tenant_id,company_id,code,name) values (?,'t',?,'W-1','Main')", warehouse, company);
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void separatesTwoSuppliersPricesAndPreservesDecimalQuantities() {
        receipt("10", "10", "100", "2026-10-01", "Supplier A", null);
        receipt("10", "10", "120", "2026-10-12", "Supplier B", null);
        balance("20", "2200");
        Detail detail = detail(null, 0, 25);
        assertThat(detail.dataStatus()).isEqualTo(DataStatus.AVAILABLE);
        assertThat(detail.summary().onHand().value()).isEqualTo("20.0000000");
        assertThat(detail.summary().totalBookValue().value()).isEqualTo("2200.0000");
        assertThat(detail.origins().content()).extracting(o -> o.supplier().name()).containsExactly("Supplier B", "Supplier A");
        assertThat(detail.origins().content()).allMatch(o -> o.provenanceStatus() == ProvenanceStatus.VERIFIED);
        assertThat(detail.origins().content()).extracting(o -> o.purchaseUnitPrice().value()).containsExactly("120.000000", "100.000000");
        assertThat(detail.summary().quantityWithoutCostLayers().value()).isEqualTo("0.0000000");
    }

    @Test void sameBatchStillKeepsSeparateReceiptsAndOriginalCostAfterConsumption() {
        UUID batch = UUID.randomUUID();
        jdbc.update("insert into stock_batches(id,tenant_id,company_id,material_id,batch_number) values (?,'t',?,?,'B1')", batch, company, material);
        UUID layer = receipt("10", "5", "100", "2026-10-01", "A", batch);
        receipt("10", "10", "120", "2026-10-12", "B", batch);
        jdbc.update("update inventory_cost_layers set total_cost=450 where id=?", layer);
        balance("15", "1650");
        Detail detail = service.detail(material, new Filter(warehouse, batch, null, true), 0, 25);
        assertThat(detail.origins().totalElements()).isEqualTo(2);
        Origin consumed = detail.origins().content().stream().filter(o -> o.costLayerId().equals(layer)).findFirst().orElseThrow();
        assertThat(consumed.receiptUnitCost().value()).isEqualTo("100.000000");
        assertThat(consumed.currentUnitCost().value()).isEqualTo("90.000000");
        assertThat(consumed.remainingQuantity()).isEqualTo("5.0000000");
    }

    @Test void transferTracesOriginalSupplierAndDateAcrossWarehouses() {
        UUID parent = receipt("10", "0", "100", "2026-10-01", "A", null);
        UUID target = UUID.randomUUID();
        jdbc.update("insert into warehouses(id,tenant_id,company_id,name) values (?,'t',?,'Destination')", target, company);
        UUID move = movement("4", "100", "2026-10-20", null);
        UUID child = layer(target, move, "4", "4", "400", "TRANSFER_IN", parent, null);
        jdbc.update("update inventory_cost_layers set source_warehouse_id=? where id=?", warehouse, child);
        Detail detail = service.detail(material, new Filter(target, null, null, true), 0, 25);
        Origin origin = detail.origins().content().getFirst();
        assertThat(origin.supplier().name()).isEqualTo("A");
        assertThat(origin.originalReceivedDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(origin.warehouseReceivedDate()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(origin.sourceWarehouse().id()).isEqualTo(warehouse);
        assertThat(origin.receivedQuantity()).isEqualTo("4.0000000");
    }

    @Test void partialOpeningAndMissingLayersNeverInventSupplierOrDate() {
        UUID move = movement("10", "100", "2026-10-01", null);
        layer(warehouse, move, "10", "10", "1000", "OPENING_BALANCE", null, null);
        balance("20", "2000");
        Detail detail = detail(null, 0, 25);
        assertThat(detail.summary().quantityWithoutCostLayers().value()).isEqualTo("10.0000000");
        Origin origin = detail.origins().content().getFirst();
        assertThat(origin.provenanceStatus()).isEqualTo(ProvenanceStatus.UNKNOWN);
        assertThat(origin.supplier()).isNull();
        assertThat(origin.originalReceivedDate()).isNull();
        assertThat(origin.purchaseUnitPrice().status()).isEqualTo(MetricStatus.NO_DATA);
    }

    @Test void legacyReceiptWithoutPriceSnapshotDoesNotReadTodaysPurchaseOrderPrice() {
        UUID layer = receipt("10", "10", "100", "2026-10-01", "A", null);
        jdbc.update("update stock_movements set source_unit_price_snapshot=null where id=(select source_movement_id from inventory_cost_layers where id=?)", layer);
        assertThat(detail(null, 0, 25).origins().content().getFirst().purchaseUnitPrice().status()).isEqualTo(MetricStatus.NO_DATA);
    }

    @Test void hidesCostsSuppliersDocumentsForInventoryOnlyReader() {
        receipt("10", "10", "100", "2026-10-01", "A", null);
        balance("10", "1000");
        auth("WAREHOUSE_KEEPER");
        Detail detail = detail(null, 0, 25);
        Origin row = detail.origins().content().getFirst();
        assertThat(row.purchaseUnitPrice().status()).isEqualTo(MetricStatus.DENIED);
        assertThat(row.currentUnitCost().value()).isNull();
        assertThat(row.receiptUnitCost().value()).isNull();
        assertThat(row.remainingBookValue().value()).isNull();
        assertThat(row.purchaseExchangeRate()).isNull();
        assertThat(row.supplier()).isNull();
        assertThat(row.receiptDocument()).isNull();
        assertThat(row.originalMovementId()).isNull();
        assertThat(detail.summary().totalBookValue().status()).isEqualTo(MetricStatus.DENIED);
        assertThat(detail.summary().onHand().status()).isEqualTo(MetricStatus.AVAILABLE);
    }

    @Test void deniesUnauthenticatedAndNonInventoryRolesBeforeQueryingScope() {
        auth("SUPPLIER_MANAGER");
        assertThatThrownBy(() -> detail(null, 0, 25)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(guard);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> detail(null, 0, 25)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void rejectsFutureDateAndUnboundedPagination() {
        assertThatThrownBy(() -> detail(LocalDate.parse("2026-10-27"), 0, 25)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> detail(null, 0, 101)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> detail(null, -1, 25)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> detail(null, 0, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test void paginationHasStableTotalsAndDoesNotChangeSummary() {
        receipt("10", "10", "100", "2026-10-01", "A", null);
        receipt("10", "10", "120", "2026-10-12", "B", null);
        balance("20", "2200");
        Detail first = detail(null, 0, 1), second = detail(null, 1, 1);
        assertThat(first.origins().totalElements()).isEqualTo(2);
        assertThat(first.origins().totalPages()).isEqualTo(2);
        assertThat(first.origins().content().getFirst().costLayerId()).isNotEqualTo(second.origins().content().getFirst().costLayerId());
        assertThat(first.summary()).isEqualTo(second.summary());
    }

    @Test void tenantAndCompanyCannotLeakThroughBalancesLayersOrParentChain() {
        UUID foreign = receipt("10", "10", "100", "2026-10-01", "Foreign", null);
        jdbc.update("update inventory_cost_layers set company_id=? where id=?", UUID.randomUUID(), foreign);
        UUID move = movement("5", "100", "2026-10-12", null);
        layer(warehouse, move, "5", "5", "500", "TRANSFER_IN", foreign, null);
        UUID otherBalance = balance("999", "99900");
        jdbc.update("update stock_balances set tenant_id='other' where id=?", otherBalance);
        Detail detail = detail(null, 0, 25);
        assertThat(detail.summary().onHand().value()).isEqualTo("0");
        assertThat(detail.origins().totalElements()).isEqualTo(1);
        assertThat(detail.origins().content().getFirst().supplier()).isNull();
        assertThat(detail.origins().content().getFirst().provenanceStatus()).isEqualTo(ProvenanceStatus.UNKNOWN);
    }

    @Test void cyclesAndMissingParentsTerminateWithoutFabricatingOrigin() {
        UUID move = movement("5", "100", "2026-10-12", null);
        UUID id = layer(warehouse, move, "5", "5", "500", "TRANSFER_IN", null, null);
        jdbc.update("update inventory_cost_layers set parent_cost_layer_id=? where id=?", id, id);
        assertThat(detail(null, 0, 25).origins().content().getFirst().provenanceStatus()).isEqualTo(ProvenanceStatus.UNKNOWN);
    }

    @Test void historicalReceiptAndPartialIssueAreReconstructedFromSignedAllocations() {
        UUID layer = receipt("10", "6", "100", "2026-10-01", "A", null);
        UUID bal = balance("6", "600");
        UUID receipt = jdbc.queryForObject("select source_movement_id from inventory_cost_layers where id=?", UUID.class, layer);
        allocation(receipt, bal, layer, "10", "1000", "TARGET", warehouse);
        UUID issue = movement("4", "100", "2026-10-20", null);
        allocation(issue, bal, layer, "-4", "-400", "SOURCE", warehouse);
        Detail detail = detail(LocalDate.parse("2026-10-10"), 0, 25);
        assertThat(detail.dataStatus()).isEqualTo(DataStatus.AVAILABLE);
        assertThat(detail.historical()).isTrue();
        assertThat(detail.summary().onHand().value()).isEqualTo("10.0000000");
        assertThat(detail.origins().content().getFirst().remainingQuantity()).isEqualTo("10.0000000");
        assertThat(detail.origins().content().getFirst().remainingBookValue().value()).isEqualTo("1000.0000");
    }

    @Test void historicalGapsReturnNoDataInsteadOfCurrentStockWithAnOldDate() {
        receipt("10", "10", "100", "2026-10-01", "A", null);
        balance("10", "1000");
        Detail detail = detail(LocalDate.parse("2026-10-10"), 0, 25);
        assertThat(detail.dataStatus()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(detail.summary().onHand().status()).isEqualTo(MetricStatus.NO_DATA);
        assertThat(detail.origins().content()).isEmpty();
        assertThat(detail.warnings()).contains("HISTORICAL_EVIDENCE_INCOMPLETE");
    }

    @Test void netZeroUnallocatedHistoryCannotBeCertifiedByMatchingCurrentTotals() {
        UUID layer = receipt("10", "10", "100", "2026-10-01", "A", null);
        UUID bal = balance("10", "1000");
        UUID receipt = jdbc.queryForObject("select source_movement_id from inventory_cost_layers where id=?", UUID.class, layer);
        allocation(receipt, bal, layer, "10", "1000", "TARGET", warehouse);
        movement("2", "100", "2026-10-12", null);
        assertThat(detail(LocalDate.parse("2026-10-15"), 0, 25).dataStatus()).isEqualTo(DataStatus.UNAVAILABLE);
    }

    @Test void accountAwareStockPostingsMakeAllocationOnlyHistoryUnavailable() {
        UUID layer = receipt("10", "10", "100", "2026-10-01", "A", null);
        UUID bal = balance("10", "1000");
        UUID receipt = jdbc.queryForObject("select source_movement_id from inventory_cost_layers where id=?", UUID.class, layer);
        allocation(receipt, bal, layer, "10", "1000", "TARGET", warehouse);
        UUID batch = UUID.randomUUID();
        jdbc.update("insert into warehouse_posting_batches values (?,'t',?,'COMMITTED')", batch, company);
        jdbc.update("insert into warehouse_posting_effects values (?,?,?,null,'STOCK')", batch, material, warehouse);
        assertThat(detail(LocalDate.parse("2026-10-15"), 0, 25).dataStatus()).isEqualTo(DataStatus.UNAVAILABLE);
    }

    @Test void batchSpecificReceiptEvidenceWorksBeyondTheFirstMovementOnALine() {
        UUID layer = receipt("10", "10", "100", "2026-10-01", "A", null);
        UUID move = jdbc.queryForObject("select source_movement_id from inventory_cost_layers where id=?", UUID.class, layer);
        UUID line = jdbc.queryForObject("select id from goods_receipt_lines where stock_movement_id=?", UUID.class, move);
        jdbc.update("update goods_receipt_lines set stock_movement_id=null where id=?", line);
        jdbc.update("insert into goods_receipt_batches(id,tenant_id,company_id,goods_receipt_line_id,stock_movement_id) values (?,'t',?,?,?)",
            UUID.randomUUID(), company, line, move);
        Origin origin = detail(null, 0, 25).origins().content().getFirst();
        assertThat(origin.provenanceStatus()).isEqualTo(ProvenanceStatus.VERIFIED);
        assertThat(origin.receiptDocument().type()).isEqualTo("GoodsReceipt");
    }

    @Test void reversalKeepsOriginalEvidenceInHistoryAndRestoresCurrentQuantity() {
        UUID layer = receipt("10", "10", "100", "2026-10-01", "A", null);
        UUID bal = balance("10", "1000");
        UUID receipt = jdbc.queryForObject("select source_movement_id from inventory_cost_layers where id=?", UUID.class, layer);
        allocation(receipt, bal, layer, "10", "1000", "TARGET", warehouse);
        UUID issue = movement("4", "100", "2026-10-12", null);
        allocation(issue, bal, layer, "-4", "-400", "SOURCE", warehouse);
        jdbc.update("update stock_movements set status='REVERSED' where id=?", issue);
        UUID reversal = movement("4", "100", "2026-10-20", null);
        allocation(reversal, bal, layer, "4", "400", "TARGET", warehouse);
        assertThat(detail(LocalDate.parse("2026-10-15"), 0, 25).summary().onHand().value()).isEqualTo("6.0000000");
        assertThat(detail(null, 0, 25).summary().onHand().value()).isEqualTo("10.0000000");
    }

    @Test void movementDetailShowsActualDeltasAndRestrictsAllocationsToWarehouse() {
        UUID layer = receipt("10", "6", "100", "2026-10-01", "A", null);
        UUID bal = balance("6", "600");
        UUID issue = movement("4", "100", "2026-10-20", null);
        allocation(issue, bal, layer, "-4", "-400", "SOURCE", warehouse);
        MovementDetail detail = service.movement(material, issue, warehouse, 0, 25);
        assertThat(detail.documentQuantity()).isEqualTo("4.0000000");
        assertThat(detail.allocations().content().getFirst().onHandDelta()).isEqualTo("-4.0000000");
        assertThat(detail.allocations().content().getFirst().costLayers()).hasSize(1);
        assertThatThrownBy(() -> service.movement(material, issue, UUID.randomUUID(), 0, 25)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.movement(material, UUID.randomUUID(), warehouse, 0, 25)).isInstanceOf(NotFoundException.class);
        auth("WAREHOUSE_KEEPER");
        assertThat(service.movement(material, issue, warehouse, 0, 25).allocations().content().getFirst()
            .costLayers().getFirst().valueDelta().status()).isEqualTo(MetricStatus.DENIED);
    }

    @Test void mixedLayerCurrenciesAreNotAddedAsBaseCurrencyMoney() {
        UUID id = receipt("10", "10", "100", "2026-10-01", "A", null);
        jdbc.update("update inventory_cost_layers set currency_code='USD' where id=?", id);
        balance("10", "1000");
        Detail detail = detail(null, 0, 25);
        assertThat(detail.summary().costLayerBookValue().status()).isEqualTo(MetricStatus.NO_DATA);
        assertThat(detail.summary().bookValueDifference().status()).isEqualTo(MetricStatus.NO_DATA);
        assertThat(detail.origins().content().getFirst().remainingBookValue().currencyCode()).isEqualTo("USD");
    }

    private Detail detail(LocalDate date, int page, int size) {
        return service.detail(material, new Filter(warehouse, null, date, true), page, size);
    }
    private void auth(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    private UUID receipt(String received, String remaining, String price, String date, String supplierName, UUID batch) {
        UUID supplier = UUID.randomUUID(), po = UUID.randomUUID(), gr = UUID.randomUUID();
        jdbc.update("insert into suppliers(id,tenant_id,company_id,name) values (?,'t',?,?)", supplier, company, supplierName);
        jdbc.update("insert into purchase_orders(id,tenant_id,company_id,supplier_id,code) values (?,'t',?,?,'PO')", po, company, supplier);
        UUID movement = movement(received, price, date, po);
        jdbc.update("insert into goods_receipts(id,tenant_id,company_id,code) values (?,'t',?,'GR')", gr, company);
        jdbc.update("insert into goods_receipt_lines(id,tenant_id,company_id,goods_receipt_id,material_id,supplier_id,stock_movement_id) values (?,'t',?,?,?,?,?)",
            UUID.randomUUID(), company, gr, material, supplier, movement);
        return layer(warehouse, movement, received, remaining, new BigDecimal(remaining).multiply(new BigDecimal(price)).toPlainString(), "RECEIPT", null, batch);
    }
    private UUID movement(String qty, String price, String date, UUID po) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stock_movements(id,tenant_id,company_id,material_id,status,quantity,unit_cost,movement_date,posted_at,reference_type,reference_id,source_unit_price_snapshot,source_currency_code_snapshot,source_exchange_rate_snapshot) values (?,'t',?,?,'POSTED',?,?,cast(? as date),cast(? as date),'PurchaseOrder',?,?,'UZS',1)",
            id, company, material, number(qty), number(price), date, date, po, number(price));
        jdbc.update("update stock_movements set warehouse_id=? where id=?", warehouse, id);
        return id;
    }
    private UUID layer(UUID wh, UUID movement, String received, String remaining, String value, String type, UUID parent, UUID batch) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory_cost_layers(id,tenant_id,company_id,material_id,warehouse_id,stock_batch_id,parent_cost_layer_id,source_movement_id,layer_type,received_quantity,remaining_quantity,total_cost,currency_code,received_at) values (?,'t',?,?,?,?,?,?,?,?,?,?,'UZS',(select posted_at from stock_movements where id=?))",
            id, company, material, wh, batch, parent, movement, type, number(received), number(remaining), number(value), movement);
        return id;
    }
    private UUID balance(String qty, String value) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stock_balances(id,tenant_id,company_id,material_id,warehouse_id,quantity,available_quantity,reserved_quantity,quarantine_quantity,total_value) values (?,'t',?,?,?,?,?,0,0,?)",
            id, company, material, warehouse, number(qty), number(qty), number(value));
        return id;
    }
    private UUID allocation(UUID movement, UUID balance, UUID layer, String qty, String value, String role, UUID wh) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stock_movement_allocations(id,tenant_id,company_id,material_id,movement_id,balance_id,allocation_sequence,allocation_role,source_warehouse_id_snapshot,target_warehouse_id_snapshot,on_hand_delta,available_delta,reserved_delta,quarantine_delta,total_value_delta,unit_cost_snapshot,currency_code_snapshot) values (?,'t',?,?,?,?,1,?,?,?,?,?,0,0,?,100,'UZS')",
            id, company, material, movement, balance, role, role.equals("SOURCE") ? wh : null, role.equals("TARGET") ? wh : null, number(qty), number(qty), number(value));
        jdbc.update("insert into stock_movement_allocation_cost_layers(id,tenant_id,company_id,allocation_id,lineage_sequence,lineage_role,cost_layer_id,quantity_delta,value_delta,unit_cost_snapshot,currency_code_snapshot) values (?,'t',?,?,1,'CREATED',?,?,?,100,'UZS')",
            UUID.randomUUID(), company, id, layer, number(qty), number(value));
        return id;
    }
    private static BigDecimal number(String value) { return new BigDecimal(value); }
}
