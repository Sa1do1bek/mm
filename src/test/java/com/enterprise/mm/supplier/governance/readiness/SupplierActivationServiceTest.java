package com.enterprise.mm.supplier.governance.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierActivationRecord;
import com.enterprise.mm.payload.SupplierActivationCommand;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessAction;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierActivationServiceTest {
    private static final UUID SUPPLIER_ID = UUID.randomUUID();

    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierActivationRecordRepository activationRepository;
    @Mock private SupplierReadinessService readinessService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    private SupplierActivationService service;

    @BeforeEach
    void setUp() {
        service = new SupplierActivationService(
            supplierRepository, activationRepository, readinessService, idempotencyService,
            integrationEventService, auditService, new ObjectMapper().findAndRegisterModules(),
            org.mockito.Mockito.mock(com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy.class));
        when(idempotencyService.execute(
            eq("SUPPLIER_ACTIVATE"), eq(SUPPLIER_ID), eq("activate-key"),
            any(), eq(SupplierReadinessSummary.class), any()))
            .thenAnswer(invocation ->
                ((java.util.function.Supplier<SupplierReadinessSummary>) invocation.getArgument(5)).get());
    }

    @Test
    void recordsSnapshotAndActivatesOnlyWhenEligible() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setCode("SUP-ACTIVATE");
        supplier.setName("Supplier Activate");
        supplier.setVersion(7L);
        supplier.setStatus("NEW");
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID))
            .thenReturn(Optional.of(supplier));
        when(readinessService.get(SUPPLIER_ID)).thenReturn(summary(true, List.of()));
        when(activationRepository.save(any(SupplierActivationRecord.class))).thenAnswer(invocation -> {
            SupplierActivationRecord record = invocation.getArgument(0);
            record.setId(UUID.randomUUID());
            return record;
        });

        SupplierReadinessSummary result = service.activate(
            SUPPLIER_ID, new SupplierActivationCommand(7L, "Final review complete"), "activate-key");

        assertThat(supplier.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.overallStatus()).isEqualTo(OverallStatus.ACTIVE);
        assertThat(result.readinessPercent()).isEqualTo(100);
        assertThat(result.stages()).filteredOn(stage -> stage.key() == StageKey.ACTIVATION)
            .singleElement().satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.COMPLETED);
                assertThat(stage.completedItems()).isEqualTo(stage.totalItems());
            });
        ArgumentCaptor<SupplierActivationRecord> record = ArgumentCaptor.forClass(SupplierActivationRecord.class);
        verify(activationRepository).save(record.capture());
        assertThat(record.getValue().getStatus()).isEqualTo("RECORDED");
        assertThat(record.getValue().getReadinessSnapshotJson()).contains("READY_TO_ACTIVATE");
    }

    @Test
    void rejectsWhenReadinessChangesBeforeCommit() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setVersion(7L);
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID))
            .thenReturn(Optional.of(supplier));
        ReadinessBlocker blocker = new ReadinessBlocker(
            "COMPLIANCE_BLOCKED", StageKey.COMPLIANCE_KYC, null, null,
            "Compliance blocked", "Resolve compliance", "COMPLIANCE_MANAGER", null);
        when(readinessService.get(SUPPLIER_ID)).thenReturn(summary(false, List.of(blocker)));

        assertThatThrownBy(() -> service.activate(
            SUPPLIER_ID, new SupplierActivationCommand(7L, "Final review complete"), "activate-key"))
            .isInstanceOf(SupplierReadinessBlockedException.class);
    }

    @Test
    void directActivationRequestCannotBypassIncompleteOnboarding() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setVersion(7L);
        supplier.setStatus("NEW");
        when(supplierRepository.findByIdAndIsDeletedFalse(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        var readiness = new SupplierReadinessPolicy().evaluate(new SupplierReadinessFacts(
            true, false, false, false, false, false, false, false, false, List.of()), java.time.LocalDate.now());
        when(readinessService.get(SUPPLIER_ID)).thenReturn(readiness);
        assertThatThrownBy(() -> service.activate(SUPPLIER_ID,
            new SupplierActivationCommand(7L, "Try to skip onboarding"), "activate-key"))
            .isInstanceOf(SupplierReadinessBlockedException.class);
        assertThat(supplier.getStatus()).isEqualTo("NEW");
        verifyNoInteractions(activationRepository, integrationEventService, auditService);
    }

    private SupplierReadinessSummary summary(boolean eligible, List<ReadinessBlocker> blockers) {
        return new SupplierReadinessSummary(
            eligible ? OverallStatus.READY_TO_ACTIVATE : OverallStatus.BLOCKED,
            eligible ? 95 : 40,
            eligible,
            List.of(new SupplierReadinessDtos.ReadinessStage(StageKey.MASTER_PROFILE,
                    SupplierReadinessEnums.StageState.COMPLETED, 1, 1, List.of()),
                new SupplierReadinessDtos.ReadinessStage(StageKey.ACTIVATION,
                    SupplierReadinessEnums.StageState.IN_PROGRESS, 0, 1, List.of())),
            List.of(),
            blockers,
            new ReadinessAction("ACTIVATE_SUPPLIER", true, null, "SUPPLIER_MANAGER", null, 7L),
            LocalDateTime.now(),
            SupplierReadinessPolicy.POLICY_VERSION
        );
    }
}
