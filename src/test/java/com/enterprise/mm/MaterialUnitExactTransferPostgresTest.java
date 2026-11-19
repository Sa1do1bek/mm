package com.enterprise.mm;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.service.MaterialUnitEligibilityService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties="mm.pilot.enabled-capabilities=SERIALIZED_EXECUTION")
@ActiveProfiles("test")
@EntityScan(basePackages={"com.enterprise.mm.entity","com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity","com.enterprise.mm.common","com.enterprise.mm.warehouse"})
@Testcontainers(disabledWithoutDocker=false)
class MaterialUnitExactTransferPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);
    }
    @Autowired MaterialUnitRepository units;
    @Autowired MaterialUnitEligibilityService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final UUID company=UUID.randomUUID(), material=UUID.randomUUID(), warehouse=UUID.randomUUID(), location=UUID.randomUUID();
    void scope(){TenantContext.setTenant("exact-transfer");CompanyContext.setCompany(company);}
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();}

    @Test void lockedSerialRefreshRejectsAnEntityMovedAfterPickingRead() {
        UUID id=unit();UUID movedTo=UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var cached=units.findById(id).orElseThrow();assertThat(cached.getStorageLocationId()).isEqualTo(location);
            var independent=new TransactionTemplate(transactions);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            // Simulate an external writer that does not increment the JPA version.
            // Versioned JPA edits are already rejected by optimistic locking.
            independent.executeWithoutResult(inner -> jdbc.update("update material_units set storage_location_id=? where id=?",movedTo,id));
            dispatch(id);
        })).hasMessageContaining("WRONG_LOCATION");
        var actual=units.findById(id).orElseThrow();
        assertThat(actual.getStorageLocationId()).isEqualTo(movedTo);
        assertThat(actual.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
    }

    @Test void lockedSerialFailsImmediatelyAndCanBeDispatchedAfterRelease() throws Exception {
        UUID id=unit();var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(() -> {scope();try {new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                units.findAllForUpdate(TenantContext.currentTenant(),company,List.of(id));held.countDown();
                try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Release timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            });}finally{clear();}});
            try {
                assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> dispatch(id)))
                    .isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
            } finally {release.countDown();}
            holder.get(20,TimeUnit.SECONDS);
        }
        assertThat(units.findById(id).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> dispatch(id));
        assertThat(units.findById(id).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_TRANSIT);
    }
    void dispatch(UUID id){service.dispatchTransferExact(material,warehouse,location,null,BigDecimal.ONE,List.of(id),"TransferDocument",UUID.randomUUID(),UUID.randomUUID());}
    UUID unit(){scope();var u=new MaterialUnit();u.setName("Exact transfer serial");u.setMaterialId(material);u.setWarehouseId(warehouse);
        u.setStorageLocationId(location);u.setStockBatchId(UUID.randomUUID());u.setManufacturerSerialNumber("EXACT-"+UUID.randomUUID());
        u.setNormalizedSerialNumber(u.getManufacturerSerialNumber());u.setReceivedAt(LocalDateTime.now());return units.saveAndFlush(u).getId();}
}
