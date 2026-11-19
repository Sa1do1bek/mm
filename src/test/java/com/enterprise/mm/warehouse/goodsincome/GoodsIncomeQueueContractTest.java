package com.enterprise.mm.warehouse.goodsincome;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.CompanyScope;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueFilter;
import com.enterprise.mm.warehouse.goodsincome.repository.GoodsIncomeQueryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class GoodsIncomeQueueContractTest {
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository itemRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private GoodsIncomeQueryRepository queryRepository;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("goods-income-queue-test");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void listAndCountUseIdenticalReceivingFilters() {
        Supplier supplier = supplierRepository.save(supplier("SUP-GI", "Bearing Supplier"));
        PurchaseOrder overdue = purchaseOrderRepository.save(po(
            supplier.getId(), "PO-GI-OVERDUE", "Bearing delivery overdue", PurchaseOrderStatus.SENT.name(), LocalDate.now().minusDays(2)
        ));
        PurchaseOrder future = purchaseOrderRepository.save(po(
            supplier.getId(), "PO-GI-FUTURE", "Bearing delivery future", PurchaseOrderStatus.PARTIALLY_RECEIVED.name(), LocalDate.now().plusDays(2)
        ));
        purchaseOrderRepository.save(po(
            supplier.getId(), "PO-GI-DRAFT", "Bearing draft", PurchaseOrderStatus.DRAFT.name(), LocalDate.now().minusDays(2)
        ));
        PurchaseOrder worksOrder = po(
            supplier.getId(),
            "PO-GI-WORKS",
            "Bearing installation works",
            PurchaseOrderStatus.SENT.name(),
            LocalDate.now().minusDays(2)
        );
        worksOrder.setRequestType(RequestType.SERVICE);
        purchaseOrderRepository.save(worksOrder);
        itemRepository.save(item(overdue.getId(), "PO-GI-OVERDUE-1", MaterialType.TMB, BigDecimal.TEN, BigDecimal.ZERO, companyId));
        itemRepository.save(item(future.getId(), "PO-GI-FUTURE-1", MaterialType.TMB, BigDecimal.TEN, BigDecimal.ZERO, companyId));
        purchaseOrderRepository.flush();
        itemRepository.flush();

        GoodsIncomeQueueFilter filter = new GoodsIncomeQueueFilter(
            "bearing",
            List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name()),
            supplier.getId(),
            true
        );

        var page = queryRepository.search(filter, PageRequest.of(0, 25));
        long count = queryRepository.count(filter);

        assertThat(page.getTotalElements()).isEqualTo(count).isEqualTo(1);
        assertThat(page.getContent()).extracting(PurchaseOrder::getCode).containsExactly("PO-GI-OVERDUE");
    }

    @Test
    void globalAdminCurrentCompanyDrilldownKeepsReceivingCountAndListScoped() {
        UUID otherCompanyId = UUID.randomUUID();
        Supplier currentSupplier = supplierRepository.save(supplier("SUP-GI-SCOPE", "Current supplier", companyId));
        Supplier otherSupplier = supplierRepository.save(supplier("SUP-GI-SCOPE-OTHER", "Other supplier", otherCompanyId));
        PurchaseOrder currentOrder = purchaseOrderRepository.save(po(
            currentSupplier.getId(), "PO-GI-SCOPE-CURRENT", "Scoped delivery",
            PurchaseOrderStatus.SENT.name(), LocalDate.now().minusDays(2), companyId
        ));
        PurchaseOrder otherOrder = purchaseOrderRepository.save(po(
            otherSupplier.getId(), "PO-GI-SCOPE-OTHER", "Scoped delivery",
            PurchaseOrderStatus.SENT.name(), LocalDate.now().minusDays(2), otherCompanyId
        ));
        itemRepository.save(item(currentOrder.getId(), "PO-GI-SCOPE-CURRENT-1", MaterialType.TMB, BigDecimal.TEN, BigDecimal.ZERO, companyId));
        itemRepository.save(item(otherOrder.getId(), "PO-GI-SCOPE-OTHER-1", MaterialType.TMB, BigDecimal.TEN, BigDecimal.ZERO, otherCompanyId));
        purchaseOrderRepository.flush();
        itemRepository.flush();
        authenticateGlobalAdmin();
        GoodsIncomeQueueFilter filter = new GoodsIncomeQueueFilter(
            "scoped delivery", List.of(PurchaseOrderStatus.SENT.name()), null, true);

        assertThat(queryRepository.search(filter, PageRequest.of(0, 25)).getTotalElements()).isEqualTo(2);

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);
        var scoped = queryRepository.search(filter, PageRequest.of(0, 25));

        assertThat(queryRepository.count(filter)).isEqualTo(scoped.getTotalElements()).isEqualTo(1);
        assertThat(scoped.getContent()).extracting(PurchaseOrder::getCode)
            .containsExactly("PO-GI-SCOPE-CURRENT");
    }

    @Test
    void goodsQueueIncludesOnlyOpenTmbPurchaseOrdersEvenWhenFixedAssetOrderUsesGoodsEnvelope() {
        Supplier supplier = supplierRepository.save(supplier("SUP-GI-TMB", "TMB and asset supplier"));
        PurchaseOrder tmbOrder = purchaseOrderRepository.save(po(
            supplier.getId(), "PO-GI-TMB", "TMB delivery", PurchaseOrderStatus.SENT.name(), LocalDate.now().plusDays(1)
        ));
        PurchaseOrder fixedAssetOrder = purchaseOrderRepository.save(po(
            supplier.getId(), "PO-GI-ASSET", "Fixed asset delivery", PurchaseOrderStatus.SENT.name(), LocalDate.now().plusDays(1)
        ));
        itemRepository.save(item(tmbOrder.getId(), "PO-GI-TMB-1", MaterialType.TMB, BigDecimal.TEN, BigDecimal.ZERO, companyId));
        itemRepository.save(item(fixedAssetOrder.getId(), "PO-GI-ASSET-1", MaterialType.FIXED_ASSET, BigDecimal.TEN, BigDecimal.ZERO, companyId));
        purchaseOrderRepository.flush();
        itemRepository.flush();

        GoodsIncomeQueueFilter filter = new GoodsIncomeQueueFilter(
            "delivery",
            List.of(PurchaseOrderStatus.SENT.name()),
            supplier.getId(),
            null
        );

        var page = queryRepository.search(filter, PageRequest.of(0, 25));

        assertThat(queryRepository.count(filter)).isEqualTo(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(PurchaseOrder::getCode).containsExactly("PO-GI-TMB");
    }

    private Supplier supplier(String code, String name) {
        return supplier(code, name, companyId);
    }

    private Supplier supplier(String code, String name, UUID supplierCompanyId) {
        Supplier supplier = new Supplier();
        supplier.setTenantId(TenantContext.currentTenant());
        supplier.setCompanyId(supplierCompanyId);
        supplier.setCode(code);
        supplier.setName(name);
        supplier.setLegalName(name);
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private PurchaseOrder po(UUID supplierId, String code, String name, String status, LocalDate expectedDeliveryDate) {
        return po(supplierId, code, name, status, expectedDeliveryDate, companyId);
    }

    private PurchaseOrder po(
        UUID supplierId,
        String code,
        String name,
        String status,
        LocalDate expectedDeliveryDate,
        UUID purchaseOrderCompanyId
    ) {
        PurchaseOrder po = new PurchaseOrder();
        po.setTenantId(TenantContext.currentTenant());
        po.setCompanyId(purchaseOrderCompanyId);
        po.setSupplierId(supplierId);
        po.setCode(code);
        po.setName(name);
        po.setStatus(status);
        po.setRequestType(RequestType.GOODS);
        po.setExpectedDeliveryDate(expectedDeliveryDate);
        po.setCurrencyCode("UZS");
        po.setTotalAmount(BigDecimal.valueOf(1_000));
        return po;
    }

    private PurchaseOrderItem item(
        UUID purchaseOrderId,
        String code,
        MaterialType materialType,
        BigDecimal quantity,
        BigDecimal receivedQuantity,
        UUID itemCompanyId
    ) {
        Material material = materialRepository.saveAndFlush(material("MAT-" + code, materialType, itemCompanyId));
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setTenantId(TenantContext.currentTenant());
        item.setCompanyId(itemCompanyId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode(code);
        item.setName(code);
        item.setStatus("OPEN");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(material.getId());
        item.setMaterialTypeSnapshot(materialType);
        item.setQuantity(quantity);
        item.setReceivedQuantity(receivedQuantity);
        item.setAcceptedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setUnit("PCS");
        return item;
    }

    private Material material(String code, MaterialType materialType, UUID materialCompanyId) {
        Material material = new Material();
        material.setTenantId(TenantContext.currentTenant());
        material.setCompanyId(materialCompanyId);
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setMaterialType(materialType);
        return material;
    }

    private void authenticateGlobalAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
