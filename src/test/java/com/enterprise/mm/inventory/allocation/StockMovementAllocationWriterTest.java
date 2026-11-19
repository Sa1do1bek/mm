package com.enterprise.mm.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class StockMovementAllocationWriterTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MOVEMENT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID INVENTORY_ITEM = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final EntityManager entityManager = mock(EntityManager.class);
    private final StockMovementAllocationReferenceVerifier referenceVerifier =
        mock(StockMovementAllocationReferenceVerifier.class);
    private final StockMovementAllocationWriter writer =
        new StockMovementAllocationWriter(entityManager, referenceVerifier);

    @BeforeEach
    void simulateHibernateUuidGeneration() {
        org.mockito.Mockito.doAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            if (entity instanceof StockMovementAllocation allocation && allocation.getId() == null) {
                allocation.setId(UUID.randomUUID());
            } else if (entity instanceof StockMovementAllocationCostLayer lineage && lineage.getId() == null) {
                lineage.setId(UUID.randomUUID());
            }
            return null;
        }).when(entityManager).persist(org.mockito.ArgumentMatchers.any());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("warehouse-poster", null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsEmptyAndZeroOnlyEvidenceWithoutTouchingPersistence() {
        StockMovement movement = movement();

        assertThatThrownBy(() -> writer.write(movement, evidence(List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allocation");
        assertThatThrownBy(() -> writer.write(movement, evidence(List.of(allocation(
            "SOURCE", new BigDecimal("0.000000"), List.of())))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zero");

        verify(entityManager, never()).persist(org.mockito.ArgumentMatchers.any());
        verify(entityManager, never()).flush();
    }

    @Test
    void rejectsEvidenceWhoseMovementOwnedScopeDoesNotMatch() {
        StockMovement movement = movement();
        StockMovementAllocationDraft nonZero = allocation("SOURCE", new BigDecimal("2.0000"), List.of());

        assertScopeRejected(movement, new StockMovementAllocationEvidence(
            "other-tenant", COMPANY, MOVEMENT, MATERIAL, INVENTORY_ITEM, List.of(nonZero)));
        assertScopeRejected(movement, new StockMovementAllocationEvidence(
            TENANT, UUID.randomUUID(), MOVEMENT, MATERIAL, INVENTORY_ITEM, List.of(nonZero)));
        assertScopeRejected(movement, new StockMovementAllocationEvidence(
            TENANT, COMPANY, UUID.randomUUID(), MATERIAL, INVENTORY_ITEM, List.of(nonZero)));
        assertScopeRejected(movement, new StockMovementAllocationEvidence(
            TENANT, COMPANY, MOVEMENT, UUID.randomUUID(), INVENTORY_ITEM, List.of(nonZero)));
        assertScopeRejected(movement, new StockMovementAllocationEvidence(
            TENANT, COMPANY, MOVEMENT, MATERIAL, UUID.randomUUID(), List.of(nonZero)));

        verify(entityManager, never()).persist(org.mockito.ArgumentMatchers.any());
        verify(entityManager, never()).flush();
    }

    @Test
    void rejectsMissingMovementOwnershipBeforePersistence() {
        StockMovement missingId = movement();
        missingId.setId(null);
        StockMovement missingTenant = movement();
        missingTenant.setTenantId("  ");
        StockMovement missingCompany = movement();
        missingCompany.setCompanyId(null);
        StockMovement missingMaterial = movement();
        missingMaterial.setMaterialId(null);

        for (StockMovement invalid : List.of(missingId, missingTenant, missingCompany, missingMaterial)) {
            assertThatThrownBy(() -> writer.write(invalid, evidence(List.of(
                allocation("SOURCE", BigDecimal.ONE, List.of())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("movement ownership");
        }
        verify(entityManager, never()).persist(org.mockito.ArgumentMatchers.any());
        verify(entityManager, never()).flush();
    }

    @Test
    void rejectsUnknownRolesAndZeroOnlyCostLineage() {
        assertThatThrownBy(() -> writer.write(movement(), evidence(List.of(
            allocation("UNKNOWN", BigDecimal.ONE, List.of())))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("allocation role");

        assertThatThrownBy(() -> writer.write(movement(), evidence(List.of(
            allocation("SOURCE", BigDecimal.ONE, List.of(lineage("UNKNOWN", UUID.randomUUID(), BigDecimal.ONE)))))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lineage role");

        StockMovementCostLineageDraft zeroLineage = new StockMovementCostLineageDraft(
            "MANUAL", null, null, null, new BigDecimal("0.0000"), new BigDecimal("0.000000"),
            null, null, null);
        assertThatThrownBy(() -> writer.write(movement(), evidence(List.of(
            allocation("SOURCE", BigDecimal.ONE, List.of(zeroLineage))))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zero");

        verify(entityManager, never()).persist(org.mockito.ArgumentMatchers.any());
        verify(entityManager, never()).flush();
    }

    @Test
    void ownsScopeFromMovementAndAssignsDeterministicOneBasedSequences() {
        StockMovement movement = movement();
        UUID firstLayer = UUID.randomUUID();
        UUID secondLayer = UUID.randomUUID();
        UUID thirdLayer = UUID.randomUUID();
        StockMovementCostLineageDraft consumedOne = lineage("CONSUMED", firstLayer, new BigDecimal("-1.0000"));
        StockMovementCostLineageDraft consumedTwo = lineage("CONSUMED", secondLayer, new BigDecimal("-2.0000"));
        StockMovementCostLineageDraft created = lineage("CREATED", thirdLayer, new BigDecimal("3.0000"));

        writer.write(movement, evidence(List.of(
            allocation("SOURCE", new BigDecimal("-3.0000"), List.of(consumedOne, consumedTwo)),
            allocation("TARGET", new BigDecimal("3.0000"), List.of(created))
        )));

        ArgumentCaptor<StockMovementAllocation> allocationCaptor = ArgumentCaptor.forClass(StockMovementAllocation.class);
        verify(entityManager, org.mockito.Mockito.times(2)).persist(allocationCaptor.capture());
        List<StockMovementAllocation> allocations = allocationCaptor.getAllValues();
        assertThat(allocations).extracting(StockMovementAllocation::getAllocationSequence).containsExactly(1, 2);
        assertThat(allocations).allSatisfy(saved -> {
            assertThat(saved.getTenantId()).isEqualTo(movement.getTenantId());
            assertThat(saved.getCompanyId()).isEqualTo(movement.getCompanyId());
            assertThat(saved.getMovementId()).isEqualTo(movement.getId());
            assertThat(saved.getMaterialId()).isEqualTo(movement.getMaterialId());
            assertThat(saved.getSourceInventoryItemId()).isEqualTo(movement.getSourceInventoryItemId());
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getCreatedBy()).isEqualTo("warehouse-poster");
        });

        ArgumentCaptor<StockMovementAllocationCostLayer> lineageCaptor =
            ArgumentCaptor.forClass(StockMovementAllocationCostLayer.class);
        verify(entityManager, org.mockito.Mockito.times(3)).persist(lineageCaptor.capture());
        List<StockMovementAllocationCostLayer> lineages = lineageCaptor.getAllValues();
        assertThat(lineages).extracting(StockMovementAllocationCostLayer::getLineageSequence).containsExactly(1, 2, 1);
        assertThat(lineages).extracting(StockMovementAllocationCostLayer::getAllocationId).containsExactly(
            allocations.get(0).getId(), allocations.get(0).getId(), allocations.get(1).getId());
        assertThat(lineages).allSatisfy(saved -> {
            assertThat(saved.getTenantId()).isEqualTo(movement.getTenantId());
            assertThat(saved.getCompanyId()).isEqualTo(movement.getCompanyId());
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getCreatedBy()).isEqualTo("warehouse-poster");
        });
    }

    @Test
    void persistsAllRowsThenFlushesInsideAMandatoryTransaction() throws Exception {
        StockMovement movement = movement();
        StockMovementAllocationEvidence evidence = evidence(List.of(allocation(
            "SOURCE",
            BigDecimal.ONE.negate(),
            List.of(lineage("CONSUMED", UUID.randomUUID(), BigDecimal.ONE.negate()))
        )));

        writer.write(movement, evidence);

        InOrder order = inOrder(referenceVerifier, entityManager);
        order.verify(referenceVerifier).verify(movement, evidence);
        order.verify(entityManager).persist(org.mockito.ArgumentMatchers.isA(StockMovementAllocation.class));
        order.verify(entityManager).persist(org.mockito.ArgumentMatchers.isA(StockMovementAllocationCostLayer.class));
        order.verify(entityManager).flush();
        Transactional transactional = StockMovementAllocationWriter.class
            .getMethod("write", StockMovement.class, StockMovementAllocationEvidence.class)
            .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void evidenceAndNestedDraftListsAreDefensiveImmutableCopies() {
        List<StockMovementCostLineageDraft> mutableLineages = new ArrayList<>();
        StockMovementAllocationDraft allocation = allocation("SOURCE", BigDecimal.ONE, mutableLineages);
        mutableLineages.add(lineage("CREATED", UUID.randomUUID(), BigDecimal.ONE));
        assertThat(allocation.costLineages()).isEmpty();
        assertThatThrownBy(() -> allocation.costLineages().add(lineage(
            "CREATED", UUID.randomUUID(), BigDecimal.ONE)))
            .isInstanceOf(UnsupportedOperationException.class);

        List<StockMovementAllocationDraft> mutableAllocations = new ArrayList<>();
        mutableAllocations.add(allocation);
        StockMovementAllocationEvidence evidence = evidence(mutableAllocations);
        mutableAllocations.clear();
        assertThat(evidence.allocations()).containsExactly(allocation);
        assertThatThrownBy(() -> evidence.allocations().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullableDraftDeltasNormalizeToScaleInsensitiveZero() {
        StockMovementCostLineageDraft lineage = new StockMovementCostLineageDraft(
            "MANUAL", null, null, null, null, null, null, null, null);
        assertThat(lineage.quantityDelta()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lineage.valueDelta()).isEqualByComparingTo(BigDecimal.ZERO);

        StockMovementAllocationDraft allocation = new StockMovementAllocationDraft(
            "SOURCE", UUID.randomUUID(), StockMovementStockKeySnapshot.empty(),
            StockMovementStockKeySnapshot.empty(), null, null, null, null, null, null, null, null,
            null, null, null, List.of());
        assertThat(List.of(
            allocation.onHandDelta(), allocation.availableDelta(), allocation.reservedDelta(),
            allocation.quarantineDelta(), allocation.totalValueDelta(), allocation.batchQuantityDelta()))
            .allSatisfy(value -> assertThat(value).isEqualByComparingTo(BigDecimal.ZERO));
        assertThatThrownBy(() -> writer.write(movement(), evidence(List.of(allocation))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zero");
    }

    private void assertScopeRejected(StockMovement movement, StockMovementAllocationEvidence evidence) {
        assertThatThrownBy(() -> writer.write(movement, evidence))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope");
    }

    private StockMovement movement() {
        StockMovement movement = new StockMovement();
        movement.setId(MOVEMENT);
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setMaterialId(MATERIAL);
        movement.setSourceInventoryItemId(INVENTORY_ITEM);
        return movement;
    }

    private StockMovementAllocationEvidence evidence(List<StockMovementAllocationDraft> allocations) {
        return new StockMovementAllocationEvidence(
            TENANT, COMPANY, MOVEMENT, MATERIAL, INVENTORY_ITEM, allocations);
    }

    private StockMovementAllocationDraft allocation(
        String role,
        BigDecimal onHandDelta,
        List<StockMovementCostLineageDraft> costLineages
    ) {
        return new StockMovementAllocationDraft(
            role,
            UUID.randomUUID(),
            new StockMovementStockKeySnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "BATCH-S", "AVAILABLE"),
            StockMovementStockKeySnapshot.empty(),
            UUID.randomUUID(),
            "PCS",
            onHandDelta,
            onHandDelta,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            onHandDelta.multiply(new BigDecimal("10.0000")),
            onHandDelta,
            new BigDecimal("10.000000"),
            "UZS",
            BigDecimal.ONE,
            costLineages
        );
    }

    private StockMovementCostLineageDraft lineage(String role, UUID layerId, BigDecimal quantityDelta) {
        return new StockMovementCostLineageDraft(
            role,
            layerId,
            null,
            UUID.randomUUID(),
            quantityDelta,
            quantityDelta.multiply(new BigDecimal("10.0000")),
            new BigDecimal("10.000000"),
            "UZS",
            BigDecimal.ONE
        );
    }
}
