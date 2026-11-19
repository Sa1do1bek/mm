package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqRequest;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.service.RfqAggregationUnitResolver;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RfqAggregationContractTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final RfqAggregationUnitResolver resolver = new RfqAggregationUnitResolver();

    @Test
    void rfqResponseExposesAggregationMetadata() {
        UUID materialGroupId = UUID.randomUUID();
        RFQ rfq = new RFQ();
        rfq.setAggregatedTender(true);
        rfq.setAggregationMode(RfqAggregationMode.MATERIAL_GROUP);
        rfq.setMaterialGroupId(materialGroupId);
        rfq.setSourcePurchaseRequestCount(2);
        rfq.setSourceItemCount(3);
        rfq.setCurrencyCode("UZS");

        RFQResponse response = new RFQMapper().toResponse(rfq);

        assertThat(response.isAggregatedTender()).isTrue();
        assertThat(response.getAggregationMode()).isEqualTo(RfqAggregationMode.MATERIAL_GROUP);
        assertThat(response.getMaterialGroupId()).isEqualTo(materialGroupId);
        assertThat(response.getSourcePurchaseRequestCount()).isEqualTo(2);
        assertThat(response.getSourceItemCount()).isEqualTo(3);
        assertThat(response.getCurrencyCode()).isEqualTo("UZS");
    }

    @Test
    void createCommandRejectsEmptySelectionAndMissingSetup() {
        Set<jakarta.validation.ConstraintViolation<CreateAggregatedRfqRequest>> violations = validator.validate(
            new CreateAggregatedRfqRequest(null, "", List.of(), null, "", "", 0));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
            .contains(
                "groupBy",
                "groupKey",
                "purchaseRequestItemIds",
                "dueDate",
                "tenderProcedureType",
                "selectionRule",
                "minQuotationsRequired");
    }

    @Test
    void unitResolutionUsesLineThenMaterialUomAndRejectsMissingUnit() {
        assertThat(resolver.resolve(itemWithLineUnit(" kg "))).isEqualTo("kg");
        assertThat(resolver.resolve(itemWithUomCode("KG"))).isEqualTo("KG");
        assertThat(resolver.resolve(itemWithUomShortName(" pcs "))).isEqualTo("pcs");
        assertThat(resolver.normalizeOptional(" kg ")).contains("kg");
        assertThat(resolver.requiredKey(" kg ", UUID.randomUUID())).isEqualTo("KG");

        PurchaseRequestItem item = itemWithoutAnyUnit();
        assertThatThrownBy(() -> resolver.resolve(item))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getMessageKey())
            .isEqualTo("rfq.aggregation.unit_required");
    }

    @Test
    void createCommandCarriesRfqSetupAndFullLineSelection() {
        UUID itemId = UUID.randomUUID();
        CreateAggregatedRfqRequest request = new CreateAggregatedRfqRequest(
            RfqAggregationMode.MATERIAL,
            "material:" + itemId + ":KG",
            List.of(itemId),
            LocalDate.of(2026, 8, 1),
            "REQUEST_FOR_QUOTATION",
            "LOWEST_PRICE",
            2);

        assertThat(request.groupBy()).isEqualTo(RfqAggregationMode.MATERIAL);
        assertThat(request.purchaseRequestItemIds()).containsExactly(itemId);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rfqControllerExposesAggregationPreviewAndSourcesUnderSingularBasePath() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/RFQController.java"));

        assertThat(controller)
            .contains("@RequestMapping(\"/api/rfq\")")
            .contains("@GetMapping(\"/aggregation-preview\")")
            .contains("@GetMapping(\"/{id}/sources\")")
            .contains("@PostMapping(\"/from-pr-items\")")
            .contains("@PostMapping(\"/{id}/cancel\")")
            .contains("@Valid @RequestBody CreateAggregatedRfqRequest request")
            .contains("PaginationUtils.pageRequest(page, size, Sort.unsorted())")
            .doesNotContain("/api/rfqs");
    }

    @Test
    void ordinaryPurchaseRequestUpdateMapperDoesNotAssignWorkflowStatus() throws Exception {
        String mapper = Files.readString(Path.of("src/main/java/com/enterprise/mm/mapper/PurchaseRequestMapper.java"));
        int updateStart = mapper.indexOf("public void updateEntity");
        String updateMethod = mapper.substring(updateStart, mapper.indexOf("private RequestType", updateStart));

        assertThat(updateMethod).doesNotContain("setStatus");
    }

    @Test
    void supplierQuotationControllerExposesAggregatedContractAwardRoute() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/SupplierQuotationController.java"));

        assertThat(controller)
            .contains("@PostMapping(\"/{id}/award-contract\")")
            .contains("rfqAwardService.award");
    }

    private PurchaseRequestItem itemWithLineUnit(String unit) {
        PurchaseRequestItem item = itemWithoutAnyUnit();
        item.setUnit(unit);
        return item;
    }

    private PurchaseRequestItem itemWithUomCode(String code) {
        PurchaseRequestItem item = itemWithoutAnyUnit();
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setCode(code);
        Material material = new Material();
        material.setUnitOfMeasure(uom);
        item.setMaterial(material);
        return item;
    }

    private PurchaseRequestItem itemWithUomShortName(String shortName) {
        PurchaseRequestItem item = itemWithoutAnyUnit();
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setMeasurementNameShort(shortName);
        Material material = new Material();
        material.setUnitOfMeasure(uom);
        item.setMaterial(material);
        return item;
    }

    private PurchaseRequestItem itemWithoutAnyUnit() {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setId(UUID.randomUUID());
        return item;
    }
}
