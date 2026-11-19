package com.enterprise.mm.warehouse.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizeEquipmentCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizationResult;
import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationPositionStatus;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InstallationCapitalizationTest {

    private WarehouseInstallationPositionRepository positionRepository;
    private AccountAwareStockPostingService postingService;
    private WarehouseInstallationCapitalizationRepository capitalizationRepository;
    private FixedAssetRepository fixedAssetRepository;
    private InstallationEquipmentService service;

    private final String tenantId = "test-tenant";
    private final UUID companyId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        positionRepository = mock(WarehouseInstallationPositionRepository.class);
        postingService = mock(AccountAwareStockPostingService.class);
        capitalizationRepository = mock(WarehouseInstallationCapitalizationRepository.class);
        fixedAssetRepository = mock(FixedAssetRepository.class);

        when(positionRepository.save(any(WarehouseInstallationPosition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(capitalizationRepository.save(any(WarehouseInstallationCapitalization.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service = new InstallationEquipmentService(
            positionRepository,
            postingService,
            capitalizationRepository,
            fixedAssetRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P5 AT03: Exact equipment allocation and capitalization into native FixedAsset")
    void p5_at03_fullCapitalizationIntoFixedAsset() {
        UUID positionId = UUID.randomUUID();
        WarehouseInstallationPosition pos = new WarehouseInstallationPosition();
        pos.setId(positionId);
        pos.setTenantId(tenantId);
        pos.setCompanyId(companyId.toString());
        pos.setProjectId(projectId);
        pos.setMaterialId(materialId);
        pos.setWarehouseId(warehouseId);
        pos.setAccountCode("07");
        pos.setStatus(InstallationPositionStatus.ACCEPTED);
        pos.setDispatchedQuantity(new BigDecimal("5.0000000"));
        pos.setAcceptedQuantity(new BigDecimal("5.0000000"));
        pos.setCapitalizedQuantity(BigDecimal.ZERO);
        pos.setReturnedQuantity(BigDecimal.ZERO);

        when(positionRepository.findById(positionId)).thenReturn(Optional.of(pos));

        UUID assetId = UUID.randomUUID();
        FixedAsset asset = new FixedAsset();
        asset.setId(assetId);
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);

        when(fixedAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate capDate = LocalDate.of(2026, 10, 20);
        var capCmd = new CapitalizeEquipmentCommand(
            "CMD-CAP-01",
            positionId,
            new BigDecimal("5.0000000"),
            new BigDecimal("50000000.0000"),
            List.of(assetId),
            capDate,
            "ACT-COMMISSIONING-55"
        );

        CapitalizationResult result = service.capitalizeInstallationEquipment(capCmd);

        // 1. Verify result & position status
        assertThat(result.capitalizedQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(result.remainingQuantity()).isEqualByComparingTo("0");
        assertThat(pos.getStatus()).isEqualTo(InstallationPositionStatus.FULLY_CAPITALIZED);

        // 2. Verify FixedAsset state updated
        var assetCaptor = ArgumentCaptor.forClass(FixedAsset.class);
        verify(fixedAssetRepository).save(assetCaptor.capture());
        FixedAsset savedAsset = assetCaptor.getValue();
        assertThat(savedAsset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        assertThat(savedAsset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.DEPARTMENT);
        assertThat(savedAsset.getAcquisitionValue()).isEqualByComparingTo("10000000.0000");
        assertThat(savedAsset.getCurrentBookValue()).isEqualByComparingTo("10000000.0000");
        assertThat(savedAsset.getFirstCommissionedOn()).isEqualTo(capDate);

        // 3. Verify capitalization repository entry
        verify(capitalizationRepository).save(any(WarehouseInstallationCapitalization.class));
    }

    @Test
    @DisplayName("P5 AT06: Partial capitalization preserves remaining balance and prevents double counting")
    void p5_at06_partialCapitalizationConservation() {
        UUID positionId = UUID.randomUUID();
        WarehouseInstallationPosition pos = new WarehouseInstallationPosition();
        pos.setId(positionId);
        pos.setTenantId(tenantId);
        pos.setCompanyId(companyId.toString());
        pos.setProjectId(projectId);
        pos.setMaterialId(materialId);
        pos.setWarehouseId(warehouseId);
        pos.setAccountCode("07");
        pos.setStatus(InstallationPositionStatus.ACCEPTED);
        pos.setDispatchedQuantity(new BigDecimal("10.0000000"));
        pos.setAcceptedQuantity(new BigDecimal("10.0000000"));
        pos.setCapitalizedQuantity(BigDecimal.ZERO);
        pos.setReturnedQuantity(BigDecimal.ZERO);

        when(positionRepository.findById(positionId)).thenReturn(Optional.of(pos));

        // 1. Partial capitalize 4 out of 10
        var step1Cmd = new CapitalizeEquipmentCommand(
            "CMD-CAP-PART-1",
            positionId,
            new BigDecimal("4.0000000"),
            new BigDecimal("40000000.0000"),
            List.of(),
            LocalDate.of(2026, 10, 21),
            "PARTIAL-CAP-1"
        );

        CapitalizationResult step1 = service.capitalizeInstallationEquipment(step1Cmd);
        assertThat(step1.capitalizedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(step1.remainingQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(pos.getStatus()).isEqualTo(InstallationPositionStatus.PARTIALLY_CAPITALIZED);

        // Verify balance query
        when(positionRepository.findByTenantIdAndCompanyIdAndProjectIdAndMaterialId(
            tenantId, companyId.toString(), projectId, materialId
        )).thenReturn(List.of(pos));

        var bal1 = service.getProjectInstallationBalance(projectId, materialId);
        assertThat(bal1.dispatchedQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(bal1.capitalizedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(bal1.remainingAtProjectQuantity()).isEqualByComparingTo("6.0000000");

        // 2. Reject over-capitalization (trying to capitalize 7 units when only 6 remain)
        var overCapCmd = new CapitalizeEquipmentCommand(
            "CMD-CAP-OVER",
            positionId,
            new BigDecimal("7.0000000"),
            new BigDecimal("70000000.0000"),
            List.of(),
            LocalDate.of(2026, 10, 22),
            "OVER-CAP"
        );

        assertThatThrownBy(() -> service.capitalizeInstallationEquipment(overCapCmd))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot capitalize more than available remaining quantity");

        // 3. Capitalize remaining 6 units
        var step2Cmd = new CapitalizeEquipmentCommand(
            "CMD-CAP-PART-2",
            positionId,
            new BigDecimal("6.0000000"),
            new BigDecimal("60000000.0000"),
            List.of(),
            LocalDate.of(2026, 10, 23),
            "PARTIAL-CAP-2"
        );

        CapitalizationResult step2 = service.capitalizeInstallationEquipment(step2Cmd);
        assertThat(step2.capitalizedQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(step2.remainingQuantity()).isEqualByComparingTo("0");
        assertThat(pos.getStatus()).isEqualTo(InstallationPositionStatus.FULLY_CAPITALIZED);

        var bal2 = service.getProjectInstallationBalance(projectId, materialId);
        assertThat(bal2.capitalizedQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(bal2.remainingAtProjectQuantity()).isEqualByComparingTo("0");
    }
}
