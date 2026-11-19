package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.ProcurementFrameworkRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContractPurchaseOrderService {
    public static final String FRAMEWORK_CALLOFF_REQUIRED =
        "FRAME_AGREEMENT purchase orders must be created via framework call-off";

    private final ContractRepository contractRepository;
    private final ContractSpecificationRepository specificationRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final AuditService auditService;
    private final PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    private final ProcurementFrameworkRepository procurementFrameworkRepository;

    @Transactional
    public PurchaseOrderResponse createFromContract(UUID contractId) {
        Contract contract = findContract(contractId);
        if (!ContractStatus.ACTIVE.name().equals(contract.getStatus())) {
            throw new BusinessException("Contract must be ACTIVE before creating purchase order");
        }
        if (contract.getSupplierId() == null) {
            throw new BusinessException("Contract supplier is required before creating purchase order");
        }
        if (isFrameworkContract(contractId)) {
            throw new BusinessException(FRAMEWORK_CALLOFF_REQUIRED);
        }

        var existing = purchaseOrderRepository
            .findFirstByTenantIdAndCompanyIdAndContractIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                contractId
            );
        if (existing.isPresent()) {
            return purchaseOrderService.get(existing.get().getId());
        }

        purchaseOrderDemandCyclePolicy.requireApprovedCycleForContract(contractId);

        List<ContractSpecification> specifications = specificationRepository
            .findByTenantIdAndContractIdAndIsDeletedFalse(TenantContext.currentTenant(), contractId)
            .stream()
            .filter(specification -> Objects.equals(CompanyContext.currentCompany(), specification.getCompanyId()))
            .toList();
        if (specifications.isEmpty()) {
            throw new BusinessException("Contract has no specifications to create purchase order");
        }

        PurchaseOrderCreateRequest request = new PurchaseOrderCreateRequest();
        request.setName(BusinessNameGenerator.workflowDocumentName("PurchaseOrder", contract.getName()));
        request.setSupplierId(contract.getSupplierId());
        request.setContractId(contract.getId());
        request.setOrderDate(LocalDate.now());
        request.setExpectedDeliveryDate(LocalDate.now().plusDays(7));
        request.setRequestType(requestTypeFor(specifications).name());
        request.setCurrencyCode(contract.getCurrencyCode());
        request.setItems(specifications.stream().map(this::toPurchaseOrderItem).toList());

        PurchaseOrderResponse response = purchaseOrderService.create(request);
        auditService.log("Contract", contractId, "CREATE_PURCHASE_ORDER", response.getCode(), null, null);
        return response;
    }

    private boolean isFrameworkContract(UUID contractId) {
        return procurementFrameworkRepository
            .findByTenantIdAndCompanyIdAndContractIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                contractId
            )
            .isPresent();
    }

    private PurchaseOrderItemCreateRequest toPurchaseOrderItem(ContractSpecification specification) {
        ProcurementLineType lineType = specification.getLineType() == null
            ? ProcurementLineType.MATERIAL
            : specification.getLineType();
        if (lineType == ProcurementLineType.MATERIAL && specification.getMaterialId() == null) {
            throw new BusinessException("Contract material specification is missing material");
        }

        PurchaseOrderItemCreateRequest item = new PurchaseOrderItemCreateRequest();
        item.setName(Objects.toString(specification.getName(), "Contract specification"));
        item.setLineType(lineType.name());
        item.setMaterialId(specification.getMaterialId());
        item.setMaterialTypeSnapshot(specification.getMaterialTypeSnapshot());
        item.setServiceDescription(specification.getServiceDescription());
        item.setServiceCategory(specification.getServiceCategory());
        item.setUnit(specification.getUnit());
        item.setPeriod(specification.getPeriod());
        item.setStage(specification.getStage());
        item.setQuantity(nvl(specification.getQuantityLimit()));
        item.setUnitPrice(nvl(specification.getPrice()));
        return item;
    }

    private RequestType requestTypeFor(List<ContractSpecification> specifications) {
        boolean allServiceOrWork = specifications.stream()
            .allMatch(specification -> specification.getLineType() == ProcurementLineType.SERVICE || specification.getLineType() == ProcurementLineType.WORK);
        if (allServiceOrWork) {
            return RequestType.SERVICE;
        }
        return RequestType.GOODS;
    }


    private Contract findContract(UUID contractId) {
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(contractId)
            .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        if (!TenantContext.currentTenant().equals(contract.getTenantId())) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(contract.getCompanyId())) {
            throw new NotFoundException("Contract not found: " + contractId);
        }
        return contract;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
