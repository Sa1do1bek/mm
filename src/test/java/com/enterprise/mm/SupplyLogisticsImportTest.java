package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.AdvanceShippingNoticeResponse;
import com.enterprise.mm.payload.CustomsDeclarationResponse;
import com.enterprise.mm.payload.ImportDeliveryResponse;
import com.enterprise.mm.payload.ImportFreightBookingResponse;
import com.enterprise.mm.payload.ImportInsurancePolicyResponse;
import com.enterprise.mm.payload.IncotermResponse;
import com.enterprise.mm.payload.LandedLogisticsEventResponse;
import com.enterprise.mm.payload.AdvanceShippingNoticeCreateRequest;
import com.enterprise.mm.payload.AdvanceShippingNoticeItemCreateRequest;
import com.enterprise.mm.payload.CustomsDeclarationCreateRequest;
import com.enterprise.mm.payload.ImportDeliveryCreateRequest;
import com.enterprise.mm.payload.ImportFreightBookingCreateRequest;
import com.enterprise.mm.payload.ImportInsurancePolicyCreateRequest;
import com.enterprise.mm.payload.IncotermCreateRequest;
import com.enterprise.mm.payload.LandedLogisticsEventCreateRequest;
import com.enterprise.mm.service.AdvanceShippingNoticeItemService;
import com.enterprise.mm.service.AdvanceShippingNoticeService;
import com.enterprise.mm.service.CustomsDeclarationService;
import com.enterprise.mm.service.ImportDeliveryService;
import com.enterprise.mm.service.ImportFreightBookingService;
import com.enterprise.mm.service.ImportInsurancePolicyService;
import com.enterprise.mm.service.IncotermService;
import com.enterprise.mm.service.LandedLogisticsEventService;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.CustomsDeclarationStatus;
import com.enterprise.mm.enums.ImportFreightStatus;
import com.enterprise.mm.enums.ImportInsuranceStatus;
import com.enterprise.mm.enums.ImportDeliveryStatus;
import com.enterprise.mm.enums.LandedLogisticsEventType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "logistics-manager", roles = "PROCUREMENT_MANAGER")
class SupplyLogisticsImportTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierAccreditationApplicationRepository supplierAccreditationApplicationRepository;
    @Autowired SupplierAccreditationDecisionRepository supplierAccreditationDecisionRepository;
    @Autowired SupplierAccreditationDocumentRepository supplierAccreditationDocumentRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired ImportDeliveryRepository importDeliveryRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired IncotermService incotermService;
    @Autowired AdvanceShippingNoticeService asnService;
    @Autowired AdvanceShippingNoticeItemService asnItemService;
    @Autowired ImportDeliveryService importDeliveryService;
    @Autowired ImportFreightBookingService freightBookingService;
    @Autowired ImportInsurancePolicyService insurancePolicyService;
    @Autowired CustomsDeclarationService customsDeclarationService;
    @Autowired LandedLogisticsEventService logisticsEventService;

    @Test
    void importLogisticsFlowCreatesAsnCustomsLandedCostAndTrackingEvents() {
        Material material = material("MAT-IMP");
        Supplier supplier = supplier("SUP-IMP");
        accreditSupplier(supplier);
        Warehouse warehouse = warehouse("WH-IMP");
        PurchaseOrder po = purchaseOrder("PO-IMP", supplier.getId());
        PurchaseOrderItem poItem = purchaseOrderItem("POI-IMP", po.getId(), material.getId());
        IncotermResponse incoterm = incoterm("CIP");

        AdvanceShippingNoticeCreateRequest asnRequest = new AdvanceShippingNoticeCreateRequest();
        asnRequest.setName("Import ASN");
        asnRequest.setAsnNumber("ASN-" + suffix());
        asnRequest.setSupplierId(supplier.getId());
        asnRequest.setPurchaseOrderId(po.getId());
        asnRequest.setIncotermId(incoterm.getId());
        asnRequest.setNoticeDate(LocalDate.now());
        asnRequest.setExpectedShipDate(LocalDate.now().plusDays(2));
        asnRequest.setExpectedArrivalDate(LocalDate.now().plusDays(12));
        asnRequest.setTransportMode("ROAD");
        asnRequest.setCarrierName("UzTrans Logistics");
        asnRequest.setOriginCountry("TUR");
        asnRequest.setDestinationCountry("UZB");
        asnRequest.setTotalAmount(BigDecimal.valueOf(1000));
        asnRequest.setCurrencyCode("USD");
        AdvanceShippingNoticeResponse asn = asnService.create(asnRequest);

        AdvanceShippingNoticeItemCreateRequest asnItem = new AdvanceShippingNoticeItemCreateRequest();
        asnItem.setName("ASN material line");
        asnItem.setAsnId(asn.getId());
        asnItem.setPurchaseOrderItemId(poItem.getId());
        asnItem.setMaterialId(material.getId());
        asnItem.setQuantity(BigDecimal.TEN);
        asnItem.setGrossWeight(BigDecimal.valueOf(120));
        asnItem.setNetWeight(BigDecimal.valueOf(100));
        asnItemService.create(asnItem);

        asn = asnService.submit(asn.getId());
        assertThat(asn.getStatus()).isEqualTo(AdvanceShippingNoticeStatus.SENT.name());
        assertThat(asn.getTotalQuantity()).isEqualByComparingTo("10");
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "ETTN.ASN_SENT".equals(event.getEventType()));

        asn = asnService.confirm(asn.getId());
        assertThat(asn.getStatus()).isEqualTo(AdvanceShippingNoticeStatus.CONFIRMED.name());

        ImportDeliveryCreateRequest deliveryRequest = new ImportDeliveryCreateRequest();
        deliveryRequest.setName("Import shipment");
        deliveryRequest.setShipmentNumber("SHIP-" + suffix());
        deliveryRequest.setSupplierId(supplier.getId());
        deliveryRequest.setPurchaseOrderId(po.getId());
        deliveryRequest.setAsnId(asn.getId());
        deliveryRequest.setIncotermId(incoterm.getId());
        deliveryRequest.setDestinationWarehouseId(warehouse.getId());
        deliveryRequest.setTransportMode("ROAD");
        deliveryRequest.setCarrierName("UzTrans Logistics");
        deliveryRequest.setForwarderName("Border Forwarder");
        deliveryRequest.setBrokerName("Customs Broker");
        deliveryRequest.setOriginCountry("TUR");
        deliveryRequest.setBorderPost("Yallama");
        deliveryRequest.setDestinationCountry("UZB");
        deliveryRequest.setDestinationPlace("Tashkent main warehouse");
        deliveryRequest.setEtd(LocalDate.now().plusDays(1));
        deliveryRequest.setEta(LocalDate.now().plusDays(10));
        deliveryRequest.setBaseGoodsAmount(BigDecimal.valueOf(1000));
        deliveryRequest.setCurrencyCode("USD");
        ImportDeliveryResponse delivery = importDeliveryService.create(deliveryRequest);
        assertThat(delivery.getStatus()).isEqualTo(ImportDeliveryStatus.PLANNED.name());
        assertThat(delivery.getLandedCostAmount()).isEqualByComparingTo("1000");

        delivery = importDeliveryService.book(delivery.getId());
        assertThat(delivery.getStatus()).isEqualTo(ImportDeliveryStatus.BOOKED.name());
        delivery = importDeliveryService.startTransit(delivery.getId());
        assertThat(delivery.getStatus()).isEqualTo(ImportDeliveryStatus.IN_TRANSIT.name());
        delivery = importDeliveryService.arriveCustoms(delivery.getId());
        assertThat(delivery.getStatus()).isEqualTo(ImportDeliveryStatus.AT_CUSTOMS.name());

        CustomsDeclarationCreateRequest declarationRequest = new CustomsDeclarationCreateRequest();
        declarationRequest.setName("GTД for import shipment");
        declarationRequest.setImportDeliveryId(delivery.getId());
        declarationRequest.setSupplierId(supplier.getId());
        declarationRequest.setDeclarationNumber("GTD-" + suffix());
        declarationRequest.setDeclarationDate(LocalDate.now());
        declarationRequest.setCustomsPostCode("26001");
        declarationRequest.setCustomsRegime("IMPORT_40");
        declarationRequest.setBrokerName("Customs Broker");
        declarationRequest.setCountryOfOrigin("TUR");
        declarationRequest.setCustomsValue(BigDecimal.valueOf(1000));
        declarationRequest.setDutyAmount(BigDecimal.valueOf(50));
        declarationRequest.setVatAmount(BigDecimal.valueOf(120));
        declarationRequest.setFeesAmount(BigDecimal.TEN);
        declarationRequest.setCurrencyCode("USD");
        CustomsDeclarationResponse declaration = customsDeclarationService.create(declarationRequest);
        declaration = customsDeclarationService.submit(declaration.getId());
        declaration = customsDeclarationService.accept(declaration.getId());
        declaration = customsDeclarationService.release(declaration.getId());
        assertThat(declaration.getStatus()).isEqualTo(CustomsDeclarationStatus.RELEASED.name());
        assertThat(declaration.getTotalCustomsAmount()).isEqualByComparingTo("180");
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "CUSTOMS.DECLARATION_RELEASED".equals(event.getEventType()));

        ImportDelivery releasedDelivery = importDeliveryRepository.findAnyById(delivery.getId()).orElseThrow();
        assertThat(releasedDelivery.getStatus()).isEqualTo(ImportDeliveryStatus.CLEARED.name());
        assertThat(releasedDelivery.getCustomsDeclarationId()).isEqualTo(declaration.getId());
        assertThat(releasedDelivery.getCustomsCostAmount()).isEqualByComparingTo("180");
        assertThat(releasedDelivery.getLandedCostAmount()).isEqualByComparingTo("1180");

        ImportFreightBookingCreateRequest freightRequest = new ImportFreightBookingCreateRequest();
        freightRequest.setName("International freight booking");
        freightRequest.setBookingNumber("FR-" + suffix());
        freightRequest.setImportDeliveryId(delivery.getId());
        freightRequest.setCarrierSupplierId(supplier.getId());
        freightRequest.setTransportMode("ROAD");
        freightRequest.setFreightAmount(BigDecimal.valueOf(180));
        freightRequest.setFuelSurchargeAmount(BigDecimal.valueOf(20));
        freightRequest.setCurrencyCode("USD");
        freightRequest.setAllocationMethod("VALUE");
        freightRequest.setInvoiceNumber("FR-INV-" + suffix());
        freightRequest.setInvoiceDate(LocalDate.now());
        freightRequest.setIncludedInInventoryCost(true);
        ImportFreightBookingResponse freight = freightBookingService.create(freightRequest);
        freight = freightBookingService.book(freight.getId());
        assertThat(freight.getStatus()).isEqualTo(ImportFreightStatus.BOOKED.name());
        freight = freightBookingService.startTransit(freight.getId());
        freight = freightBookingService.deliver(freight.getId());
        assertThat(freight.getStatus()).isEqualTo(ImportFreightStatus.DELIVERED.name());
        freight = freightBookingService.postCost(freight.getId());
        assertThat(freight.getStatus()).isEqualTo(ImportFreightStatus.POSTED.name());
        assertThat(freight.getTotalFreightAmount()).isEqualByComparingTo("200");
        assertThat(freight.getLandedCostComponentId()).isNotNull();

        ImportInsurancePolicyCreateRequest insuranceRequest = new ImportInsurancePolicyCreateRequest();
        insuranceRequest.setName("Cargo insurance policy");
        insuranceRequest.setPolicyNumber("INS-" + suffix());
        insuranceRequest.setImportDeliveryId(delivery.getId());
        insuranceRequest.setInsurerSupplierId(supplier.getId());
        insuranceRequest.setInsuredAmount(BigDecimal.valueOf(1000));
        insuranceRequest.setPremiumAmount(BigDecimal.valueOf(35));
        insuranceRequest.setDeductibleAmount(BigDecimal.valueOf(10));
        insuranceRequest.setCurrencyCode("USD");
        insuranceRequest.setAllocationMethod("VALUE");
        insuranceRequest.setValidFrom(LocalDate.now());
        insuranceRequest.setValidTo(LocalDate.now().plusDays(45));
        insuranceRequest.setIncludedInInventoryCost(true);
        ImportInsurancePolicyResponse insurance = insurancePolicyService.create(insuranceRequest);
        insurance = insurancePolicyService.activate(insurance.getId());
        assertThat(insurance.getStatus()).isEqualTo(ImportInsuranceStatus.ACTIVE.name());
        assertThat(insurance.getLandedCostComponentId()).isNotNull();

        delivery = importDeliveryService.recalculate(delivery.getId());
        assertThat(delivery.getLogisticsCostAmount()).isEqualByComparingTo("235");
        assertThat(delivery.getCustomsCostAmount()).isEqualByComparingTo("180");
        assertThat(delivery.getLandedCostAmount()).isEqualByComparingTo("1415");

        LandedLogisticsEventCreateRequest deliveredEvent = new LandedLogisticsEventCreateRequest();
        deliveredEvent.setName("Warehouse arrival");
        deliveredEvent.setImportDeliveryId(delivery.getId());
        deliveredEvent.setEventType(LandedLogisticsEventType.DELIVERED.name());
        deliveredEvent.setEventDate(LocalDateTime.now());
        deliveredEvent.setLocation("Tashkent main warehouse");
        deliveredEvent.setCarrierStatus("Delivered");
        logisticsEventService.create(deliveredEvent);

        ImportDelivery delivered = importDeliveryRepository.findAnyById(delivery.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(ImportDeliveryStatus.DELIVERED.name());
        assertThat(delivered.getCurrentMilestone()).isEqualTo(LandedLogisticsEventType.DELIVERED.name());

        LandedLogisticsEventCreateRequest exceptionRequest = new LandedLogisticsEventCreateRequest();
        exceptionRequest.setName("Damage claim");
        exceptionRequest.setDescription("Packaging damage discovered during unloading");
        exceptionRequest.setImportDeliveryId(delivery.getId());
        exceptionRequest.setEventType(LandedLogisticsEventType.EXCEPTION.name());
        exceptionRequest.setEventDate(LocalDateTime.now());
        LandedLogisticsEventResponse exception = logisticsEventService.create(exceptionRequest);
        assertThat(importDeliveryRepository.findAnyById(delivery.getId()).orElseThrow().isRiskFlag()).isTrue();

        exception = logisticsEventService.resolve(exception.getId());
        assertThat(exception.isExceptionFlag()).isFalse();
        ImportDelivery resolved = importDeliveryRepository.findAnyById(delivery.getId()).orElseThrow();
        assertThat(resolved.isRiskFlag()).isFalse();
        assertThat(resolved.getCurrentMilestone()).isEqualTo(LandedLogisticsEventType.DELIVERED.name());
    }

    private IncotermResponse incoterm(String code) {
        IncotermCreateRequest dto = new IncotermCreateRequest();
        dto.setCode(code);
        dto.setName(code + " Incoterms 2020");
        dto.setVersionYear(2020);
        dto.setModeScope("ANY");
        dto.setNamedPlaceRequired(true);
        return incotermService.create(dto);
    }

    private Material material(String prefix) {
        MaterialGroup group = defaultMaterialGroup();
        String code = prefix + "-" + suffix();
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(group.getId());
        return materialRepository.save(material);
    }

    private MaterialGroup defaultMaterialGroup() {
        return materialGroupRepository.findAll().stream()
            .filter(group -> !group.isDeleted() && "IMP-DEFAULT-GROUP".equals(group.getCode()))
            .findFirst()
            .orElseGet(() -> {
                MaterialGroup group = new MaterialGroup();
                group.setCode("IMP-DEFAULT-GROUP");
                group.setName("Import default material group");
                group.setStatus("ACTIVE");
                return materialGroupRepository.save(group);
            });
    }

    private Supplier supplier(String prefix) {
        String code = prefix + "-" + suffix();
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setRating(BigDecimal.valueOf(90));
        supplier.setCategory("A");
        return supplierRepository.save(supplier);
    }

    private void accreditSupplier(Supplier supplier) {
        MaterialGroup group = defaultMaterialGroup();
        saveAccreditation(supplier, "GOODS", List.of(group.getId()), "Industrial materials");
    }

    private void saveAccreditation(Supplier supplier, String type, List<UUID> groupIds, String approvedScope) {
        SupplierAccreditationApplication application = new SupplierAccreditationApplication();
        application.setCode("ACC-" + type + "-" + supplier.getCode());
        application.setName("Accreditation " + type + " " + supplier.getCode());
        application.setStatus(SupplierAccreditationStatus.APPROVED.name());
        application.setSupplierId(supplier.getId());
        application.setAccreditationType(type);
        application.setRequestedScope(approvedScope);
        application.setRequestedMaterialGroupIds(groupIds);
        application.setValidFrom(LocalDate.now().minusDays(1));
        application.setValidTo(LocalDate.now().plusYears(1));
        application = supplierAccreditationApplicationRepository.save(application);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setCode("ACC-DEC-" + type + "-" + supplier.getCode());
        decision.setName("Decision " + type + " " + supplier.getCode());
        decision.setStatus("RECORDED");
        decision.setApplicationId(application.getId());
        decision.setDecision(SupplierAccreditationStatus.APPROVED.name());
        decision.setApprovedScope(approvedScope);
        decision.setAllowedMaterialGroupIds(groupIds);
        decision.setValidFrom(LocalDate.now().minusDays(1));
        decision.setValidTo(LocalDate.now().plusYears(1));
        supplierAccreditationDecisionRepository.save(decision);
        saveVerifiedAccreditationEvidence(application);
    }

    private void saveVerifiedAccreditationEvidence(SupplierAccreditationApplication application) {
        SupplierAccreditationDocument document = new SupplierAccreditationDocument();
        document.setApplicationId(application.getId());
        document.setDocumentType("REGISTRATION_CERTIFICATE");
        document.setStatus("VERIFIED");
        document.setValidFrom(LocalDate.now().minusDays(1));
        document.setValidTo(LocalDate.now().plusYears(1));
        document = supplierAccreditationDocumentRepository.save(document);

        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + UUID.randomUUID().toString().substring(0, 8));
        attachment.setName("accreditation-evidence.pdf");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("SUPPLIER_ACCREDITATION_DOCUMENT");
        attachment.setEntityId(document.getId());
        attachment.setAttachmentType("REGISTRATION_CERTIFICATE");
        attachment.setOriginalFileName("accreditation-evidence.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(128L);
        attachment.setChecksumSha256("a".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("tests/accreditation/" + UUID.randomUUID());
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setDownloadable(true);
        attachment = attachmentRepository.save(attachment);

        document.setAttachmentId(attachment.getId());
        supplierAccreditationDocumentRepository.save(document);
    }

    private Warehouse warehouse(String prefix) {
        String code = prefix + "-" + suffix();
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        warehouse.setAddress("Tashkent logistics hub");
        return warehouseRepository.save(warehouse);
    }

    private PurchaseOrder purchaseOrder(String prefix, UUID supplierId) {
        String code = prefix + "-" + suffix();
        PurchaseOrder po = new PurchaseOrder();
        po.setCode(code);
        po.setName(code);
        po.setStatus(PurchaseOrderStatus.SENT.name());
        po.setSupplierId(supplierId);
        po.setOrderDate(LocalDate.now());
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(15));
        po.setTotalAmount(BigDecimal.valueOf(1000));
        po.setCurrencyCode("USD");
        return purchaseOrderRepository.save(po);
    }

    private PurchaseOrderItem purchaseOrderItem(String prefix, UUID purchaseOrderId, UUID materialId) {
        String code = prefix + "-" + suffix();
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode(code);
        item.setName(code);
        item.setStatus("OPEN");
        item.setPurchaseOrderId(purchaseOrderId);
        item.setMaterialId(materialId);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.valueOf(100));
        return purchaseOrderItemRepository.save(item);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
