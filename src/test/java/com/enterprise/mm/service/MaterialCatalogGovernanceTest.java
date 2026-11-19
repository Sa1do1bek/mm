package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Manufacturer;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialAnalog;
import com.enterprise.mm.entity.MaterialAttributeTemplate;
import com.enterprise.mm.entity.MaterialConsumptionNorm;
import com.enterprise.mm.entity.MaterialEquipmentCompatibility;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.MaterialDuplicateCandidate;
import com.enterprise.mm.entity.MaterialMasterRequest;
import com.enterprise.mm.entity.MaterialQualityIssue;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.MaterialAnalogMapper;
import com.enterprise.mm.mapper.EquipmentMapper;
import com.enterprise.mm.mapper.MaterialConsumptionNormMapper;
import com.enterprise.mm.mapper.MaterialEquipmentCompatibilityMapper;
import com.enterprise.mm.mapper.MaterialMapper;
import com.enterprise.mm.mapper.MaterialDuplicateCandidateMapper;
import com.enterprise.mm.mapper.MaterialMasterRequestMapper;
import com.enterprise.mm.mapper.MaterialQualityIssueMapper;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.payload.MaterialAttributeRequest;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupResult;
import com.enterprise.mm.payload.MaterialGovernanceActionRequest;
import com.enterprise.mm.payload.MaterialQualityIssueActionRequest;
import com.enterprise.mm.repository.ManufacturerRepository;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.MaterialAnalogRepository;
import com.enterprise.mm.repository.MaterialAttributeTemplateRepository;
import com.enterprise.mm.repository.MaterialConsumptionNormRepository;
import com.enterprise.mm.repository.MaterialEquipmentCompatibilityRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialDuplicateCandidateRepository;
import com.enterprise.mm.repository.MaterialMasterRequestRepository;
import com.enterprise.mm.repository.MaterialQualityIssueRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialValuationMethodHistoryRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.TnvedCodeRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MaterialCatalogGovernanceTest {
    private static final String TENANT = "material-catalog-governance";

    @Mock private MaterialRepository materialRepository;
    @Mock private MXIKRepository mxikRepository;
    @Mock private TnvedCodeRepository tnvedCodeRepository;
    @Mock private MaterialGroupRepository materialGroupRepository;
    @Mock private UnitOfMeasureRepository unitOfMeasureRepository;
    @Mock private MaterialValuationMethodHistoryRepository historyRepository;
    @Mock private MaterialUsageService materialUsageService;
    @Mock private AuditService auditService;
    @Mock private MaterialAttributeService materialAttributeService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ManufacturerRepository manufacturerRepository;
    @Mock private MaterialAttributeTemplateRepository templateRepository;
    @Mock private MaterialAnalogRepository analogRepository;
    @Mock private MaterialEquipmentCompatibilityRepository compatibilityRepository;
    @Mock private MaterialConsumptionNormRepository normRepository;
    @Mock private MaterialCatalogGuard catalogGuard;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private MaterialMasterRequestRepository masterRequestRepository;
    @Mock private MaterialDuplicateCandidateRepository duplicateRepository;
    @Mock private MaterialQualityIssueRepository qualityIssueRepository;
    @Mock private MaterialService governedMaterialService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        lenient().when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void catalogShelfLifeObsoletesExpiredActiveMaterialFromCreatedAt() {
        Material material = scoped(new Material());
        material.setCode("MAT-001");
        material.setName("Bearing");
        material.setStatus("ACTIVE");
        material.setShelfLifeDays(1);
        material.setCreatedAt(LocalDateTime.now().minusYears(1));
        when(materialRepository.findByIdAndIsDeletedFalse(material.getId())).thenReturn(Optional.of(material));
        when(materialAttributeService.getByMaterialId(material.getId())).thenReturn(List.of());

        MaterialService service = materialService();

        assertThat(service.get(material.getId()).getStatus()).isEqualTo("OBSOLETE");
        verify(materialRepository).saveAndFlush(material);
    }

    @Test
    void manufacturerMustBeActiveInTheSameCompanyAndResponseCarriesItsName() {
        UUID groupId = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();
        UUID manufacturerId = UUID.randomUUID();
        MaterialGroup group = scoped(new MaterialGroup());
        group.setId(groupId);
        group.setStatus("ACTIVE");
        UnitOfMeasure uom = scoped(new UnitOfMeasure());
        uom.setId(uomId);
        Manufacturer manufacturer = scoped(new Manufacturer());
        manufacturer.setId(manufacturerId);
        manufacturer.setName("SKF");
        manufacturer.setStatus("ACTIVE");
        when(materialGroupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, companyId, groupId))
            .thenReturn(Optional.of(group));
        lenient().when(unitOfMeasureRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, companyId, uomId))
            .thenReturn(Optional.of(uom));
        when(unitOfMeasureRepository.findByIdAndIsDeletedFalse(uomId)).thenReturn(Optional.of(uom));
        when(manufacturerRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, companyId, manufacturerId))
            .thenReturn(Optional.of(manufacturer));
        when(templateRepository.findActiveByScopeAndMaterialGroup(TENANT, companyId, groupId)).thenReturn(List.of());

        Material material = scoped(new Material());
        material.setCode("MAT-002");
        material.setName("Bearing");
        material.setMaterialGroupId(groupId);
        material.setUnitOfMeasureId(uomId);
        material.setManufacturerId(manufacturerId);

        MaterialService service = materialService();
        service.beforeCreate(material, new com.enterprise.mm.payload.MaterialCreateRequest());

        assertThat(material.getManufacturerRef()).isSameAs(manufacturer);
        assertThat(material.getManufacturer()).isEqualTo("SKF");
        assertThat(new MaterialMapper().toResponse(material).getManufacturerName()).isEqualTo("SKF");
    }

    @Test
    void fixedAssetMaterialCanCarrySupplierTraceabilityPolicies() {
        UUID groupId = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();
        stubRequiredMaterialReferences(groupId, uomId);

        var request = materialRequest(groupId, uomId);
        request.setMaterialType(MaterialType.FIXED_ASSET);
        request.setTrackingMode(TrackingMode.SERIAL);
        request.setBatchTrackingPolicy(BatchTrackingPolicy.REQUIRED);
        request.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        MaterialService service = materialService();
        ReflectionTestUtils.setField(service, "fixedAssetsEnabled", true);

        MaterialResponse response = service.create(request);

        assertThat(response.getMaterialType()).isEqualTo(MaterialType.FIXED_ASSET);
        assertThat(response.getTrackingMode()).isEqualTo(TrackingMode.SERIAL);
        assertThat(response.getBatchTrackingPolicy()).isEqualTo(BatchTrackingPolicy.REQUIRED);
        assertThat(response.getSerialTrackingPolicy()).isEqualTo(SerialTrackingPolicy.REQUIRED);
    }

    @Test
    void materialResponseDefaultsLegacyNullMaterialTypeToTmb() {
        Material material = scoped(new Material());
        material.setCode("MAT-LEGACY-001");
        material.setName("Legacy goods material");
        material.setMaterialType(null);

        assertThat(new MaterialMapper().toResponse(material).getMaterialType()).isEqualTo(MaterialType.TMB);
    }

    @Test
    void fixedAssetMaterialCreationIsBlockedWhileFeatureIsDisabled() {
        UUID groupId = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();
        stubRequiredMaterialReferences(groupId, uomId);
        com.enterprise.mm.payload.MaterialCreateRequest request = materialRequest(groupId, uomId);
        request.setMaterialType(MaterialType.FIXED_ASSET);

        assertThatThrownBy(() -> materialService().create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FIXED_ASSETS_DISABLED");
        verify(materialRepository, never()).saveAndFlush(any(Material.class));
    }

    @Test
    void updateToFixedAssetMaterialIsBlockedWhileFeatureIsDisabled() {
        UUID groupId = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();
        stubRequiredMaterialReferences(groupId, uomId);
        Material material = scoped(new Material());
        material.setCode("MAT-TMB-001");
        material.setName("Existing material");
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(groupId);
        material.setUnitOfMeasureId(uomId);
        material.setMaterialType(MaterialType.TMB);
        when(materialRepository.findByIdAndIsDeletedFalse(material.getId())).thenReturn(Optional.of(material));
        com.enterprise.mm.payload.MaterialUpdateRequest request = new com.enterprise.mm.payload.MaterialUpdateRequest();
        request.setCode("MAT-TMB-001");
        request.setName("Existing material");
        request.setMaterialGroupId(groupId);
        request.setUnitOfMeasureId(uomId);
        request.setMaterialType(MaterialType.FIXED_ASSET);
        request.setAttributes(List.of());

        assertThatThrownBy(() -> materialService().update(material.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FIXED_ASSETS_DISABLED");
        verify(materialRepository, never()).saveAndFlush(any(Material.class));
    }

    @Test
    void equipmentUsesTheSameScopedManufacturerReferenceContract() {
        UUID manufacturerId = UUID.randomUUID();
        Manufacturer manufacturer = scoped(new Manufacturer());
        manufacturer.setId(manufacturerId);
        manufacturer.setName("Siemens");
        manufacturer.setStatus("ACTIVE");
        when(manufacturerRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TENANT, companyId, manufacturerId)).thenReturn(Optional.of(manufacturer));
        EquipmentService service = new EquipmentService(
            equipmentRepository, new EquipmentMapper(), auditService, manufacturerRepository, roleDataScopeService);
        Equipment equipment = scoped(new Equipment());
        equipment.setManufacturerId(manufacturerId);

        service.beforeCreate(equipment, new com.enterprise.mm.payload.EquipmentCreateRequest());

        assertThat(equipment.getManufacturerRef()).isSameAs(manufacturer);
        assertThat(new EquipmentMapper().toResponse(equipment).getManufacturerName()).isEqualTo("Siemens");

        Equipment unknown = scoped(new Equipment());
        unknown.setManufacturerId(UUID.randomUUID());
        assertThatThrownBy(() -> service.beforeCreate(
            unknown, new com.enterprise.mm.payload.EquipmentCreateRequest()))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("current company");
    }

    @Test
    void requiredEnumTemplateAttributeIsEnforced() {
        UUID groupId = UUID.randomUUID();
        MaterialAttributeTemplate template = scoped(new MaterialAttributeTemplate());
        template.setMaterialGroupId(groupId);
        template.setAttributeName("grade");
        template.setRequired(true);
        template.setDataType("ENUM");
        template.setAllowedValues("A,B");
        template.setStatus("ACTIVE");
        MaterialAttributeTemplate numberTemplate = scoped(new MaterialAttributeTemplate());
        numberTemplate.setMaterialGroupId(groupId);
        numberTemplate.setAttributeName("weight");
        numberTemplate.setRequired(false);
        numberTemplate.setDataType("NUMBER");
        numberTemplate.setStatus("ACTIVE");
        when(templateRepository.findActiveByScopeAndMaterialGroup(TENANT, companyId, groupId))
            .thenReturn(List.of(template, numberTemplate));
        MaterialAttributeValidationService service = new MaterialAttributeValidationService(templateRepository);

        assertThatThrownBy(() -> service.validate(groupId, List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("grade");
        assertThatThrownBy(() -> service.validate(groupId, List.of(new MaterialAttributeRequest("grade", "C"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("allowed");
        assertThatThrownBy(() -> service.validate(groupId, List.of(
            new MaterialAttributeRequest("grade", "A"),
            new MaterialAttributeRequest("weight", "heavy")
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NUMBER");

        service.validate(groupId, List.of(
            new MaterialAttributeRequest("grade", "A"),
            new MaterialAttributeRequest("weight", "12.5")
        ));
    }

    @Test
    void analogRejectsDuplicateAndDirectedCycle() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        MaterialAnalogService service = new MaterialAnalogService(
            analogRepository, new MaterialAnalogMapper(), auditService, catalogGuard);
        MaterialAnalog duplicate = analog(source, target);
        when(analogRepository.existsActivePair(TENANT, companyId, source, target, null)).thenReturn(true);

        assertThatThrownBy(() -> service.beforeCreate(duplicate, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");

        MaterialAnalog cycle = analog(source, target);
        when(analogRepository.existsActivePair(TENANT, companyId, source, target, null)).thenReturn(false);
        when(analogRepository.findActiveOutgoing(TENANT, companyId, target))
            .thenReturn(List.of(analog(target, source)));
        assertThatThrownBy(() -> service.beforeCreate(cycle, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void equipmentCompatibilityRejectsDuplicateBusinessKey() {
        UUID materialId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        MaterialEquipmentCompatibility entity = scoped(new MaterialEquipmentCompatibility());
        entity.setMaterialId(materialId);
        entity.setEquipmentId(equipmentId);
        entity.setCompatibilityType("SPARE_PART");
        when(compatibilityRepository.existsActiveBusinessKey(
            TENANT, companyId, materialId, equipmentId, "SPARE_PART", null)).thenReturn(true);
        MaterialEquipmentCompatibilityService service = new MaterialEquipmentCompatibilityService(
            compatibilityRepository, new MaterialEquipmentCompatibilityMapper(), auditService, catalogGuard);

        assertThatThrownBy(() -> service.beforeCreate(entity, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void consumptionNormRejectsOverlappingEffectiveRangeForSameScope() {
        UUID materialId = UUID.randomUUID();
        MaterialConsumptionNorm entity = scoped(new MaterialConsumptionNorm());
        entity.setMaterialId(materialId);
        entity.setNormType("PRODUCTION");
        entity.setBaseQuantity(BigDecimal.ONE);
        entity.setOutputQuantity(BigDecimal.ONE);
        entity.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        entity.setEffectiveTo(LocalDate.of(2026, 12, 31));
        when(normRepository.existsOverlappingActiveNorm(
            TENANT, companyId, materialId, null, null, null, null, "PRODUCTION",
            entity.getEffectiveFrom(), entity.getEffectiveTo(), null)).thenReturn(true);
        MaterialConsumptionNormService service = new MaterialConsumptionNormService(
            normRepository, new MaterialConsumptionNormMapper(), auditService, catalogGuard, roleDataScopeService);

        assertThatThrownBy(() -> service.beforeCreate(entity, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("overlaps");
    }

    @Test
    void masterRequestUsesVersionedCommandStateMachineAndLocksGenericUpdate() {
        MaterialMasterRequest entity = scoped(new MaterialMasterRequest());
        entity.setStatus("DRAFT");
        entity.setVersion(4L);
        entity.setRequestType("CREATE");
        entity.setProposedName("New material");
        when(masterRequestRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(masterRequestRepository.save(entity)).thenReturn(entity);
        MaterialMasterRequestService service = new MaterialMasterRequestService(
            masterRequestRepository, new MaterialMasterRequestMapper(), auditService,
            catalogGuard, materialGroupRepository, unitOfMeasureRepository, governedMaterialService);

        assertThatThrownBy(() -> service.submit(
            entity.getId(), new MaterialGovernanceActionRequest(3L, "stale command")))
            .isInstanceOf(com.enterprise.mm.error.ConflictException.class);
        assertThat(entity.getStatus()).isEqualTo("DRAFT");

        var submitted = service.submit(entity.getId(), new MaterialGovernanceActionRequest(4L, "ready"));

        assertThat(submitted.getStatus()).isEqualTo("SUBMITTED");
        assertThat(entity.getSubmittedAt()).isNotNull();
        entity.setVersion(5L);
        assertThatThrownBy(() -> service.approve(
            entity.getId(), new MaterialGovernanceActionRequest(5L, "skip review")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SUBMITTED");

        com.enterprise.mm.payload.MaterialMasterRequestUpdateRequest update =
            new com.enterprise.mm.payload.MaterialMasterRequestUpdateRequest();
        assertThatThrownBy(() -> service.update(entity.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("editable");
    }

    @Test
    void duplicateCandidateMustBeConfirmedBeforeMerge() {
        MaterialDuplicateCandidate entity = scoped(new MaterialDuplicateCandidate());
        entity.setStatus("OPEN");
        entity.setVersion(2L);
        entity.setMaterialId(UUID.randomUUID());
        entity.setCandidateMaterialId(UUID.randomUUID());
        when(duplicateRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(duplicateRepository.save(entity)).thenReturn(entity);
        MaterialDuplicateCandidateService service = new MaterialDuplicateCandidateService(
            duplicateRepository, new MaterialDuplicateCandidateMapper(), catalogGuard, auditService,
            governedMaterialService);

        assertThatThrownBy(() -> service.merge(
            entity.getId(), new MaterialGovernanceActionRequest(2L, "merge now")))
            .isInstanceOf(BusinessException.class);

        service.confirm(entity.getId(), new MaterialGovernanceActionRequest(2L, "same item"));
        assertThat(entity.getStatus()).isEqualTo("CONFIRMED");
        entity.setVersion(3L);
        service.merge(entity.getId(), new MaterialGovernanceActionRequest(3L, "canonical selected"));
        assertThat(entity.getStatus()).isEqualTo("MERGED");
        assertThat(entity.getMergedAt()).isNotNull();
        verify(governedMaterialService).markObsolete(
            entity.getCandidateMaterialId(),
            new com.enterprise.mm.payload.MaterialLifecycleRequest(
                entity.getMaterialId(), "canonical selected")
        );
    }

    @Test
    void aiScanResultRefreshesOpenCanonicalPairWithoutReopeningRejectedCandidates() throws Exception {
        UUID jobId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID firstMaterialId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID secondMaterialId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        MaterialDuplicateCandidate open = scoped(new MaterialDuplicateCandidate());
        open.setMaterialId(firstMaterialId);
        open.setCandidateMaterialId(secondMaterialId);
        open.setStatus("OPEN");
        open.setSimilarityScore(BigDecimal.valueOf(0.70));
        open.setReason("old reason");
        when(duplicateRepository.findActiveByCanonicalPair(TENANT, companyId, firstMaterialId, secondMaterialId))
            .thenReturn(Optional.of(open));
        when(duplicateRepository.save(open)).thenReturn(open);
        MaterialDedupResult result = new ObjectMapper().findAndRegisterModules().readValue("""
            {
              "query":{"name":"catalog scan","domain":"BEARING","domainAmbiguous":false},
              "matches":[
                {
                  "materialId":"20000000-0000-0000-0000-000000000002",
                  "candidateMaterialId":"10000000-0000-0000-0000-000000000001",
                  "candidateMaterialName":"Bearing duplicate",
                  "similarityScore":"0.9400",
                  "similarityPercent":"94.00",
                  "verdict":"LIKELY_DUPLICATE",
                  "reason":"Same bearing dimensions",
                  "signals":[{"kind":"POSITIVE","detail":"Exact dimensions","weight":"0.75"}]
                }
              ],
              "llmUsed":true
            }
            """, MaterialDedupResult.class);

        MaterialDuplicateCandidateService service = new MaterialDuplicateCandidateService(
            duplicateRepository, new MaterialDuplicateCandidateMapper(), catalogGuard, auditService,
            governedMaterialService);

        int changed = service.importAiScanResult(jobId, result);

        assertThat(changed).isEqualTo(1);
        assertThat(open.getMaterialId()).isEqualTo(firstMaterialId);
        assertThat(open.getCandidateMaterialId()).isEqualTo(secondMaterialId);
        assertThat(open.getSimilarityScore()).isEqualByComparingTo("0.9400");
        assertThat(open.getReason()).isEqualTo("Same bearing dimensions");
        assertThat(open.getAiSourceJobId()).isEqualTo(jobId);
        assertThat(open.getAiEvidenceJson()).contains("Exact dimensions");
        assertThat(open.getStatus()).isEqualTo("OPEN");
        verify(duplicateRepository).save(open);
    }

    @Test
    void applyingApprovedCreateRequestCreatesTheMaterialAtomically() {
        MaterialMasterRequest entity = scoped(new MaterialMasterRequest());
        entity.setStatus("APPROVED");
        entity.setVersion(8L);
        entity.setRequestType("CREATE");
        entity.setProposedName("Governed bearing");
        entity.setMaterialGroupId(UUID.randomUUID());
        entity.setUomId(UUID.randomUUID());
        when(masterRequestRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(masterRequestRepository.save(entity)).thenReturn(entity);
        com.enterprise.mm.payload.MaterialResponse created = new com.enterprise.mm.payload.MaterialResponse();
        created.setId(UUID.randomUUID());
        when(governedMaterialService.create(any())).thenReturn(created);
        MaterialMasterRequestService service = new MaterialMasterRequestService(
            masterRequestRepository, new MaterialMasterRequestMapper(), auditService,
            catalogGuard, materialGroupRepository, unitOfMeasureRepository, governedMaterialService);

        var response = service.apply(
            entity.getId(), new MaterialGovernanceActionRequest(8L, "approved master data"));

        assertThat(response.getStatus()).isEqualTo("APPLIED");
        assertThat(response.getMaterialId()).isEqualTo(created.getId());
        verify(governedMaterialService).create(org.mockito.ArgumentMatchers.argThat(request ->
            "Governed bearing".equals(request.getName())
                && entity.getMaterialGroupId().equals(request.getMaterialGroupId())
                && entity.getUomId().equals(request.getUnitOfMeasureId())
        ));
    }

    @Test
    void applyingApprovedMergeRequestObsoletesSourceInFavorOfCanonicalTarget() {
        MaterialMasterRequest entity = scoped(new MaterialMasterRequest());
        entity.setStatus("APPROVED");
        entity.setVersion(6L);
        entity.setRequestType("MERGE");
        entity.setMaterialId(UUID.randomUUID());
        entity.setTargetMaterialId(UUID.randomUUID());
        when(masterRequestRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(masterRequestRepository.save(entity)).thenReturn(entity);
        MaterialMasterRequestService service = new MaterialMasterRequestService(
            masterRequestRepository, new MaterialMasterRequestMapper(), auditService,
            catalogGuard, materialGroupRepository, unitOfMeasureRepository, governedMaterialService);

        service.apply(entity.getId(), new MaterialGovernanceActionRequest(6L, "merge duplicate"));

        verify(governedMaterialService).markObsolete(
            entity.getMaterialId(),
            new com.enterprise.mm.payload.MaterialLifecycleRequest(
                entity.getTargetMaterialId(), "merge duplicate")
        );
        assertThat(entity.getStatus()).isEqualTo("APPLIED");
    }

    @Test
    void qualityIssueRequiresAssignmentAndResolutionEvidence() {
        MaterialQualityIssue entity = scoped(new MaterialQualityIssue());
        entity.setStatus("OPEN");
        entity.setVersion(1L);
        entity.setMaterialId(UUID.randomUUID());
        entity.setIssueType("MISSING_BARCODE");
        entity.setSeverity("HIGH");
        when(qualityIssueRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        when(qualityIssueRepository.save(entity)).thenReturn(entity);
        MaterialQualityIssueService service = new MaterialQualityIssueService(
            qualityIssueRepository, new MaterialQualityIssueMapper(), catalogGuard, auditService);

        service.assign(entity.getId(), new MaterialQualityIssueActionRequest(
            1L, "assign owner", "catalog.owner", null));
        assertThat(entity.getStatus()).isEqualTo("ASSIGNED");
        entity.setVersion(2L);
        service.start(entity.getId(), new MaterialQualityIssueActionRequest(
            2L, "work started", null, null));
        assertThat(entity.getStatus()).isEqualTo("IN_PROGRESS");
        entity.setVersion(3L);
        assertThatThrownBy(() -> service.resolve(entity.getId(), new MaterialQualityIssueActionRequest(
            3L, "fixed", null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Evidence");

        service.resolve(entity.getId(), new MaterialQualityIssueActionRequest(
            3L, "fixed", null, "ATTACHMENT:123"));
        assertThat(entity.getStatus()).isEqualTo("RESOLVED");
        assertThat(entity.getResolvedAt()).isNotNull();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> trackingCombinations() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        var validGoods = java.util.Set.of(
            "QUANTITY/NONE/NONE", "QUANTITY/OPTIONAL/NONE", "LOT/REQUIRED/NONE",
            "SERIAL/NONE/REQUIRED", "SERIAL/OPTIONAL/REQUIRED", "SERIAL/REQUIRED/REQUIRED");
        for (boolean update : new boolean[] {false, true}) {
            for (MaterialType type : new MaterialType[] {MaterialType.TMB, MaterialType.FIXED_ASSET, MaterialType.SERVICES}) {
                for (TrackingMode mode : TrackingMode.values()) {
                    for (BatchTrackingPolicy batch : BatchTrackingPolicy.values()) {
                        for (SerialTrackingPolicy serial : SerialTrackingPolicy.values()) {
                            String combination = mode + "/" + batch + "/" + serial;
                            boolean valid = type == MaterialType.SERVICES
                                ? combination.equals("QUANTITY/NONE/NONE") : validGoods.contains(combination);
                            cases.add(org.junit.jupiter.params.provider.Arguments.of(update, type, mode, batch, serial, valid));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("trackingCombinations")
    void createAndUpdateValidateTrackingCombinations(boolean update, MaterialType type, TrackingMode mode,
        BatchTrackingPolicy batch, SerialTrackingPolicy serial, boolean valid) {
        UUID groupId = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();
        stubRequiredMaterialReferences(groupId, uomId);
        var request = new com.enterprise.mm.payload.MaterialUpdateRequest();
        request.setCode("MAT-TRACKING");
        request.setName("Tracking validation");
        request.setStatus(com.enterprise.mm.enums.MaterialStatus.ACTIVE);
        request.setMaterialGroupId(groupId);
        request.setUnitOfMeasureId(uomId);
        request.setAttributes(List.of());
        request.setMaterialType(type);
        request.setTrackingMode(mode);
        request.setBatchTrackingPolicy(batch);
        request.setSerialTrackingPolicy(serial);
        MaterialService service = materialService();
        ReflectionTestUtils.setField(service, "fixedAssetsEnabled", true);
        UUID id = UUID.randomUUID();
        if (update) {
            Material existing = scoped(new Material());
            existing.setId(id);
            existing.setCode(request.getCode());
            existing.setName(request.getName());
            existing.setStatus("ACTIVE");
            existing.setMaterialType(type);
            existing.setUnitOfMeasureId(uomId);
            when(materialRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(existing));
        }
        java.util.function.Supplier<MaterialResponse> action = () -> update
            ? service.update(id, request) : service.create(request);
        if (valid) {
            MaterialResponse response = action.get();
            assertThat(response.getTrackingMode()).isEqualTo(mode);
            assertThat(response.getBatchTrackingPolicy()).isEqualTo(batch);
            assertThat(response.getSerialTrackingPolicy()).isEqualTo(serial);
        } else {
            assertThatThrownBy(action::get)
                .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
                .hasMessageContaining(type == MaterialType.SERVICES ? "SERIAL_TRACKING_NOT_ALLOWED" : "Invalid tracking combination");
            verify(materialRepository, never()).saveAndFlush(any(Material.class));
            verify(materialRepository, never()).save(any(Material.class));
        }
    }

    private MaterialService materialService() {
        MaterialAttributeValidationService validation = new MaterialAttributeValidationService(templateRepository);
        return new MaterialService(
            materialRepository,
            new MaterialMapper(),
            mxikRepository,
            tnvedCodeRepository,
            materialGroupRepository,
            unitOfMeasureRepository,
            historyRepository,
            materialUsageService,
            auditService,
            materialAttributeService,
            manufacturerRepository,
            validation,
            org.mockito.Mockito.mock(MaterialAdviceService.class),
            org.mockito.Mockito.mock(MaterialWmsIntegrationPublisher.class)
        );
    }

    private void stubRequiredMaterialReferences(UUID groupId, UUID uomId) {
        MaterialGroup group = scoped(new MaterialGroup());
        group.setId(groupId);
        group.setStatus("ACTIVE");
        UnitOfMeasure uom = scoped(new UnitOfMeasure());
        uom.setId(uomId);
        when(materialGroupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TENANT, companyId, groupId))
            .thenReturn(Optional.of(group));
        when(unitOfMeasureRepository.findByIdAndIsDeletedFalse(uomId)).thenReturn(Optional.of(uom));
        when(templateRepository.findActiveByScopeAndMaterialGroup(TENANT, companyId, groupId)).thenReturn(List.of());
    }

    private com.enterprise.mm.payload.MaterialCreateRequest materialRequest(UUID groupId, UUID uomId) {
        com.enterprise.mm.payload.MaterialCreateRequest request = new com.enterprise.mm.payload.MaterialCreateRequest();
        request.setCode("MAT-FA-001");
        request.setName("Fixed asset candidate");
        request.setMaterialGroupId(groupId);
        request.setUnitOfMeasureId(uomId);
        request.setAttributes(List.of());
        return request;
    }

    private MaterialAnalog analog(UUID source, UUID target) {
        MaterialAnalog entity = scoped(new MaterialAnalog());
        entity.setMaterialId(source);
        entity.setAnalogMaterialId(target);
        entity.setAnalogType("SUBSTITUTE");
        return entity;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T scoped(T entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
        entity.setDeleted(false);
        return entity;
    }
}
