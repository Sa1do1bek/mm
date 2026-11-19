package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.AuctionAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkCallOffRequest;
import com.enterprise.mm.payload.ProcurementProcedureResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.service.ProcurementAwardFinalizationOrchestrator.AwardFinalizationCommand;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementProcedureExecutionService {
    private final ProcurementProcedureRepository repository;
    private final ProcurementProcedureService procedureService;
    private final ProcurementMethodPolicy methodPolicy;
    private final ProcurementFrameworkService frameworkService;
    private final ProcurementAwardPurchaseOrderService awardPurchaseOrderService;
    private final ProcurementAwardFinalizationOrchestrator awardFinalizationOrchestrator;

    @Transactional
    public PurchaseOrderResponse awardAuction(UUID id, AuctionAwardRequest request) {
        ProcurementProcedure procedure = require(id, ProcurementExecutionPath.AUCTION);
        if (!ProcurementProcedureStatus.AUCTION_CLOSED.name().equals(procedure.getStatus())) {
            throw new BusinessException("Auction must be closed before award");
        }
        PurchaseOrderResponse purchaseOrder = awardPurchaseOrderService.create(
            procedure,
            request == null ? null : request.supplierId(),
            request == null ? null : request.awardAmount(),
            request == null ? null : request.currencyCode(),
            null,
            request == null ? null : request.orderDate(),
            request == null ? null : request.expectedDeliveryDate(),
            request == null ? null : request.justification()
        );
        awardFinalizationOrchestrator.finalizeAward(new AwardFinalizationCommand(
            procedure.getId(),
            ProcurementAwardSourceType.MANAGED_AUCTION,
            procedure.getId(),
            request == null ? null : request.supplierId(),
            purchaseOrder.getId(),
            null,
            request == null ? null : request.awardAmount(),
            request == null ? null : request.currencyCode(),
            request == null ? null : request.awardReference(),
            ProcurementProcedureStatus.COMPLETED,
            "PROCUREMENT.AUCTION_AWARDED",
            "AUCTION_AWARD"
        ));
        return purchaseOrder;
    }

    @Transactional
    public ProcurementProcedureResponse awardFramework(UUID id, FrameworkAwardRequest request) {
        frameworkService.createFromProcedure(id, request);
        return procedureService.get(id);
    }

    @Transactional
    public ProcurementProcedureResponse activateFramework(UUID id) {
        var framework = frameworkService.getByProcedure(id);
        frameworkService.activate(framework.id());
        return procedureService.get(id);
    }

    @Transactional
    public PurchaseOrderResponse createFrameworkCallOff(UUID id, FrameworkCallOffRequest request) {
        return frameworkService.callOffFromProcedure(id, request);
    }

    private ProcurementProcedure require(UUID id, ProcurementExecutionPath path) {
        ProcurementProcedure procedure = repository.lockScopedById(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id
        ).orElseThrow(() -> new NotFoundException("ProcurementProcedure not found: " + id));
        if (methodPolicy.require(procedure.getMethod()).executionPath() != path) {
            throw new BusinessException("Procurement procedure does not use " + path.name() + " execution");
        }
        return procedure;
    }

}
