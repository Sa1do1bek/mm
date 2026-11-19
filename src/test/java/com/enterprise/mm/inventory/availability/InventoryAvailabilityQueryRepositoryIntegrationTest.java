package com.enterprise.mm.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.inventory.availability.enums.InventoryAvailabilityQueueKey;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.repository.InventoryAvailabilityQueryRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryAvailabilityQueryRepositoryIntegrationTest {
    @Autowired InventoryAvailabilityQueryRepository repository;
    @Autowired EntityManager entityManager;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void shortageQueueIncludesDemandWithoutStockAndKeepsCountListCompanyParity() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-inventory-availability");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-A", companyId);

        Material stocked = material("MAT-STOCKED", "Stocked material", companyId);
        UnitOfMeasure unit = base(new UnitOfMeasure(), "1_GR", "Gram", companyId);
        unit.setMeasurementNameShortRu("г");
        unit.setMeasurementNameLongRu("Грамм");
        entityManager.persist(unit);
        stocked.setUnitOfMeasureId(unit.getId());
        stock(stocked, warehouse, new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO, companyId);
        demand(stocked, new BigDecimal("9"), companyId);

        Material noStock = material("MAT-NO-STOCK", "Demand without stock", companyId);
        demand(noStock, new BigDecimal("7"), companyId);

        Material otherCompany = material("MAT-OTHER", "Other company", UUID.randomUUID());
        demand(otherCompany, new BigDecimal("20"), otherCompany.getCompanyId());
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                InventoryAvailabilityQueueKey.SHORTAGE, null, null, null,
                null, null, null, null, null);
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(repository.count(filter)).isEqualTo(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(row -> row.materialCode())
            .containsExactly("MAT-NO-STOCK", "MAT-STOCKED");
        var stockedRow = page.getContent().stream()
            .filter(row -> row.materialId().equals(stocked.getId())).findFirst().orElseThrow();
        assertThat(stockedRow.available()).isEqualByComparingTo("5");
        assertThat(stockedRow.openDemand()).isEqualByComparingTo("9");
        assertThat(stockedRow.projectedAvailable()).isEqualByComparingTo("-4");
        assertThat(stockedRow.shortage()).isEqualByComparingTo("4");
        assertThat(stockedRow.unitOfMeasureCode()).isEqualTo("1_GR");
        assertThat(stockedRow.unitOfMeasureShortNameRu()).isEqualTo("г");
        assertThat(stockedRow.unitOfMeasureNameRu()).isEqualTo("Грамм");
    }

    @Test
    void activeReservationIsEvidenceWithoutBeingDeductedTwiceFromAvailability() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-inventory-reservation-evidence");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-RES", companyId);
        Material material = material("MAT-RES", "Reserved material", companyId);
        stock(material, warehouse, new BigDecimal("100"), new BigDecimal("70"), new BigDecimal("30"), companyId);
        reservation(material, warehouse, new BigDecimal("30"), companyId);
        entityManager.flush();

        var page = repository.search(
                new InventoryAvailabilityFilter(null, null, null, null,
                        null, null, null, null, null),
                PageRequest.of(0, 20));
        var row = page.getContent().stream()
            .filter(candidate -> candidate.materialId().equals(material.getId()))
            .findFirst()
            .orElseThrow();

        assertSoftly(softly -> {
            softly.assertThat(row.onHand()).isEqualByComparingTo("100");
            softly.assertThat(row.available()).isEqualByComparingTo("70");
            softly.assertThat(row.reserved()).isEqualByComparingTo("30");
            softly.assertThat(row.openDemand()).isEqualByComparingTo("0");
            softly.assertThat(row.openSupply()).isEqualByComparingTo("0");
            softly.assertThat(row.projectedAvailable()).isEqualByComparingTo("70");
            softly.assertThat(row.shortage()).isEqualByComparingTo("0");
        });
    }

    // ---- Item 1: filters (replacementMissing, staleBalance, nonMoving, negativeProjected, urgency) ----

    @Test
    void replacementMissingFilterExcludesMaterialsThatAlreadyHaveAReplacement() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-filter-replacement");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-REPL", companyId);

        Material replacement = material("MAT-REPL-NEW", "Replacement material", companyId);
        stock(replacement, warehouse, new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, companyId);

        Material withReplacement = material("MAT-HAS-REPL", "Has replacement", companyId);
        withReplacement.setReplacementMaterialId(replacement.getId());
        entityManager.persist(withReplacement);
        stock(withReplacement, warehouse, new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO, companyId);
        demand(withReplacement, new BigDecimal("5"), companyId);

        Material withoutReplacement = material("MAT-NO-REPL", "No replacement", companyId);
        stock(withoutReplacement, warehouse, new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO, companyId);
        demand(withoutReplacement, new BigDecimal("5"), companyId);
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                null, null, null, null, Boolean.TRUE, null, null, null, null);
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(row -> row.materialCode())
                .contains("MAT-NO-REPL")
                .doesNotContain("MAT-HAS-REPL");
    }

    @Test
    void staleBalanceFilterMatchesOnlyOldOrMissingBalanceAt() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-filter-stale");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-STALE", companyId);

        Material fresh = material("MAT-FRESH", "Fresh balance", companyId);
        stock(fresh, warehouse, new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, companyId);

        Material stale = material("MAT-STALE", "Stale balance", companyId);
        StockBalance staleBalance = base(new StockBalance(), "BAL-" + stale.getCode(), stale.getName(), companyId);
        staleBalance.setMaterialId(stale.getId());
        staleBalance.setWarehouseId(warehouse.getId());
        staleBalance.setQuantity(new BigDecimal("10"));
        staleBalance.setAvailableQuantity(new BigDecimal("10"));
        staleBalance.setReservedQuantity(BigDecimal.ZERO);
        staleBalance.setQuarantineQuantity(BigDecimal.ZERO);
        staleBalance.setTotalValue(BigDecimal.ZERO);
        staleBalance.setBalanceAt(LocalDateTime.now().minusHours(48)); // older than 24h threshold
        entityManager.persist(staleBalance);

        Material missingTimestamp = material("MAT-NO-TS", "Missing balanceAt", companyId);
        StockBalance noTsBalance = base(new StockBalance(), "BAL-" + missingTimestamp.getCode(),
                missingTimestamp.getName(), companyId);
        noTsBalance.setMaterialId(missingTimestamp.getId());
        noTsBalance.setWarehouseId(warehouse.getId());
        noTsBalance.setQuantity(new BigDecimal("10"));
        noTsBalance.setAvailableQuantity(new BigDecimal("10"));
        noTsBalance.setReservedQuantity(BigDecimal.ZERO);
        noTsBalance.setQuarantineQuantity(BigDecimal.ZERO);
        noTsBalance.setTotalValue(BigDecimal.ZERO);
        noTsBalance.setBalanceAt(null);
        entityManager.persist(noTsBalance);
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                null, null, null, null, null, Boolean.TRUE, null, null, null);
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(row -> row.materialCode())
                .contains("MAT-STALE", "MAT-NO-TS")
                .doesNotContain("MAT-FRESH");
    }

    @Test
    void nonMovingFilterIncludesOnlyMaterialsWithAnOpenNonMovingCase() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-filter-non-moving");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-NM", companyId);

        Material moving = material("MAT-MOVING", "Regular material", companyId);
        stock(moving, warehouse, new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, companyId);

        Material nonMoving = material("MAT-NONMOVING", "Non-moving material", companyId);
        stock(nonMoving, warehouse, new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, companyId);
        com.enterprise.mm.entity.NonMovingStock nonMovingCase = base(
                new com.enterprise.mm.entity.NonMovingStock(), "NM-" + nonMoving.getCode(), nonMoving.getName(), companyId);
        nonMovingCase.setMaterialId(nonMoving.getId());
        nonMovingCase.setWarehouseId(warehouse.getId());
        nonMovingCase.setQuantity(new BigDecimal("10"));
        nonMovingCase.setMonthsWithoutMovement(6);
        nonMovingCase.setDetectedAt(LocalDateTime.now().minusMonths(6));
        entityManager.persist(nonMovingCase);
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                null, null, null, null, null, null, Boolean.TRUE, null, null);
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(row -> row.materialCode())
                .containsExactly("MAT-NONMOVING");
        assertThat(page.getContent().get(0).signalReasons()).contains("NON_MOVING");
    }

    @Test
    void negativeProjectedFilterIncludesOnlyMaterialsWithNegativeProjectedAvailable() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-filter-negative-projected");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-NEG", companyId);

        Material healthy = material("MAT-POS", "Positive projection", companyId);
        stock(healthy, warehouse, new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, companyId);
        demand(healthy, new BigDecimal("2"), companyId);

        Material deficit = material("MAT-NEG", "Negative projection", companyId);
        stock(deficit, warehouse, new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO, companyId);
        demand(deficit, new BigDecimal("50"), companyId);
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                null, null, null, null, null, null, null, Boolean.TRUE, null);
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(row -> row.materialCode())
                .contains("MAT-NEG")
                .doesNotContain("MAT-POS");
    }

    @Test
    void urgencyFilterMatchesNearestDemandDateBucket() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-filter-urgency");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-URG", companyId);

        Material dueNow = material("MAT-DUE-NOW", "Due now", companyId);
        stock(dueNow, warehouse, new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO, companyId);
        demandDueOn(dueNow, new BigDecimal("5"), companyId, LocalDate.now());

        Material dueLater = material("MAT-DUE-LATER", "Due later", companyId);
        stock(dueLater, warehouse, new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO, companyId);
        demandDueOn(dueLater, new BigDecimal("5"), companyId, LocalDate.now().plusDays(60));
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                null, null, null, null, null, null, null, null,
                com.enterprise.mm.inventory.availability.enums.DemandUrgencyBucket.NOW);
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(row -> row.materialCode())
                .contains("MAT-DUE-NOW")
                .doesNotContain("MAT-DUE-LATER");
    }

    // ---- Item 2: overview.staleDataCount ----

    @Test
    void overviewStaleDataCountCountsMaterialsWithOldOrMissingBalanceAt() {
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-overview-stale-count");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = warehouse("WH-OV-STALE", companyId);

        Material fresh = material("MAT-OV-FRESH", "Fresh", companyId);
        stock(fresh, warehouse, new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, companyId);

        Material stale = material("MAT-OV-STALE", "Stale", companyId);
        StockBalance staleBalance = base(new StockBalance(), "BAL-" + stale.getCode(), stale.getName(), companyId);
        staleBalance.setMaterialId(stale.getId());
        staleBalance.setWarehouseId(warehouse.getId());
        staleBalance.setQuantity(new BigDecimal("10"));
        staleBalance.setAvailableQuantity(new BigDecimal("10"));
        staleBalance.setReservedQuantity(BigDecimal.ZERO);
        staleBalance.setQuarantineQuantity(BigDecimal.ZERO);
        staleBalance.setTotalValue(BigDecimal.ZERO);
        staleBalance.setBalanceAt(LocalDateTime.now().minusHours(30));
        entityManager.persist(staleBalance);
        entityManager.flush();

        InventoryAvailabilityFilter filter = new InventoryAvailabilityFilter(
                null, null, null, null, null, null, null, null, null);
        var overview = repository.overview(filter);

        assertThat(overview.staleDataCount()).isEqualTo(1);
        assertThat(overview.materialCount()).isEqualTo(2);
    }

    private void demandDueOn(Material material, BigDecimal quantity, UUID companyId, LocalDate requiredDate) {
        PurchaseRequest request = base(new PurchaseRequest(), "PR-" + material.getCode(), material.getName(), companyId);
        request.setStatus("APPROVED");
        request.setRequiredDate(requiredDate);
        entityManager.persist(request);
        PurchaseRequestItem line = base(new PurchaseRequestItem(), "PRI-" + material.getCode(), material.getName(), companyId);
        line.setPurchaseRequestId(request.getId());
        line.setLineType(ProcurementLineType.MATERIAL);
        line.setMaterialId(material.getId());
        line.setQuantity(quantity);
        line.setUnit("EA");
        entityManager.persist(line);
    }

    private Warehouse warehouse(String code, UUID companyId) {
        Warehouse entity = base(new Warehouse(), code, code, companyId);
        entityManager.persist(entity);
        return entity;
    }

    private Material material(String code, String name, UUID companyId) {
        Material entity = base(new Material(), code, name, companyId);
        entity.setSafetyStock(new BigDecimal("2"));
        entityManager.persist(entity);
        return entity;
    }

    private void stock(
        Material material,
        Warehouse warehouse,
        BigDecimal onHand,
        BigDecimal available,
        BigDecimal reserved,
        UUID companyId
    ) {
        StockBalance balance = base(new StockBalance(), "BAL-" + material.getCode(), material.getName(), companyId);
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(onHand);
        balance.setAvailableQuantity(available);
        balance.setReservedQuantity(reserved);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(onHand.multiply(BigDecimal.TEN));
        balance.setBalanceAt(LocalDateTime.now());
        entityManager.persist(balance);
    }

    private void reservation(Material material, Warehouse warehouse, BigDecimal quantity, UUID companyId) {
        MaterialReservation reservation = base(
            new MaterialReservation(),
            "RES-" + material.getCode(),
            material.getName(),
            companyId);
        reservation.setMaterialId(material.getId());
        reservation.setWarehouseId(warehouse.getId());
        reservation.setReservationType("MANUAL");
        reservation.setPriority("NORMAL");
        reservation.setQuantity(quantity);
        reservation.setReservedQuantity(quantity);
        reservation.setReleasedQuantity(BigDecimal.ZERO);
        entityManager.persist(reservation);
    }

    private void demand(Material material, BigDecimal quantity, UUID companyId) {
        PurchaseRequest request = base(new PurchaseRequest(), "PR-" + material.getCode(), material.getName(), companyId);
        request.setStatus("APPROVED");
        request.setRequiredDate(LocalDate.now().plusDays(5));
        entityManager.persist(request);
        PurchaseRequestItem line = base(new PurchaseRequestItem(), "PRI-" + material.getCode(), material.getName(), companyId);
        line.setPurchaseRequestId(request.getId());
        line.setLineType(ProcurementLineType.MATERIAL);
        line.setMaterialId(material.getId());
        line.setQuantity(quantity);
        line.setUnit("EA");
        entityManager.persist(line);
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T base(
        T entity, String code, String name, UUID companyId
    ) {
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
