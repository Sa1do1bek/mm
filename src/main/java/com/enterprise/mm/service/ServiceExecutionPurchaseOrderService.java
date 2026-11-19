package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ServiceExecutionCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connects a sent/accepted service PO to exactly one execution record. */
@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {})
public class ServiceExecutionPurchaseOrderService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderSourceAllocationRepository sourceAllocationRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final ServiceOrderRepository serviceOrderRepository;
    private final ServiceExecutionRepository serviceExecutionRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final ServiceExecutionService serviceExecutionService;
    private final AuditService auditService;

    @Transactional
    public ServiceExecutionResponse createFromPurchaseOrder(UUID purchaseOrderId) {
        PurchaseOrder order = purchaseOrderRepository.findByTenantIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrderId)
            .filter(item -> Objects.equals(item.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        if (order.getRequestType() != RequestType.SERVICE) {
            throw new BusinessException("Service execution is only available for SERVICE purchase orders");
        }
        if (!List.of("APPROVED", "SENT", "PARTIALLY_COMPLETED", "COMPLETED").contains(order.getStatus())) {
            throw new BusinessException("Service execution requires an approved or sent purchase order");
        }
        if (hasAcceptedServiceAct(order.getId())) {
            throw new BusinessException("Service execution is already completed by an approved service acceptance act");
        }
        if (hasInvoiceOrPayment(order.getId())) {
            throw new BusinessException("Service execution cannot be created after invoice or payment has started");
        }
        List<PurchaseOrderItem> serviceItems = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(item -> item.getLineType() == ProcurementLineType.SERVICE)
            .toList();
        PurchaseOrderItem sourceItem = serviceItems.stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException("Service purchase order has no SERVICE line"));
        if (sourceItem.getServiceRequestId() == null) {
            throw new BusinessException("Service purchase order line is missing serviceRequestId lineage; repair purchase order service lineage before starting execution");
        }
        ServiceOrder serviceOrder = serviceOrderRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderId)
            .orElseGet(() -> createServiceOrder(order, sourceItem, serviceItems));
        List<ServiceExecution> existing = serviceExecutionRepository
            .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), serviceOrder.getId());
        if (!existing.isEmpty()) {
            return serviceExecutionService.get(existing.get(0).getId());
        }
        ServiceExecutionCreateRequest request = new ServiceExecutionCreateRequest();
        request.setName(sourceItem.getServiceDescription());
        request.setDescription(sourceItem.getServiceDescription());
        request.setServiceRequestId(sourceItem.getServiceRequestId());
        request.setServiceOrderId(serviceOrder.getId());
        request.setPlannedStartDate(order.getOrderDate());
        request.setPlannedEndDate(order.getExpectedDeliveryDate());
        return serviceExecutionService.create(request);
    }

    @Transactional
    public int repairPurchaseOrderLineage(UUID purchaseOrderId) {
        PurchaseOrder order = purchaseOrderRepository.findByTenantIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrderId)
            .filter(item -> Objects.equals(item.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        if (order.getRequestType() != RequestType.SERVICE) {
            throw new BusinessException("Service lineage repair is only available for SERVICE purchase orders");
        }
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .filter(item -> item.getLineType() == ProcurementLineType.SERVICE)
            .toList();
        if (items.isEmpty()) {
            return 0;
        }
        List<UUID> itemIds = items.stream().map(PurchaseOrderItem::getId).filter(Objects::nonNull).toList();
        Map<UUID, List<PurchaseOrderSourceAllocation>> allocationsByItem = sourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), itemIds)
            .stream()
            .collect(Collectors.groupingBy(PurchaseOrderSourceAllocation::getPurchaseOrderItemId));
        List<UUID> requestItemIds = allocationsByItem.values().stream()
            .flatMap(Collection::stream)
            .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<UUID, PurchaseRequestItem> requestItems = purchaseRequestItemRepository.findAllById(requestItemIds).stream()
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> Objects.equals(CompanyContext.currentCompany(), item.getCompanyId()))
            .filter(item -> !item.isDeleted())
            .collect(Collectors.toMap(PurchaseRequestItem::getId, item -> item, (first, second) -> first));
        int repaired = 0;
        for (PurchaseOrderItem item : items) {
            if (item.getServiceRequestId() != null) {
                continue;
            }
            UUID serviceRequestId = allocationsByItem.getOrDefault(item.getId(), List.of()).stream()
                .map(PurchaseOrderSourceAllocation::getPurchaseRequestItemId)
                .map(requestItems::get)
                .filter(Objects::nonNull)
                .map(PurchaseRequestItem::getServiceRequestId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
            if (serviceRequestId == null) {
                continue;
            }
            item.setServiceRequestId(serviceRequestId);
            purchaseOrderItemRepository.save(item);
            repaired++;
        }
        return repaired;
    }

    private boolean hasAcceptedServiceAct(UUID purchaseOrderId) {
        return !serviceAcceptanceActRepository.findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            TenantContext.currentTenant(),
            purchaseOrderId,
            List.of(
                ServiceAcceptanceActStatus.APPROVED.name(),
                ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(),
                ServiceAcceptanceActStatus.PAID.name()
            )
        ).isEmpty();
    }

    private boolean hasInvoiceOrPayment(UUID purchaseOrderId) {
        boolean hasInvoice = contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .anyMatch(invoice -> List.of(
                ContractInvoiceStatus.DRAFT.name(),
                ContractInvoiceStatus.ISSUED.name(),
                ContractInvoiceStatus.PARTIALLY_PAID.name(),
                ContractInvoiceStatus.PAID.name()
            ).contains(normalized(invoice.getStatus())));
        if (hasInvoice) {
            return true;
        }
        return contractPaymentRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), purchaseOrderId)
            .stream()
            .anyMatch(payment -> List.of(
                ContractPaymentStatus.DRAFT.name(),
                ContractPaymentStatus.SUBMITTED.name(),
                ContractPaymentStatus.SENT_TO_TREASURY.name(),
                ContractPaymentStatus.PAID.name()
            ).contains(normalized(payment.getStatus())));
    }

    private String normalized(String status) {
        return status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private ServiceOrder createServiceOrder(PurchaseOrder order, PurchaseOrderItem item, java.util.List<PurchaseOrderItem> serviceItems) {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setTenantId(order.getTenantId());
        serviceOrder.setCompanyId(order.getCompanyId());
        serviceOrder.setName(item.getServiceDescription() == null ? order.getName() : item.getServiceDescription());
        serviceOrder.setCode("SO-" + order.getCode());
        serviceOrder.setStatus("DRAFT");
        serviceOrder.setServiceRequestId(item.getServiceRequestId());
        serviceOrder.setSupplierId(order.getSupplierId());
        serviceOrder.setContractId(order.getContractId());
        serviceOrder.setPurchaseOrderId(order.getId());
        serviceOrder.setServiceType("SERVICE");

        serviceOrder.setTotalAmount(item.getUnitPrice() == null ? order.getTotalAmount() : item.getUnitPrice().multiply(item.getQuantity()));
        serviceOrder.setCurrencyCode(order.getCurrencyCode());
        ServiceOrder saved = serviceOrderRepository.save(serviceOrder);

        List<PurchaseOrderItem> itemsToUpdate = serviceItems.stream()
            .filter(i -> i.getServiceOrderId() == null)
            .peek(i -> i.setServiceOrderId(saved.getId()))
            .toList();
        if (!itemsToUpdate.isEmpty()) {
            purchaseOrderItemRepository.saveAll(itemsToUpdate);
            itemsToUpdate.forEach(i -> auditService.logInCurrentTransaction(
                "PurchaseOrderItem", i.getId(), "LINK_SERVICE_ORDER", saved.getId().toString(), null, i));
        }

        return saved;
    }
}
