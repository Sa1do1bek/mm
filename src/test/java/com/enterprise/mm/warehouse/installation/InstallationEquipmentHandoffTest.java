package com.enterprise.mm.warehouse.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.installation.InstallationCommands.AcceptInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.DispatchInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationPositionStatus;
import com.enterprise.mm.warehouse.installation.InstallationCommands.ReturnInstallationCommand;
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

class InstallationEquipmentHandoffTest {

    private WarehouseInstallationPositionRepository positionRepository;
    private AccountAwareStockPostingService postingService;
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

        when(positionRepository.save(any(WarehouseInstallationPosition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service = new InstallationEquipmentService(positionRepository, postingService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P5 AT01: Dispatch of installation equipment from account 07 creates project installation position")
    void p5_at01_dispatchAndAcceptEquipmentForProject() {
        when(positionRepository.findByTenantIdAndCompanyIdAndProjectIdAndMaterialIdAndWarehouseId(
            tenantId, companyId.toString(), projectId, materialId, warehouseId
        )).thenReturn(Optional.empty());

        var dispatchCmd = new DispatchInstallationCommand(
            "CMD-DISP-01",
            projectId,
            materialId,
            warehouseId,
            new BigDecimal("10.0000000"),
            "PCS",
            LocalDate.of(2026, 10, 15),
            "PROJECT-DISPATCH-REQ-101"
        );

        WarehouseInstallationPosition pos = service.dispatchEquipmentToProject(dispatchCmd);

        // 1. Verify stock issue from account 07 was called
        var issueCaptor = ArgumentCaptor.forClass(AccountAwareStockPostingService.IssueRequest.class);
        verify(postingService).postIssue(issueCaptor.capture());
        AccountAwareStockPostingService.IssueRequest issue = issueCaptor.getValue();
        assertThat(issue.accountCode()).isEqualTo("07");
        assertThat(issue.materialId()).isEqualTo(materialId);
        assertThat(issue.warehouseId()).isEqualTo(warehouseId);
        assertThat(issue.quantity()).isEqualByComparingTo("10.0000000");

        // 2. Verify position details
        assertThat(pos).isNotNull();
        assertThat(pos.getStatus()).isEqualTo(InstallationPositionStatus.DISPATCHED);
        assertThat(pos.getDispatchedQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(pos.getAccountCode()).isEqualTo("07");
        assertThat(pos.getRemainingQuantity()).isEqualByComparingTo("10.0000000");

        // 3. Accept equipment at project site
        when(positionRepository.findById(pos.getId())).thenReturn(Optional.of(pos));
        var acceptCmd = new AcceptInstallationCommand(
            "CMD-ACC-01",
            pos.getId(),
            new BigDecimal("10.0000000"),
            LocalDate.of(2026, 10, 16)
        );

        WarehouseInstallationPosition acceptedPos = service.acceptEquipmentAtProject(acceptCmd);
        assertThat(acceptedPos.getStatus()).isEqualTo(InstallationPositionStatus.ACCEPTED);
        assertThat(acceptedPos.getAcceptedQuantity()).isEqualByComparingTo("10.0000000");

        // 4. Verify project balance calculation
        when(positionRepository.findByTenantIdAndCompanyIdAndProjectIdAndMaterialId(
            tenantId, companyId.toString(), projectId, materialId
        )).thenReturn(List.of(acceptedPos));

        var balance = service.getProjectInstallationBalance(projectId, materialId);
        assertThat(balance.dispatchedQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(balance.acceptedQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(balance.returnedQuantity()).isEqualByComparingTo("0");
        assertThat(balance.remainingAtProjectQuantity()).isEqualByComparingTo("10.0000000");
    }

    @Test
    @DisplayName("P5 AT02: Return of unused installation equipment restores account 07 stock and rejects over-return")
    void p5_at02_returnUnusedEquipmentToWarehouse() {
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

        // 1. Return 3 unused units back to warehouse
        var returnCmd = new ReturnInstallationCommand(
            "CMD-RET-01",
            positionId,
            new BigDecimal("3.0000000"),
            warehouseId,
            LocalDate.of(2026, 10, 17),
            "EXCESS_EQUIPMENT"
        );

        WarehouseInstallationPosition returnedPos = service.returnUnusedEquipmentToWarehouse(returnCmd);

        // Verify stock receipt back into account 07 was called
        var receiptCaptor = ArgumentCaptor.forClass(AccountAwareStockPostingService.ReceiptRequest.class);
        verify(postingService).postReceipt(receiptCaptor.capture());
        AccountAwareStockPostingService.ReceiptRequest receipt = receiptCaptor.getValue();
        assertThat(receipt.accountCode()).isEqualTo("07");
        assertThat(receipt.materialId()).isEqualTo(materialId);
        assertThat(receipt.warehouseId()).isEqualTo(warehouseId);
        assertThat(receipt.quantity()).isEqualByComparingTo("3.0000000");

        // Verify updated position
        assertThat(returnedPos.getReturnedQuantity()).isEqualByComparingTo("3.0000000");
        assertThat(returnedPos.getRemainingQuantity()).isEqualByComparingTo("7.0000000");

        // 2. Reject over-return: trying to return 8 units when only 7 remain
        var overReturnCmd = new ReturnInstallationCommand(
            "CMD-RET-OVER",
            positionId,
            new BigDecimal("8.0000000"),
            warehouseId,
            LocalDate.of(2026, 10, 18),
            "MORE_THAN_REMAINING"
        );

        assertThatThrownBy(() -> service.returnUnusedEquipmentToWarehouse(overReturnCmd))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot return more than available at project");
    }
}
