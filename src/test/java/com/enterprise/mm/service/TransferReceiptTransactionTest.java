package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.TransferReceiptConflictException;
import com.enterprise.mm.mapper.TransferDocumentMapper;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.transfer.*;
import com.enterprise.mm.warehouse.history.IssueSourceTransferGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring facade transaction + H2 writes/parent lock, with the stock posting boundary simulated. */
@SpringJUnitConfig(TransferReceiptTransactionTest.Config.class)
@org.springframework.test.context.TestPropertySource(properties = "app.warehouse.transfers.shipment-flow-enabled=true")
class TransferReceiptTransactionTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build(); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean Fixture fixture(JdbcTemplate jdbc) { return new Fixture(jdbc); }
        @Bean TransferDocumentService service(Fixture fixture) { return fixture.service(); }
    }

    @Autowired TransferDocumentService service;
    @Autowired Fixture f;
    @BeforeEach void setup() {
        scope(); f.reset();
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void receiptPostsGoodAndQuarantineAndReplayDoesNotPostTwice() {
        var request = f.request(); service.receive(f.document.getId(), request, "receipt-once");
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_writes where kind='GOOD_RECEIPT'", BigDecimal.class)).isEqualByComparingTo("6");
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_writes where kind='QUARANTINE_RECEIPT'", BigDecimal.class)).isEqualByComparingTo("2");
        assertThat(f.jdbc.queryForObject("select sum(book_value) from receipt_writes", BigDecimal.class)).isEqualByComparingTo("1100");
        int writes = f.jdbc.queryForObject("select count(*) from receipt_writes", Integer.class);
        service.receive(f.document.getId(), request, "receipt-once");
        assertThat(f.jdbc.queryForObject("select count(*) from receipt_writes", Integer.class)).isEqualTo(writes);
        assertThat(f.jdbc.queryForObject("select count(*) from receipt_idempotency", Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_remaining", BigDecimal.class)).isZero();
        assertThat(f.jdbc.queryForObject("select sum(book_value) from receipt_remaining", BigDecimal.class)).isZero();
    }

    @Test void failureAfterFirstPostingRollsBackAllWritesAndIdempotency() {
        f.failOnPosting = 2;
        assertThatThrownBy(() -> service.receive(f.document.getId(), f.request(), "receipt-failure")).hasMessageContaining("simulated posting failure");
        assertThat(f.jdbc.queryForObject("select count(*) from receipt_writes", Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_remaining", BigDecimal.class)).isEqualByComparingTo("8");
        assertThat(f.jdbc.queryForObject("select sum(book_value) from receipt_remaining", BigDecimal.class)).isEqualByComparingTo("1100");
        assertThat(f.jdbc.queryForObject("select count(*) from receipt_idempotency", Integer.class)).isZero();
    }

    @Test void simultaneousCommandsSerializeAndRejectStaleVersion() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Future<Object>>();
            for (int i = 0; i < 2; i++) {
                String key = "concurrent-" + i;
                jobs.add(executor.submit(() -> { scope(); try { start.await(); return service.receive(f.document.getId(), f.request(), key); }
                    catch (RuntimeException ex) { return ex; } finally { clear(); } }));
            }
            start.countDown(); var results = List.of(jobs.getFirst().get(20, TimeUnit.SECONDS), jobs.getLast().get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(TransferDocumentResponse.class::isInstance).count()).as("Concurrent outcomes: %s", results).isEqualTo(1);
            assertThat(results.stream().filter(TransferReceiptConflictException.class::isInstance).count()).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_writes", BigDecimal.class)).isEqualByComparingTo("8");
        } finally { executor.shutdownNow(); }
    }

    @Test void legacyPartialReceiptWithoutLocationStillPostsAndReplaysExactlyOnce() {
        var request = f.request(); request.setReceiptMode(null);
        var input = request.getLines().getFirst(); input.setRowKey(null); input.setShipmentAllocationId(f.allocations.getFirst().getId());
        input.setReceivedQuantity(new BigDecimal("2")); input.setDamagedQuantity(null); input.setStorageLocationId(null);
        service.receive(f.document.getId(), request, "legacy-partial");
        service.receive(f.document.getId(), request, "legacy-partial");
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_writes", BigDecimal.class)).isEqualByComparingTo("2");
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_remaining", BigDecimal.class)).isEqualByComparingTo("6");
        assertThat(f.jdbc.queryForObject("select count(*) from receipt_idempotency", Integer.class)).isEqualTo(1);
    }

    @Test void busyParentLockReturnsRetryableConflictWithoutPosting() {
        doThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout")).when(f.policy).lockAuthorized(f.document.getId(), TransferActionPolicy.Action.RECEIVE);
        assertThatThrownBy(() -> service.receive(f.document.getId(), f.request(), "busy-receipt"))
            .isInstanceOfSatisfying(com.enterprise.mm.error.ConflictException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo("transfers.receipt.busy"));
        assertThat(f.jdbc.queryForObject("select count(*) from receipt_writes", Integer.class)).isZero();
    }

    @Test void reusingSuccessfulKeyWithChangedQuantitiesIsRejectedWithoutAnotherPosting() {
        var request = f.request(); service.receive(f.document.getId(), request, "same-key");
        request.getLines().getFirst().setReceivedQuantity(new BigDecimal("5"));
        request.getLines().getFirst().setDamagedQuantity(new BigDecimal("3"));
        assertThatThrownBy(() -> service.receive(f.document.getId(), request, "same-key"))
            .isInstanceOf(com.enterprise.mm.error.ConflictException.class).hasMessageContaining("different request payload");
        assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_writes", BigDecimal.class)).isEqualByComparingTo("8");
    }

    @Test void simultaneousRetriesWithSameKeyBothReturnTheSameSuccessfulReceipt() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Future<TransferDocumentResponse>>();
            for (int i = 0; i < 2; i++) jobs.add(executor.submit(() -> { scope();
                try { start.await(); return service.receive(f.document.getId(), f.request(), "concurrent-retry"); } finally { clear(); } }));
            start.countDown();
            assertThat(jobs.getFirst().get(20, TimeUnit.SECONDS).getId()).isEqualTo(jobs.getLast().get(20, TimeUnit.SECONDS).getId());
            assertThat(f.jdbc.queryForObject("select sum(qty) from receipt_writes", BigDecimal.class)).isEqualByComparingTo("8");
            assertThat(f.jdbc.queryForObject("select count(*) from receipt_idempotency", Integer.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    private void scope() {
        TenantContext.setTenant("receipt-tx"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("head", "", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_HEAD"))));
    }

    static class Fixture {
        final JdbcTemplate jdbc;
        final TransferDocument document = new TransferDocument();
        final TransferDocumentRepository documents = mock(TransferDocumentRepository.class);
        final TransferDocumentLineRepository lines = mock(TransferDocumentLineRepository.class);
        final TransferShipmentAllocationRepository allocationRepo = mock(TransferShipmentAllocationRepository.class);
        final MaterialRepository materials = mock(MaterialRepository.class);
        final TransferPostingService posting = mock(TransferPostingService.class);
        final TransferTransitLedgerService transit = mock(TransferTransitLedgerService.class);
        final TransferProgressService progress = mock(TransferProgressService.class);
        final TransferActionPolicy policy = mock(TransferActionPolicy.class);
        final IdempotencyRecordRepository idem = mock(IdempotencyRecordRepository.class);
        final StorageLocationRepository locations = mock(StorageLocationRepository.class);
        final List<TransferShipmentAllocation> allocations = new ArrayList<>();
        final AtomicInteger postCount = new AtomicInteger();
        UUID locationId = UUID.randomUUID(); int failOnPosting;

        Fixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        TransferDocumentService service() {
            var service = new TransferDocumentService(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class), documents, lines, new TransferDocumentMapper(), mock(WarehouseRepository.class), materials,
                new IdempotencyService(idem, new ObjectMapper().findAndRegisterModules()), mock(AuditService.class), mock(AuditSnapshotSerializer.class),
                mock(RoleDataScopeService.class), posting, mock(StockPickingAllocationService.class), mock(TransferShipmentRepository.class), allocationRepo,
                mock(TransferAllocationClearanceRepository.class), transit, policy, progress, mock(MaterialUnitEligibilityService.class), mock(IssueSourceTransferGuard.class),
                new TransferReceiptService(transit, locations, mock(StorageBinRepository.class), materials));
            ReflectionTestUtils.setField(service, "shipmentFlowEnabled", true); return service;
        }
        void reset() {
            org.mockito.Mockito.reset(documents, lines, allocationRepo, materials, posting, transit, progress, policy, idem, locations);
            postCount.set(0); failOnPosting = 0; allocations.clear();
            jdbc.execute("drop all objects");
            jdbc.execute("create table receipt_parent(id uuid primary key, version bigint)");
            jdbc.execute("create table receipt_remaining(id uuid primary key, qty decimal(22,7), book_value decimal(19,4))");
            jdbc.execute("create table receipt_writes(kind varchar(50), qty decimal(22,7), book_value decimal(19,4))");
            jdbc.execute("create table receipt_idempotency(request_key varchar(160) primary key, hash varchar(100), response clob)");
            document.setId(UUID.randomUUID()); document.setTenantId("receipt-tx"); document.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); document.setVersion(0L);
            document.setSourceWarehouseId(UUID.randomUUID()); document.setDestinationWarehouseId(UUID.randomUUID()); document.setStatus("IN_TRANSIT");
            jdbc.update("insert into receipt_parent values(?,0)", document.getId());
            var material = new Material(); material.setId(UUID.randomUUID()); material.setTenantId("receipt-tx"); material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); material.setMaterialType(MaterialType.TMB);
            when(materials.findByIdAndIsDeletedFalse(material.getId())).thenReturn(Optional.of(material));
            var location = new StorageLocation(); location.setId(locationId); location.setTenantId("receipt-tx"); location.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID); location.setWarehouseId(document.getDestinationWarehouseId()); location.setStatus("ACTIVE");
            when(locations.findByIdAndIsDeletedFalse(locationId)).thenReturn(Optional.of(location));
            UUID shipment = UUID.randomUUID(), line = UUID.randomUUID(), batch = UUID.randomUUID();
            for (int i = 0; i < 2; i++) {
                var allocation = new TransferShipmentAllocation(); allocation.setId(UUID.randomUUID()); allocation.setShipmentId(shipment); allocation.setTransferLineId(line);
                allocation.setSourceStockBatchId(batch); allocation.setMaterialId(material.getId()); allocation.setQuantityScale(0); allocations.add(allocation);
                jdbc.update("insert into receipt_remaining values(?,?,?)", allocation.getId(), i == 0 ? 5 : 3, i == 0 ? 500 : 600);
            }
            when(allocationRepo.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc("receipt-tx", CompanyContext.DEFAULT_COMPANY_ID, document.getId())).thenReturn(allocations);
            when(policy.lockAuthorized(eq(document.getId()), eq(TransferActionPolicy.Action.RECEIVE))).thenAnswer(inv -> {
                Long version = jdbc.queryForObject("select version from receipt_parent where id=? for update", Long.class, document.getId());
                document.setVersion(version); return document;
            });
            when(progress.describe(any())).thenReturn(new TransferProgressService.Projection(Map.of(), new TransferProgressService.Progress(false, true, false, List.of()), List.of(), 0, null));
            when(transit.balance(any())).thenAnswer(inv -> jdbc.queryForObject("select qty,book_value from receipt_remaining where id=?",
                (rs, row) -> new TransferTransitLedgerService.TransitBalance(rs.getBigDecimal(1), rs.getBigDecimal(2)), (UUID) inv.getArgument(0)));
            when(posting.postWithEvidence(any(), any(), any())).thenAnswer(inv -> {
                if (postCount.incrementAndGet() == failOnPosting) throw new IllegalStateException("simulated posting failure");
                TransferPostingContext context = inv.getArgument(0); StockMovement movement = inv.getArgument(1);
                var balance = transit.balance(context.allocationId()); var value = TransferValueSplit.take(balance.quantity(), balance.bookValue(), movement.getQuantity());
                jdbc.update("insert into receipt_writes values(?,?,?)", context.kind().name(), movement.getQuantity(), value);
                jdbc.update("update receipt_remaining set qty=qty-?,book_value=book_value-? where id=?", movement.getQuantity(), value, context.allocationId());
                return new TransferPostingService.PostedTransferStock(UUID.randomUUID(), List.of(), movement.getQuantity(), value, UUID.randomUUID());
            });
            when(documents.saveAndFlush(any())).thenAnswer(inv -> {
                jdbc.update("update receipt_parent set version=version+1 where id=?", document.getId()); document.setVersion(document.getVersion() + 1); return document;
            });
            when(idem.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(anyString(), any(), anyString(), any(), anyString()))
                .thenAnswer(inv -> jdbc.query("select hash,response from receipt_idempotency where request_key=?", (rs, row) -> {
                    var record = new IdempotencyRecord(); record.setRequestHash(rs.getString(1)); record.setResponseJson(rs.getString(2)); return record;
                }, (String) inv.getArgument(4)).stream().findFirst());
            when(idem.save(any())).thenAnswer(inv -> { IdempotencyRecord record = inv.getArgument(0);
                jdbc.update("insert into receipt_idempotency values(?,?,?)", record.getIdempotencyKey(), record.getRequestHash(), record.getResponseJson()); return record; });
        }
        TransferReceiveRequest request() {
            var request = new TransferReceiveRequest(); request.setExpectedVersion(0L); request.setReceivedDate(LocalDate.now()); request.setReceiptMode(TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS);
            var a = allocations.getFirst(); var input = new TransferReceiveRequest.Line();
            input.setRowKey(TransferReceiptService.rowKey(a.getShipmentId(), a.getTransferLineId(), a.getSourceStockBatchId(), null));
            input.setReceivedQuantity(new BigDecimal("6")); input.setDamagedQuantity(new BigDecimal("2")); input.setConditionEvidence("Damaged"); input.setStorageLocationId(locationId);
            request.setLines(List.of(input)); return request;
        }
    }
}
