package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.payload.EquipmentResponse;
import com.enterprise.mm.payload.MaterialAttributeRequest;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.payload.MaterialAnalogResponse;
import com.enterprise.mm.payload.MaterialConsumptionNormResponse;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilityResponse;
import com.enterprise.mm.payload.MaterialLifecycleRequest;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.EquipmentCreateRequest;
import com.enterprise.mm.payload.MaterialAnalogCreateRequest;
import com.enterprise.mm.payload.MaterialConsumptionNormCreateRequest;
import com.enterprise.mm.payload.MaterialEquipmentCompatibilityCreateRequest;
import com.enterprise.mm.payload.MaterialUpdateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.DemandPlanService;
import com.enterprise.mm.service.EquipmentService;
import com.enterprise.mm.service.MaterialAnalogService;
import com.enterprise.mm.service.MaterialConsumptionNormService;
import com.enterprise.mm.service.MaterialEquipmentCompatibilityService;
import com.enterprise.mm.service.MaterialService;
import com.enterprise.mm.enums.ConsumptionNormType;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.MaterialAnalogType;
import com.enterprise.mm.enums.MaterialCompatibilityType;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.MaterialAttributeRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "catalog-manager", roles = "ADMIN")
class MaterialCatalogizationTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired MaterialAttributeRepository materialAttributeRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired MXIKRepository mxikRepository;
    @Autowired MaterialService materialService;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired EquipmentService equipmentService;
    @Autowired MaterialAnalogService materialAnalogService;
    @Autowired MaterialEquipmentCompatibilityService compatibilityService;
    @Autowired MaterialConsumptionNormService normService;
    @Autowired DemandPlanService demandPlanService;
    @Autowired DemandPlanItemService demandPlanItemService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void materialCatalogSupportsAnalogsEquipmentNormsAndLifecycleControls() {
        Material main = material("MAT-CAT-MAIN");
        Material analog = material("MAT-CAT-ANALOG");
        Material replacement = material("MAT-CAT-REPLACEMENT");

        EquipmentCreateRequest equipmentRequest = new EquipmentCreateRequest();
        equipmentRequest.setCode("EQ-CAT-1");
        equipmentRequest.setName("Pump unit");
        equipmentRequest.setStatus("ACTIVE");
        equipmentRequest.setExternalEquipmentId("MES-EQ-1001");
        equipmentRequest.setModel("PMP-500");
        equipmentRequest.setManufacturer("UzPump");
        EquipmentResponse equipment = equipmentService.create(equipmentRequest);

        MaterialAnalogCreateRequest analogDtoRequest = new MaterialAnalogCreateRequest();
        analogDtoRequest.setName("Analog for pump material");
        analogDtoRequest.setMaterialId(main.getId());
        analogDtoRequest.setAnalogMaterialId(analog.getId());
        analogDtoRequest.setAnalogType(MaterialAnalogType.SUBSTITUTE.name());
        analogDtoRequest.setConversionFactor(BigDecimal.ONE);
        analogDtoRequest.setPriority(10);
        MaterialAnalogResponse analogDto = materialAnalogService.create(analogDtoRequest);
        assertThat(analogDto.getStatus()).isEqualTo("ACTIVE");

        MaterialEquipmentCompatibilityCreateRequest compatibilityRequest = new MaterialEquipmentCompatibilityCreateRequest();
        compatibilityRequest.setName("Pump spare compatibility");
        compatibilityRequest.setMaterialId(main.getId());
        compatibilityRequest.setEquipmentId(equipment.getId());
        compatibilityRequest.setCompatibilityType(MaterialCompatibilityType.SPARE_PART.name());
        compatibilityRequest.setManufacturerPartNo("PMP-SP-001");
        compatibilityRequest.setRequired(true);
        MaterialEquipmentCompatibilityResponse compatibility = compatibilityService.create(compatibilityRequest);
        assertThat(compatibility.getCompatibilityType()).isEqualTo(MaterialCompatibilityType.SPARE_PART.name());

        MaterialConsumptionNormCreateRequest normRequest = new MaterialConsumptionNormCreateRequest();
        normRequest.setName("Repair norm");
        normRequest.setMaterialId(main.getId());
        normRequest.setEquipmentId(equipment.getId());
        normRequest.setNormType(ConsumptionNormType.REPAIR.name());
        normRequest.setBaseQuantity(BigDecimal.valueOf(2));
        normRequest.setOutputQuantity(BigDecimal.ONE);
        normRequest.setLossPercent(BigDecimal.valueOf(1.5));
        MaterialConsumptionNormResponse norm = normService.create(normRequest);
        assertThat(norm.getBaseQuantity()).isEqualByComparingTo("2");

        var obsolete = materialService.markObsolete(main.getId(), new MaterialLifecycleRequest(replacement.getId(), "Replaced by modern item"));
        assertThat(obsolete.getStatus()).isEqualTo("OBSOLETE");
        assertThat(obsolete.getReplacementMaterialId()).isEqualTo(replacement.getId());
        assertThat(obsolete.getLifecycleChangedAt()).isNotNull();

        var prohibited = materialService.prohibit(analog.getId(), new MaterialLifecycleRequest(null, "Unsafe material"));
        assertThat(prohibited.getStatus()).isEqualTo("PROHIBITED");

        MaterialConsumptionNormCreateRequest prohibitedNorm = new MaterialConsumptionNormCreateRequest();
        prohibitedNorm.setName("Blocked norm");
        prohibitedNorm.setMaterialId(analog.getId());
        prohibitedNorm.setNormType(ConsumptionNormType.PRODUCTION.name());
        prohibitedNorm.setBaseQuantity(BigDecimal.ONE);
        prohibitedNorm.setOutputQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> normService.create(prohibitedNorm))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("prohibited");

        DemandPlanCreateRequest planRequest = new DemandPlanCreateRequest();
        planRequest.setName("Plan with prohibited material");
        planRequest.setPlanYear(Year.now().getValue());
        planRequest.setPeriodType(DemandPeriodType.YEAR.name());
        planRequest.setDepartmentId(department("DEP-CAT").getId());
        DemandPlanResponse plan = demandPlanService.create(planRequest);

        DemandPlanItemCreateRequest demandItem = new DemandPlanItemCreateRequest();
        demandItem.setName("Blocked demand");
        demandItem.setDemandPlanId(plan.getId());
        demandItem.setMaterialId(analog.getId());
        demandItem.setRequestedQuantity(BigDecimal.ONE);
        demandItem.setEstimatedUnitPrice(BigDecimal.ONE);
        assertThatThrownBy(() -> demandPlanItemService.create(demandItem))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("prohibited");
    }

    @Test
    void expiredShelfLifeAutomaticallyMarksMaterialObsolete() {
        Material material = new Material();
        material.setCode("MAT-EXPIRED-SHELF");
        material.setName("MAT-EXPIRED-SHELF");
        material.setStatus("ACTIVE");
        material.setShelfLifeDays(30);
        Material saved = materialRepository.saveAndFlush(material);
        jdbcTemplate.update("update materials set created_at = ? where id = ?", LocalDateTime.now().minusDays(31), saved.getId());

        var response = materialService.get(saved.getId());

        assertThat(response.getStatus()).isEqualTo("OBSOLETE");
        assertThat(response.getLifecycleChangedAt()).isNotNull();
        assertThat(materialRepository.findAnyById(saved.getId())).get().extracting(Material::getStatus).isEqualTo("OBSOLETE");
    }

    @Test
    void materialResponseIncludesUnitOfMeasureDisplayFields() {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode("KG-CAT");
        unit.setName("Kilogram catalog test");
        unit.setMeasurementNameShort("kg");
        unit.setMeasurementNameLong("Kilogram");
        UnitOfMeasure savedUnit = unitOfMeasureRepository.save(unit);

        Material material = new Material();
        material.setCode("MAT-UOM-CAT");
        material.setName("Material with UOM");
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(savedUnit.getId());
        Material saved = materialRepository.saveAndFlush(material);

        var response = materialService.get(saved.getId());

        assertThat(response.getUnitOfMeasureId()).isEqualTo(savedUnit.getId());
        assertThat(response.getUnitOfMeasureCode()).isEqualTo("KG-CAT");
        assertThat(response.getUnitOfMeasureShortName()).isEqualTo("kg");
        assertThat(response.getUnitOfMeasureName()).isEqualTo("Kilogram");
    }

    @Test
    void materialResponseIncludesCharacteristicDerivedFromMxik() {
        MXIK mxik = new MXIK();
        mxik.setKod("12345678901234567");
        mxik.setName("Catalog item");
        mxik.setType("GOODS");
        mxik.setAttributeName("Color, size");
        MXIK savedMxik = mxikRepository.save(mxik);

        Material material = new Material();
        material.setCode("MAT-MXIK-ATTR");
        material.setName("Material with characteristic");
        material.setStatus("ACTIVE");
        material.setMxikCode(savedMxik.getKod());
        material.setMxik(savedMxik);
        Material saved = materialRepository.saveAndFlush(material);

        var response = materialService.get(saved.getId());

        assertThat(response.getMxikId()).isEqualTo(savedMxik.getId());
        assertThat(response.getMxikCode()).isEqualTo(savedMxik.getKod());
        assertThat(response.getMxikAttributeName()).isEqualTo("Color, size");
    }

    @Test
    void materialCreateUpdateAndDeleteManageKeyValueAttributes() {
        MaterialGroup group = materialGroup("GRP-ATTR");
        UnitOfMeasure unit = unitOfMeasure("UOM-ATTR");

        MaterialCreateRequest createRequest = new MaterialCreateRequest();
        createRequest.setCode("MAT-ATTR-FLOW");
        createRequest.setName("Material attribute flow");
        createRequest.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        createRequest.setMaterialGroupId(group.getId());
        createRequest.setUnitOfMeasureId(unit.getId());
        createRequest.setAttributes(List.of(
            new MaterialAttributeRequest(" Rang ", " Qora "),
            new MaterialAttributeRequest("Diametr", "25 mm")
        ));

        var created = materialService.create(createRequest);

        assertThat(created.getAttributes()).hasSize(2);
        assertThat(created.getAttributes().get(0).key()).isEqualTo("Rang");
        assertThat(created.getAttributes().get(0).value()).isEqualTo("Qora");
        assertThat(created.getAttributes().get(0).sortOrder()).isEqualTo(0);
        assertThat(created.getAttributes().get(1).key()).isEqualTo("Diametr");

        MaterialUpdateRequest replaceRequest = new MaterialUpdateRequest();
        replaceRequest.setCode("MAT-ATTR-FLOW");
        replaceRequest.setName("Material attribute flow updated");
        replaceRequest.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        replaceRequest.setMaterialGroupId(group.getId());
        replaceRequest.setUnitOfMeasureId(unit.getId());
        replaceRequest.setAttributes(List.of(
            new MaterialAttributeRequest("rang", "Oq"),
            new MaterialAttributeRequest("Uzunlik", "10 m")
        ));

        var updated = materialService.update(created.getId(), replaceRequest);

        assertThat(updated.getAttributes()).hasSize(2);
        assertThat(updated.getAttributes().stream().map(attribute -> attribute.key()).toList())
            .containsExactly("rang", "Uzunlik");
        assertThat(updated.getAttributes().stream().map(attribute -> attribute.value()).toList())
            .containsExactly("Oq", "10 m");
        assertThat(materialAttributeRepository.findActiveByMaterialId(created.getId())).hasSize(2);

        MaterialUpdateRequest keepExistingRequest = new MaterialUpdateRequest();
        keepExistingRequest.setCode("MAT-ATTR-FLOW");
        keepExistingRequest.setName("Material attribute flow keep existing");
        keepExistingRequest.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        keepExistingRequest.setMaterialGroupId(group.getId());
        keepExistingRequest.setUnitOfMeasureId(unit.getId());

        var unchanged = materialService.update(created.getId(), keepExistingRequest);

        assertThat(unchanged.getAttributes().stream().map(attribute -> attribute.key()).toList())
            .containsExactly("rang", "Uzunlik");

        MaterialUpdateRequest clearRequest = new MaterialUpdateRequest();
        clearRequest.setCode("MAT-ATTR-FLOW");
        clearRequest.setName("Material attribute flow cleared");
        clearRequest.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        clearRequest.setMaterialGroupId(group.getId());
        clearRequest.setUnitOfMeasureId(unit.getId());
        clearRequest.setAttributes(List.of());

        var cleared = materialService.update(created.getId(), clearRequest);

        assertThat(cleared.getAttributes()).isEmpty();
        assertThat(materialAttributeRepository.findActiveByMaterialId(created.getId())).isEmpty();

        MaterialCreateRequest deletableRequest = new MaterialCreateRequest();
        deletableRequest.setCode("MAT-ATTR-DELETE");
        deletableRequest.setName("Material attribute delete");
        deletableRequest.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        deletableRequest.setMaterialGroupId(group.getId());
        deletableRequest.setUnitOfMeasureId(unit.getId());
        deletableRequest.setAttributes(List.of(new MaterialAttributeRequest("Color", "Black")));

        var deletable = materialService.create(deletableRequest);
        materialService.softDelete(deletable.getId());

        assertThat(materialAttributeRepository.findActiveByMaterialId(deletable.getId())).isEmpty();
    }

    @Test
    void materialMasterChangeIsWrittenToTransactionalOutboxForErpAndWms() throws Exception {
        MaterialGroup group = materialGroup("GRP-WMS-SYNC");
        UnitOfMeasure unit = unitOfMeasure("UOM-WMS-SYNC");
        MaterialCreateRequest request = new MaterialCreateRequest();
        request.setCode("MAT-WMS-SYNC");
        request.setName("Quality-controlled material");
        request.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        request.setAverageCost(BigDecimal.ONE);
        request.setMaterialGroupId(group.getId());
        request.setUnitOfMeasureId(unit.getId());

        var material = materialService.create(request);

        var events = integrationEventRepository.findAll().stream()
            .filter(candidate -> "mm.material.changed.v1".equals(candidate.getEventType()))
            .filter(candidate -> material.getId().equals(candidate.getAggregateId()))
            .toList();
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(candidate -> candidate.getAggregateType()).toList())
            .containsExactlyInAnyOrder("ERP", "WMS");
        for (var event : events) {
            var envelope = objectMapper.readTree(event.getPayload()).path("raw");

            assertThat(envelope.path("source").asText()).isEqualTo("mm");
            assertThat(envelope.path("target").asText()).isEqualTo(event.getAggregateType().toLowerCase());
            assertThat(envelope.path("type").asText()).isEqualTo("mm.material.changed.v1");
            assertThat(envelope.path("data").path("canonicalId").asText()).isEqualTo(material.getId().toString());
            assertThat(envelope.path("data").path("sku").asText()).isEqualTo("MAT-WMS-SYNC");
            assertThat(envelope.path("data").path("unit").asText()).isEqualTo("pcs");
            assertThat(envelope.path("data").path("qualityControlRequired").asBoolean()).isFalse();
            assertThat(envelope.path("data").path("active").asBoolean()).isTrue();
            assertThat(envelope.path("data").path("sourceRevision").asLong()).isEqualTo(material.getVersion());
        }

        MaterialUpdateRequest update = new MaterialUpdateRequest();
        update.setCode(material.getCode());
        update.setName(material.getName());
        update.setStatus(com.enterprise.mm.enums.MaterialStatus.INACTIVE);
        update.setAverageCost(BigDecimal.ONE);
        update.setMaterialGroupId(group.getId());
        update.setUnitOfMeasureId(unit.getId());
        var updated = materialService.update(material.getId(), update);
        var updateEnvelope = integrationEventRepository.findAll().stream()
            .filter(candidate -> "mm.material.changed.v1".equals(candidate.getEventType()))
            .filter(candidate -> material.getId().equals(candidate.getAggregateId()))
            .map(candidate -> {
                try {
                    return objectMapper.readTree(candidate.getPayload()).path("raw");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            })
            .max(java.util.Comparator.comparingLong(node -> node.path("aggregateVersion").asLong()))
            .orElseThrow();

        assertThat(updateEnvelope.path("data").path("active").asBoolean()).isFalse();
        assertThat(updateEnvelope.path("data").path("sourceRevision").asLong()).isEqualTo(updated.getVersion());
    }

    @Test
    void duplicateMaterialAttributeKeysRollbackMaterialCreate() {
        MaterialGroup group = materialGroup("GRP-ATTR-DUP");
        UnitOfMeasure unit = unitOfMeasure("UOM-ATTR-DUP");

        MaterialCreateRequest request = new MaterialCreateRequest();
        request.setCode("MAT-ATTR-DUP");
        request.setName("Material attribute duplicate");
        request.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        request.setMaterialGroupId(group.getId());
        request.setUnitOfMeasureId(unit.getId());
        request.setAttributes(List.of(
            new MaterialAttributeRequest("Rang", "Qora"),
            new MaterialAttributeRequest(" rang ", "Oq")
        ));

        assertThatThrownBy(() -> materialService.create(request))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getMessageKey())
            .isEqualTo("MATERIAL_ATTRIBUTE_DUPLICATE_KEY");

        assertThat(materialRepository.findByTenantIdAndCodeAndIsDeletedFalse("default", "MAT-ATTR-DUP"))
            .isEmpty();
    }

    private Material material(String code) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Department department(String code) {
        Department department = new Department();
        department.setCode(code);
        department.setName(code);
        department.setStatus("ACTIVE");
        return departmentRepository.save(department);
    }

    private MaterialGroup materialGroup(String code) {
        MaterialGroup group = new MaterialGroup();
        group.setCode(code);
        group.setName(code);
        group.setStatus("ACTIVE");
        return materialGroupRepository.save(group);
    }

    private UnitOfMeasure unitOfMeasure(String code) {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode(code);
        unit.setName(code);
        unit.setStatus("ACTIVE");
        unit.setMeasurementNameShort("pcs");
        unit.setMeasurementNameLong("Pieces");
        return unitOfMeasureRepository.save(unit);
    }
}
