package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ConfirmProcessorConsumptionCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.DispatchToProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ExecuteTransformationCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ReturnFromProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineItem;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineType;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationOrderStatus;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationResult;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingTransformationService {

    private final WarehouseTransformationOrderRepository orderRepository;
    private final AccountAwareStockPostingService postingService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProcessingTransformationService(
        WarehouseTransformationOrderRepository orderRepository,
        AccountAwareStockPostingService postingService
    ) {
        this.orderRepository = orderRepository;
        this.postingService = postingService;
    }

    @Transactional
    public TransformationResult dispatchToProcessor(DispatchToProcessorCommand command) {
        if (command == null || command.orderNumber() == null || command.orderNumber().isBlank()) {
            throw new BadRequestException("Order number is required");
        }
        if (command.warehouseId() == null) {
            throw new BadRequestException("Warehouse ID is required");
        }
        if (command.processorId() == null) {
            throw new BadRequestException("Processor ID is required");
        }
        if (command.materialId() == null) {
            throw new BadRequestException("Material ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Dispatch quantity must be strictly positive");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        var existing = orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(
            tenantId, companyId.toString(), command.orderNumber()
        );
        if (existing.isPresent()) {
            throw new ConflictException("Transformation order number already exists: " + command.orderNumber());
        }

        LocalDate date = command.dispatchDate() != null ? command.dispatchDate() : LocalDate.now();
        BigDecimal unitCost = command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO;
        BigDecimal totalCost = unitCost.multiply(command.quantity()).setScale(4, RoundingMode.HALF_UP);

        // Core rule: Do NOT equate dispatch to processor with expense consumption.
        // Post issue to PROCESSOR_TRANSIT holding disposition rather than final consumption.
        var postingCmd = new PostingCommand(
            "TRANSFORMATION", "TRANSFER", "PROCESSOR_DISPATCH", UUID.randomUUID(),
            command.orderNumber(), date, 1L, mapper.createObjectNode().put("qty", command.quantity().toPlainString())
        );

        var issueReq = new AccountAwareStockPostingService.IssueRequest(
            postingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null, null,
            "OWNED",
            "PROCESSOR_TRANSIT",
            command.accountCode() != null ? command.accountCode() : "20",
            "PCS",
            command.quantity(),
            unitCost,
            "UZS",
            command.sourceReference() != null ? command.sourceReference() : "DISPATCH_TO_PROCESSOR"
        );
        postingService.postIssue(issueReq);

        var order = new WarehouseTransformationOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId(tenantId);
        order.setCompanyId(companyId.toString());
        order.setOrderNumber(command.orderNumber());
        order.setTransformationType(TransformationType.PROCESSOR_TOLLING);
        order.setStatus(TransformationOrderStatus.DISPATCHED_TO_PROCESSOR);
        order.setWarehouseId(command.warehouseId());
        order.setProcessorId(command.processorId());
        order.setTotalInputCost(totalCost);
        order.setTotalOutputCost(BigDecimal.ZERO);
        order.setExecutionDate(date);
        order.setSourceReference(command.sourceReference());
        order.setCreatedAt(Instant.now());

        var line = new WarehouseTransformationLine();
        line.setId(UUID.randomUUID());
        line.setLineType(TransformationLineType.INPUT);
        line.setMaterialId(command.materialId());
        line.setAccountCode(command.accountCode() != null ? command.accountCode() : "20");
        line.setQuantity(command.quantity());
        line.setUnitCost(unitCost);
        line.setAllocatedCost(totalCost);
        line.setCreatedAt(Instant.now());
        order.addLine(line);

        var saved = orderRepository.save(order);

        return new TransformationResult(
            saved.getId(),
            saved.getOrderNumber(),
            saved.getTransformationType(),
            saved.getStatus(),
            saved.getTotalInputCost(),
            saved.getTotalOutputCost()
        );
    }

    @Transactional
    public TransformationResult confirmProcessorConsumption(ConfirmProcessorConsumptionCommand command) {
        if (command == null || command.orderNumber() == null) {
            throw new BadRequestException("Order number is required");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        var order = orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(
            tenantId, companyId.toString(), command.orderNumber()
        ).orElseThrow(() -> new NotFoundException("Transformation order not found: " + command.orderNumber()));

        if (order.getStatus() != TransformationOrderStatus.DISPATCHED_TO_PROCESSOR
            && order.getStatus() != TransformationOrderStatus.PARTIALLY_RETURNED) {
            throw new BadRequestException("Order status must be DISPATCHED_TO_PROCESSOR or PARTIALLY_RETURNED to confirm consumption. Current: " + order.getStatus());
        }

        LocalDate date = command.confirmationDate() != null ? command.confirmationDate() : LocalDate.now();

        BigDecimal totalOutputCost = BigDecimal.ZERO;
        if (command.outputs() != null) {
            for (TransformationLineItem item : command.outputs()) {
                BigDecimal cost = item.allocatedCost() != null
                    ? item.allocatedCost()
                    : (item.unitCost() != null ? item.unitCost().multiply(item.quantity()) : BigDecimal.ZERO);
                totalOutputCost = totalOutputCost.add(cost);

                // Post output / processed goods receipt
                var postingCmd = new PostingCommand(
                    "TRANSFORMATION", "RECEIPT", "PROCESSOR_RETURN", UUID.randomUUID(),
                    command.orderNumber() + "-OUT", date, 1L,
                    mapper.createObjectNode().put("qty", item.quantity().toPlainString())
                );
                var receiptReq = new AccountAwareStockPostingService.ReceiptRequest(
                    postingCmd,
                    item.materialId(),
                    order.getWarehouseId(),
                    null, null, null, null,
                    "OWNED",
                    "AVAILABLE",
                    item.accountCode() != null ? item.accountCode() : "2810",
                    "PCS",
                    item.quantity(),
                    item.unitCost() != null ? item.unitCost() : BigDecimal.ZERO,
                    "UZS",
                    "PROCESSOR_OUTPUT:" + command.orderNumber()
                );
                postingService.postReceipt(receiptReq);

                var line = new WarehouseTransformationLine();
                line.setId(UUID.randomUUID());
                line.setLineType(item.lineType());
                line.setMaterialId(item.materialId());
                line.setAccountCode(item.accountCode());
                line.setQuantity(item.quantity());
                line.setUnitCost(item.unitCost());
                line.setAllocatedCost(cost);
                line.setCreatedAt(Instant.now());
                order.addLine(line);
            }
        }

        order.setTotalOutputCost(totalOutputCost);
        order.setStatus(TransformationOrderStatus.EXECUTED);
        var saved = orderRepository.save(order);

        return new TransformationResult(
            saved.getId(),
            saved.getOrderNumber(),
            saved.getTransformationType(),
            saved.getStatus(),
            saved.getTotalInputCost(),
            saved.getTotalOutputCost()
        );
    }

    @Transactional
    public TransformationResult returnFromProcessor(ReturnFromProcessorCommand command) {
        if (command == null || command.orderNumber() == null) {
            throw new BadRequestException("Order number is required");
        }
        if (command.returnedQuantity() == null || command.returnedQuantity().signum() <= 0) {
            throw new BadRequestException("Returned quantity must be strictly positive");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        var order = orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(
            tenantId, companyId.toString(), command.orderNumber()
        ).orElseThrow(() -> new NotFoundException("Transformation order not found: " + command.orderNumber()));

        if (order.getStatus() != TransformationOrderStatus.DISPATCHED_TO_PROCESSOR
            && order.getStatus() != TransformationOrderStatus.PARTIALLY_RETURNED) {
            throw new BadRequestException("Order must be DISPATCHED_TO_PROCESSOR or PARTIALLY_RETURNED to return materials. Current: " + order.getStatus());
        }

        LocalDate date = command.returnDate() != null ? command.returnDate() : LocalDate.now();
        BigDecimal unitCost = command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO;
        BigDecimal returnedCost = unitCost.multiply(command.returnedQuantity()).setScale(4, RoundingMode.HALF_UP);

        // Post receipt of returned material back into warehouse stock as AVAILABLE
        var postingCmd = new PostingCommand(
            "TRANSFORMATION", "RECEIPT", "PROCESSOR_PARTIAL_RETURN", UUID.randomUUID(),
            command.orderNumber() + "-RET-" + System.currentTimeMillis(), date, 1L,
            mapper.createObjectNode().put("qty", command.returnedQuantity().toPlainString())
        );
        var receiptReq = new AccountAwareStockPostingService.ReceiptRequest(
            postingCmd,
            command.materialId(),
            command.warehouseId() != null ? command.warehouseId() : order.getWarehouseId(),
            null, null, null, null,
            "OWNED",
            "AVAILABLE",
            "1010",
            "PCS",
            command.returnedQuantity(),
            unitCost,
            "UZS",
            "RETURN_FROM_PROCESSOR:" + command.orderNumber()
        );
        postingService.postReceipt(receiptReq);

        var line = new WarehouseTransformationLine();
        line.setId(UUID.randomUUID());
        line.setLineType(TransformationLineType.RETURNED);
        line.setMaterialId(command.materialId());
        line.setAccountCode("1010");
        line.setQuantity(command.returnedQuantity());
        line.setUnitCost(unitCost);
        line.setAllocatedCost(returnedCost);
        line.setCreatedAt(Instant.now());
        order.addLine(line);

        BigDecimal totalDispatchedQty = order.getLines().stream()
            .filter(l -> l.getLineType() == TransformationLineType.INPUT)
            .map(WarehouseTransformationLine::getQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReturnedQty = order.getLines().stream()
            .filter(l -> l.getLineType() == TransformationLineType.RETURNED)
            .map(WarehouseTransformationLine::getQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalReturnedQty.compareTo(totalDispatchedQty) >= 0) {
            order.setStatus(TransformationOrderStatus.EXECUTED);
        } else {
            order.setStatus(TransformationOrderStatus.PARTIALLY_RETURNED);
        }

        var saved = orderRepository.save(order);
        return new TransformationResult(
            saved.getId(),
            saved.getOrderNumber(),
            saved.getTransformationType(),
            saved.getStatus(),
            saved.getTotalInputCost(),
            saved.getTotalOutputCost()
        );
    }

    @Transactional
    public TransformationResult executeTransformation(ExecuteTransformationCommand command) {
        if (command == null || command.orderNumber() == null) {
            throw new BadRequestException("Order number is required");
        }
        if (command.transformationType() == null) {
            throw new BadRequestException("Transformation type is required");
        }
        if (command.warehouseId() == null) {
            throw new BadRequestException("Warehouse ID is required");
        }
        if (command.inputs() == null || command.inputs().isEmpty()) {
            throw new BadRequestException("Transformation inputs cannot be empty");
        }
        if (command.outputs() == null || command.outputs().isEmpty()) {
            throw new BadRequestException("Transformation outputs cannot be empty");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        var existing = orderRepository.findByTenantIdAndCompanyIdAndOrderNumber(
            tenantId, companyId.toString(), command.orderNumber()
        );
        if (existing.isPresent()) {
            throw new ConflictException("Transformation order number already exists: " + command.orderNumber());
        }

        // 1. Calculate and verify value conservation
        BigDecimal totalInputCost = BigDecimal.ZERO;
        for (TransformationLineItem in : command.inputs()) {
            BigDecimal lineCost = in.allocatedCost() != null && in.allocatedCost().signum() > 0
                ? in.allocatedCost()
                : (in.unitCost() != null ? in.unitCost().multiply(in.quantity()) : BigDecimal.ZERO);
            totalInputCost = totalInputCost.add(lineCost);
        }

        BigDecimal totalOutputCost = BigDecimal.ZERO;
        for (TransformationLineItem out : command.outputs()) {
            BigDecimal lineCost = out.allocatedCost() != null && out.allocatedCost().signum() > 0
                ? out.allocatedCost()
                : (out.unitCost() != null ? out.unitCost().multiply(out.quantity()) : BigDecimal.ZERO);
            totalOutputCost = totalOutputCost.add(lineCost);
        }

        if (totalInputCost.subtract(totalOutputCost).abs().compareTo(new BigDecimal("0.005")) > 0) {
            throw new BadRequestException(
                "Transformation value discrepancy: Total input cost (" + totalInputCost
                + ") does not equal total output cost (" + totalOutputCost + "). Approved value allocation required."
            );
        }

        LocalDate date = command.executionDate() != null ? command.executionDate() : LocalDate.now();

        // 2. Consume inputs
        for (TransformationLineItem in : command.inputs()) {
            var postingCmd = new PostingCommand(
                "TRANSFORMATION", "ISSUE", command.transformationType().name() + "_INPUT", UUID.randomUUID(),
                command.orderNumber() + "-IN", date, 1L,
                mapper.createObjectNode().put("qty", in.quantity().toPlainString())
            );
            var issueReq = new AccountAwareStockPostingService.IssueRequest(
                postingCmd,
                in.materialId(),
                command.warehouseId(),
                null, null, null, null,
                "OWNED",
                "AVAILABLE",
                in.accountCode() != null ? in.accountCode() : "1010",
                "PCS",
                in.quantity(),
                in.unitCost() != null ? in.unitCost() : BigDecimal.ZERO,
                "UZS",
                "TRANSFORMATION_INPUT:" + command.orderNumber()
            );
            postingService.postIssue(issueReq);
        }

        // 3. Post output receipts (Finished product, parts, scrap)
        for (TransformationLineItem out : command.outputs()) {
            var postingCmd = new PostingCommand(
                "TRANSFORMATION", "RECEIPT", command.transformationType().name() + "_OUTPUT", UUID.randomUUID(),
                command.orderNumber() + "-OUT", date, 1L,
                mapper.createObjectNode().put("qty", out.quantity().toPlainString())
            );
            var receiptReq = new AccountAwareStockPostingService.ReceiptRequest(
                postingCmd,
                out.materialId(),
                command.warehouseId(),
                null, null, null, null,
                "OWNED",
                "AVAILABLE",
                out.accountCode() != null ? out.accountCode() : "2810",
                "PCS",
                out.quantity(),
                out.unitCost() != null ? out.unitCost() : BigDecimal.ZERO,
                "UZS",
                "TRANSFORMATION_OUTPUT:" + command.orderNumber()
            );
            postingService.postReceipt(receiptReq);
        }

        // 4. Save transformation order and lines
        var order = new WarehouseTransformationOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId(tenantId);
        order.setCompanyId(companyId.toString());
        order.setOrderNumber(command.orderNumber());
        order.setTransformationType(command.transformationType());
        order.setStatus(TransformationOrderStatus.EXECUTED);
        order.setWarehouseId(command.warehouseId());
        order.setProcessorId(command.processorId());
        order.setTotalInputCost(totalInputCost);
        order.setTotalOutputCost(totalOutputCost);
        order.setExecutionDate(date);
        order.setSourceReference(command.sourceReference());
        order.setCreatedAt(Instant.now());

        for (TransformationLineItem in : command.inputs()) {
            var line = new WarehouseTransformationLine();
            line.setId(UUID.randomUUID());
            line.setLineType(in.lineType());
            line.setMaterialId(in.materialId());
            line.setAccountCode(in.accountCode());
            line.setQuantity(in.quantity());
            line.setUnitCost(in.unitCost() != null ? in.unitCost() : BigDecimal.ZERO);
            line.setAllocatedCost(in.allocatedCost() != null ? in.allocatedCost() : BigDecimal.ZERO);
            line.setCreatedAt(Instant.now());
            order.addLine(line);
        }

        for (TransformationLineItem out : command.outputs()) {
            var line = new WarehouseTransformationLine();
            line.setId(UUID.randomUUID());
            line.setLineType(out.lineType());
            line.setMaterialId(out.materialId());
            line.setAccountCode(out.accountCode());
            line.setQuantity(out.quantity());
            line.setUnitCost(out.unitCost() != null ? out.unitCost() : BigDecimal.ZERO);
            line.setAllocatedCost(out.allocatedCost() != null ? out.allocatedCost() : BigDecimal.ZERO);
            line.setCreatedAt(Instant.now());
            order.addLine(line);
        }

        var saved = orderRepository.save(order);

        return new TransformationResult(
            saved.getId(),
            saved.getOrderNumber(),
            saved.getTransformationType(),
            saved.getStatus(),
            saved.getTotalInputCost(),
            saved.getTotalOutputCost()
        );
    }
}
