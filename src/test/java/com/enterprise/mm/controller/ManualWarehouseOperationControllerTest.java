package com.enterprise.mm.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.ManualAssetReceiptService;
import com.enterprise.mm.service.ManualWarehouseOperationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ManualWarehouseOperationControllerTest {

    @Mock
    private ManualWarehouseOperationService service;

    @Mock
    private ManualAssetReceiptService assetReceipts;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ManualWarehouseOperationController controller = new ManualWarehouseOperationController(service, assetReceipts);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void contractsReturnsPagedContractOptions() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        var option = new ManualIssueContractOptionResponse(
            contractId,
            "CTR-101",
            "SH-101/2026",
            "Metal supply contract",
            supplierId,
            "SUP-99",
            "UzMetallurgy",
            "UZS",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            "ACTIVE",
            3
        );

        when(service.searchContractsForIssue(eq(warehouseId), eq("metal"), eq("CTR-101"), eq("SH-101"), eq(supplierId), any()))
            .thenReturn(PageResponse.from(new PageImpl<>(List.of(option), PageRequest.of(0, 20), 1)));

        mockMvc.perform(get("/api/workspaces/warehouse-execution/manual/contracts")
                .param("warehouseId", warehouseId.toString())
                .param("search", "metal")
                .param("code", "CTR-101")
                .param("contractNumber", "SH-101")
                .param("supplierId", supplierId.toString())
                .param("page", "0")
                .param("size", "20")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].id").value(contractId.toString()))
            .andExpect(jsonPath("$.data.content[0].code").value("CTR-101"))
            .andExpect(jsonPath("$.data.content[0].contractNumber").value("SH-101/2026"))
            .andExpect(jsonPath("$.data.content[0].supplierName").value("UzMetallurgy"))
            .andExpect(jsonPath("$.data.content[0].materialCount").value(3));

        verify(service).searchContractsForIssue(eq(warehouseId), eq("metal"), eq("CTR-101"), eq("SH-101"), eq(supplierId), any());
    }

    @Test
    void contractIssueContextReturnsCompleteStockAndSpecificationDetails() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();

        var stockSummary = new ContractManualIssueContextResponse.WarehouseStockBalanceSummary(
            new BigDecimal("50.000"),
            new BigDecimal("55.000"),
            new BigDecimal("5.000"),
            true,
            positionId
        );

        var position = new ContractManualIssueContextResponse.AvailableStockPositionDto(
            positionId,
            UUID.randomUUID(),
            "BATCH-001",
            UUID.randomUUID(),
            "Main Zone",
            UUID.randomUUID(),
            "Bin-A",
            new BigDecimal("50.000"),
            LocalDate.of(2027, 6, 30)
        );

        var line = new ContractManualIssueContextResponse.ContractManualIssueLineResponse(
            specId,
            materialId,
            "MAT-STEEL",
            "Reinforced steel rod",
            "TN",
            new BigDecimal("100.000"),
            new BigDecimal("4500000.00"),
            new BigDecimal("450000000.00"),
            "MATERIAL",
            "NONE",
            stockSummary,
            List.of(position)
        );

        var contextResponse = new ContractManualIssueContextResponse(
            contractId,
            "CTR-101",
            "SH-101/2026",
            "Metal supply contract",
            UUID.randomUUID(),
            "UzMetallurgy",
            "UZS",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            warehouseId,
            "Central Materials Warehouse",
            List.of(line)
        );

        when(service.contractManualIssueContext(warehouseId, contractId)).thenReturn(contextResponse);

        mockMvc.perform(get("/api/workspaces/warehouse-execution/manual/contracts/{contractId}/issue-context", contractId)
                .param("warehouseId", warehouseId.toString())
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contractId").value(contractId.toString()))
            .andExpect(jsonPath("$.data.contractNumber").value("SH-101/2026"))
            .andExpect(jsonPath("$.data.warehouseId").value(warehouseId.toString()))
            .andExpect(jsonPath("$.data.lines[0].specificationId").value(specId.toString()))
            .andExpect(jsonPath("$.data.lines[0].materialCode").value("MAT-STEEL"))
            .andExpect(jsonPath("$.data.lines[0].serialTrackingPolicy").value("NONE"))
            .andExpect(jsonPath("$.data.lines[0].availableStock.availableQuantity").value(50.0))
            .andExpect(jsonPath("$.data.lines[0].availablePositions[0].batchNumber").value("BATCH-001"));

        verify(service).contractManualIssueContext(warehouseId, contractId);
    }

    @Test
    void groupReceiptContextAndMemberEndpointExposeGroupIdentityAndRemainingQuantity() throws Exception {
        UUID warehouseId = UUID.randomUUID(), contractId = UUID.randomUUID(), specId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), materialId = UUID.randomUUID(), unitId = UUID.randomUUID();
        var line = new ContractManualIssueContextResponse.ContractManualIssueLineResponse(specId, materialId,
            "MAT-1", "Cable", "m", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, "TMB", "NONE", null, List.of(),
            groupId, "GRP-1", "Cable group", unitId, new BigDecimal("7"));
        var context = new ContractManualIssueContextResponse(contractId, "CTR-1", "C-1", "Supply contract", null, null,
            "UZS", null, null, warehouseId, "Warehouse", List.of(line));
        when(service.contractManualReceiptContext(warehouseId, contractId)).thenReturn(context);
        when(service.contractSpecificationMaterials(eq(warehouseId), eq(contractId), eq(specId), eq("Cable"), any()))
            .thenReturn(PageResponse.from(List.of(line)));
        mockMvc.perform(get("/api/workspaces/warehouse-execution/manual/contracts/{contractId}/receipt-context", contractId)
                .param("warehouseId", warehouseId.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].materialGroupId").value(groupId.toString()));
        mockMvc.perform(get("/api/workspaces/warehouse-execution/manual/contracts/{contractId}/specifications/{specificationId}/materials", contractId, specId)
                .param("warehouseId", warehouseId.toString()).param("search", "Cable").param("page", "0").param("size", "20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].materialId").value(materialId.toString()))
            .andExpect(jsonPath("$.data.content[0].unitOfMeasureId").value(unitId.toString()))
            .andExpect(jsonPath("$.data.content[0].remainingReceiptQuantity").value(7));
    }

    @Test
    void legacyContractIssueRequiresApprovedRequest() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();

        ManualWarehouseOperationRequest request = new ManualWarehouseOperationRequest(
            warehouseId,
            materialId,
            new BigDecimal("10.000"),
            BigDecimal.ZERO,
            positionId,
            LocalDate.of(2026, 10, 20),
            "Direct workshop issue",
            "Req-77",
            "UZS",
            null,
            null,
            contractId,
            specId
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(post("/api/workspaces/warehouse-execution/manual/issue")
                .header("Idempotency-Key", "idemp-key-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
            .hasRootCauseInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasStackTraceContaining("approved material issue request");
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void receiptBatchCallsServiceAndReturnsDocument() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();

        var line = new ManualWarehouseDocumentLineRequest(
            specId,
            new BigDecimal("5.000"),
            new BigDecimal("100.00"),
            null,
            null,
            null,
            null
        );
        var request = new ManualWarehouseDocumentRequest(
            warehouseId,
            contractId,
            LocalDate.of(2026, 10, 20),
            "Supplier source",
            "M-1",
            List.of(line)
        );

        UUID docId = UUID.randomUUID();
        UUID movId = UUID.randomUUID();
        var response = new ManualWarehouseDocumentResponse(
            docId,
            "SMD-001",
            MovementType.RECEIPT,
            "DRAFT",
            List.of(movId),
            1
        );

        when(service.receiptBatch(any(ManualWarehouseDocumentRequest.class), eq("batch-key-1")))
            .thenReturn(response);

        mockMvc.perform(post("/api/workspaces/warehouse-execution/manual/receipt-batch")
                .header("Idempotency-Key", "batch-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentId").value(docId.toString()))
            .andExpect(jsonPath("$.data.movementType").value("RECEIPT"))
            .andExpect(jsonPath("$.data.lineCount").value(1));

        verify(service).receiptBatch(any(ManualWarehouseDocumentRequest.class), eq("batch-key-1"));
    }

    @Test
    void legacyContractIssueBatchRequiresApprovedRequest() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        UUID posId = UUID.randomUUID();

        var line = new ManualWarehouseDocumentLineRequest(
            specId,
            new BigDecimal("2.000"),
            null,
            posId,
            null,
            null,
            null
        );
        var request = new ManualWarehouseDocumentRequest(
            warehouseId,
            contractId,
            LocalDate.of(2026, 10, 20),
            null,
            null,
            List.of(line)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(post("/api/workspaces/warehouse-execution/manual/issue-batch")
                .header("Idempotency-Key", "batch-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))))
            .hasRootCauseInstanceOf(com.enterprise.mm.error.BusinessException.class)
            .hasStackTraceContaining("approved material issue request");
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
