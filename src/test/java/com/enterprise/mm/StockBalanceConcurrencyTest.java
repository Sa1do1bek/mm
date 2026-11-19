package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.StockMovementService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StockBalanceConcurrencyTest {

    @Autowired StockMovementService stockMovementService;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired com.enterprise.mm.repository.CompanyRepository companies;
    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository units;
    @Autowired com.enterprise.mm.repository.UserRepository users;
    @Autowired com.enterprise.mm.repository.EmployeeRepository employees;
    @Autowired com.enterprise.mm.service.MaterialReservationService reservations;
    private final List<com.enterprise.mm.entity.User> workers = new ArrayList<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private String tenantId;
    private UUID companyId;
    private Material material;
    private Warehouse warehouse;
    private StockBatch batch;

    @BeforeEach
    void setUp() {
        tenantId = "stock-concurrency-" + UUID.randomUUID();
        var company = new com.enterprise.mm.entity.Company();
        company.setName("Concurrency factory"); company.setCode("C-" + suffix()); company.setStatus("ACTIVE");
        companyId = companies.saveAndFlush(company).getId();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        var unit = new com.enterprise.mm.entity.UnitOfMeasure(); unit.setName("Each"); unit.setCode("EA-" + suffix()); unit.setMeasurementNameShort("EA"); unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY); unit.setStatus("ACTIVE"); unit = units.saveAndFlush(unit);
        material = new Material();material.setUnitOfMeasureId(unit.getId());
        material.setCode("MAT-" + suffix());
        material.setName(material.getCode());
        material.setStatus("ACTIVE");
        material = materialRepository.saveAndFlush(material);

        warehouse = new Warehouse();
        warehouse.setCode("WH-" + suffix());
        warehouse.setName(warehouse.getCode());
        warehouse.setStatus("ACTIVE");
        warehouse = warehouseRepository.saveAndFlush(warehouse);
        for (String name : List.of("Otabek", "Sardor")) {
            var employee = new com.enterprise.mm.entity.Employee();employee.setName(name);employee.setWarehouse(warehouse);employee=employees.saveAndFlush(employee);
            var user = new com.enterprise.mm.entity.User();user.setName(name);user.setFullName(name);user.setUsername(name+suffix());user.setPasswordHash("unused-fixture");user.setEmployee(employee);user.setRoles(new java.util.HashSet<>(java.util.Set.of(com.enterprise.mm.enums.RoleName.WAREHOUSE_OPERATOR)));workers.add(users.saveAndFlush(user));
        }
        authenticate(0);

        batch = new StockBatch();
        batch.setCode("BATCH-" + suffix());
        batch.setName(batch.getCode());
        batch.setStatus("ACTIVE");
        batch.setMaterialId(material.getId());
        batch.setWarehouseId(warehouse.getId());
        batch.setBatchNumber(batch.getCode());
        batch.setQuantity(BigDecimal.ZERO);
        batch = stockBatchRepository.saveAndFlush(batch);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void concurrentFirstReceiptsCreateOneBalanceForSharedLotAndNullLocation() throws Exception {
        List<PostingResult> results = runConcurrently(
            receipt("RECEIPT-" + suffix(), BigDecimal.valueOf(2)),
            receipt("RECEIPT-" + suffix(), BigDecimal.valueOf(3))
        );

        assertThat(results).allMatch(PostingResult::succeeded);
        var balances = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            tenantId,
            material.getId(),
            warehouse.getId()
        );
        assertThat(balances).hasSize(1);
        assertThat(balances.getFirst().getStockBatchId()).isEqualTo(batch.getId());
        assertThat(balances.getFirst().getStorageLocationId()).isNull();
        assertThat(balances.getFirst().getStorageBinId()).isNull();
        assertThat(balances.getFirst().getQuantity()).isEqualByComparingTo("5.0000");
        assertThat(balances.getFirst().getAvailableQuantity()).isEqualByComparingTo("5.0000");
        assertThat(balances.getFirst().getTotalValue()).isEqualByComparingTo("5.0000");
        assertThat(stockBatchRepository.findByIdAndIsDeletedFalse(batch.getId()).orElseThrow().getQuantity())
            .isEqualByComparingTo("5.0000");
    }

    @Test
    void concurrentIssuesCannotDriveAvailableStockBelowZero() throws Exception {
        stockMovementService.createAndPost(receipt("SEED-" + suffix(), BigDecimal.valueOf(5)));

        List<PostingResult> results = runConcurrently(
            issue("ISSUE-" + suffix(), BigDecimal.valueOf(4)),
            issue("ISSUE-" + suffix(), BigDecimal.valueOf(4))
        );

        assertThat(results.stream().filter(PostingResult::succeeded)).hasSize(1);
        assertThat(results.stream().filter(result -> result.error() instanceof BusinessException)).hasSize(1);

        var balances = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            tenantId,
            material.getId(),
            warehouse.getId()
        );
        BigDecimal quantity = balances.stream().map(balance -> balance.getQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = balances.stream().map(balance -> balance.getAvailableQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(quantity).isEqualByComparingTo("1.0000");
        assertThat(available).isEqualByComparingTo("1.0000");
        assertThat(balances).allMatch(balance -> balance.getAvailableQuantity().signum() >= 0);
        assertThat(stockMovementRepository.findAll().stream()
            .filter(movement -> tenantId.equals(movement.getTenantId()))
            .filter(movement -> !movement.isDeleted())
            .filter(movement -> "POSTED".equals(movement.getStatus())))
            .hasSize(2);
    }

    @Test
    void concurrentReservationsCannotOverbookFiveUnits() throws Exception {
        stockMovementService.createAndPost(receipt("SEED-" + suffix(), BigDecimal.valueOf(5)));
        java.util.function.Supplier<Object> reserve = () -> {
            var command = new com.enterprise.mm.payload.MaterialReservationCreateRequest();
            command.setName("Four units for maintenance"); command.setMaterialId(material.getId()); command.setWarehouseId(warehouse.getId()); command.setStockBatchId(batch.getId()); command.setQuantity(BigDecimal.valueOf(4)); command.setReservationType("MANUAL");
            return reservations.create(command);
        };
        var results = runConcurrently(reserve, reserve);
        assertThat(results.stream().filter(PostingResult::succeeded)).hasSize(1);
        assertThat(results.stream().filter(result -> result.error() instanceof BusinessException)).hasSize(1);
        var balances = stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(tenantId, material.getId(), warehouse.getId());
        assertThat(balances).singleElement().satisfies(balance -> {
            assertThat(balance.getQuantity()).isEqualByComparingTo("5");
            assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("1");
            assertThat(balance.getReservedQuantity()).isEqualByComparingTo("4");
            assertThat(balance.getTotalValue()).isEqualByComparingTo("5");
        });
        assertThat(stockMovementRepository.findAll().stream().filter(m -> tenantId.equals(m.getTenantId()) && "POSTED".equals(m.getStatus()))).hasSize(1);
    }

    private List<PostingResult> runConcurrently(StockMovement first, StockMovement second) throws Exception {
        return runConcurrently(() -> stockMovementService.createAndPost(first), () -> stockMovementService.createAndPost(second));
    }

    private List<PostingResult> runConcurrently(java.util.function.Supplier<?> first, java.util.function.Supplier<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PostingResult>> futures = new ArrayList<>();
        for (java.util.function.Supplier<?> command : List.of(first, second)) {
            int actorIndex = futures.size();
            futures.add(executor.submit(() -> {
                TenantContext.setTenant(tenantId);
                CompanyContext.setCompany(companyId);
                try {
                    authenticate(actorIndex);
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    command.get();
                    return new PostingResult(true, null);
                } catch (Throwable error) {
                    return new PostingResult(false, rootCause(error));
                } finally {
                    TenantContext.clear();
                    CompanyContext.clear();
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<PostingResult> results = new ArrayList<>();
        for (Future<PostingResult> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    private StockMovement receipt(String code, BigDecimal quantity) {
        StockMovement movement = baseMovement(code, quantity);
        movement.setMovementType(MovementType.RECEIPT);
        movement.setStockBatchId(batch.getId());
        return movement;
    }

    private StockMovement issue(String code, BigDecimal quantity) {
        StockMovement movement = baseMovement(code, quantity);
        movement.setMovementType(MovementType.ISSUE);
        movement.setUnitCost(BigDecimal.ZERO);
        return movement;
    }

    private StockMovement baseMovement(String code, BigDecimal quantity) {
        StockMovement movement = new StockMovement();
        movement.setCode(code);
        movement.setName(code);
        movement.setCompanyId(companyId);
        movement.setMaterialId(material.getId());
        movement.setWarehouseId(warehouse.getId());
        movement.setQuantity(quantity);
        movement.setUnitCost(BigDecimal.ONE);
        return movement;
    }

    private void authenticate(int index) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(workers.get(index).getUsername(), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_WAREHOUSE_OPERATOR"))));
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record PostingResult(boolean succeeded, Throwable error) {
    }
}
