package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.transfer.TransferActionPolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real JPA locking queries must not validate an entity cached before a database update. */
@SpringBootTest(properties = "mm.pilot.enabled-capabilities=SERIALIZED_EXECUTION")
@ActiveProfiles("test")
class TransferReceiptJpaFreshnessTest {
    @Autowired MaterialUnitRepository units;
    @Autowired MaterialUnitEligibilityService eligibility;
    @Autowired WarehouseRepository warehouses;
    @Autowired TransferDocumentRepository transfers;
    @Autowired TransferActionPolicy policy;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void scope() {
        TenantContext.setTenant("receipt-jpa"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("receipt-review", "",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void serialReceiptRejectsAnAlreadyReceivedUnitEvenWhenThePersistenceContextHasStaleState() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var unit = new MaterialUnit(); unit.setName("Receipt cache regression"); unit.setMaterialId(UUID.randomUUID());
            unit.setWarehouseId(UUID.randomUUID()); unit.setStockBatchId(UUID.randomUUID()); unit.setUnitStatus(MaterialUnitStatus.IN_TRANSIT);
            unit.setManufacturerSerialNumber("CACHE-" + UUID.randomUUID()); unit.setNormalizedSerialNumber(unit.getManufacturerSerialNumber());
            unit.setReceivedAt(LocalDateTime.now()); unit = units.saveAndFlush(unit);
            var cached = units.findById(unit.getId()).orElseThrow();
            // Native import/maintenance writers may change state without incrementing the JPA version.
            jdbc.update("update material_units set unit_status='IN_STOCK',status='IN_STOCK' where id=?", unit.getId());
            assertThat(cached.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_TRANSIT);
            UUID reference = UUID.randomUUID();
            assertThatThrownBy(() -> eligibility.receiveTransferWithDisposition(cached.getMaterialId(), cached.getWarehouseId(), cached.getStockBatchId(),
                cached.getManufacturerSerialNumber(), UUID.randomUUID(), null, null, UUID.randomUUID(), BigDecimal.ONE, List.of(cached.getId()),
                false, "TransferDocument", reference, UUID.randomUUID())).isInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("select count(*) from material_unit_events where source_id=?", Integer.class, reference)).isZero();
        });
    }

    @Test void receiptParentLockRefreshesCachedLifecycleAfterNativeUpdate() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var source = new Warehouse(); source.setName("Receipt source"); source.setStatus("ACTIVE"); source = warehouses.saveAndFlush(source);
            var destination = new Warehouse(); destination.setName("Receipt destination"); destination.setStatus("ACTIVE"); destination = warehouses.saveAndFlush(destination);
            var document = new TransferDocument(); document.setName("Receipt cache regression"); document.setStatus("IN_TRANSIT");
            document.setSourceWarehouseId(source.getId()); document.setDestinationWarehouseId(destination.getId()); document = transfers.saveAndFlush(document);
            Long originalVersion = document.getVersion();
            jdbc.update("update transfer_documents set status='RECEIVED' where id=?", document.getId());
            assertThat(document.getStatus()).isEqualTo("IN_TRANSIT");
            var locked = policy.lockAuthorized(document.getId(), TransferActionPolicy.Action.RECEIVE);
            assertThat(locked.getVersion()).isEqualTo(originalVersion);
            assertThat(locked.getStatus()).isEqualTo("RECEIVED");
        });
    }

    @Test void physicalUnitDispatchMembershipIsCheckedAgainstTheExactPosition() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var unit = new MaterialUnit(); unit.setName("Receipt position membership"); unit.setMaterialId(UUID.randomUUID());
            unit.setWarehouseId(UUID.randomUUID()); unit.setStockBatchId(UUID.randomUUID());
            unit.setManufacturerSerialNumber("LINE-" + UUID.randomUUID()); unit.setNormalizedSerialNumber(unit.getManufacturerSerialNumber());
            unit.setReceivedAt(LocalDateTime.now()); unit = units.saveAndFlush(unit);
            UUID documentId = UUID.randomUUID(), dispatchLine = UUID.randomUUID(), destination = UUID.randomUUID(), targetBatch = UUID.randomUUID();
            eligibility.transition(List.of(unit), MaterialUnitStatus.IN_TRANSIT, com.enterprise.mm.enums.MaterialUnitEventType.TRANSFER_DISPATCHED,
                com.enterprise.mm.enums.MaterialUnitOperationType.TRANSFER, "TransferDocument", documentId, dispatchLine, unit.getWarehouseId(), null, null, null);
            units.flush(); var selected = unit;
            assertThatThrownBy(() -> eligibility.receiveTransferWithDisposition(selected.getMaterialId(), selected.getWarehouseId(), selected.getStockBatchId(),
                selected.getManufacturerSerialNumber(), destination, null, null, targetBatch, BigDecimal.ONE, List.of(selected.getId()), false,
                "TransferDocument", documentId, UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getMessageKey()).isEqualTo("transfers.receipt.serialWrongLine"));
            eligibility.receiveTransferWithDisposition(selected.getMaterialId(), selected.getWarehouseId(), selected.getStockBatchId(), selected.getManufacturerSerialNumber(),
                destination, null, null, targetBatch, BigDecimal.ONE, List.of(selected.getId()), false, "TransferDocument", documentId, dispatchLine);
            assertThat(selected.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
            assertThat(selected.getStockBatchId()).isEqualTo(targetBatch);
        });
    }
}
