package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ProcurementPlan;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ProcurementPlanExecutionResponse;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.ExchangeRateRepository;
import com.enterprise.mm.repository.ProcurementPlanRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

@Service
public class ProcurementPlanExecutionService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final ProcurementPlanRepository planRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final PurchaseRequestRepository requestRepository;
    private final PurchaseOrderRepository orderRepository;
    private final PurchaseOrderItemRepository itemRepository;
    private final ContractRepository contractRepository;
    private final RFQRepository rfqRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ProcurementCampaignRepository campaignRepository;
    private final ProcurementProcedureRepository procedureRepository;

    public ProcurementPlanExecutionService(
        ProcurementPlanRepository planRepository,
        DemandPlanRepository demandPlanRepository,
        PurchaseRequestRepository requestRepository,
        PurchaseOrderRepository orderRepository,
        PurchaseOrderItemRepository itemRepository,
        ContractRepository contractRepository,
        RFQRepository rfqRepository,
        CurrencyRepository currencyRepository,
        ExchangeRateRepository exchangeRateRepository,
        ProcurementCampaignRepository campaignRepository,
        ProcurementProcedureRepository procedureRepository
    ) {
        this.planRepository = planRepository;
        this.demandPlanRepository = demandPlanRepository;
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.contractRepository = contractRepository;
        this.rfqRepository = rfqRepository;
        this.currencyRepository = currencyRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.campaignRepository = campaignRepository;
        this.procedureRepository = procedureRepository;
    }

    @Transactional(readOnly = true)
    public ProcurementPlanExecutionResponse get(UUID planId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        ProcurementPlan plan = planRepository.findByIdAndIsDeletedFalse(planId)
            .filter(p -> tenantId.equals(p.getTenantId()) && companyId.equals(p.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ProcurementPlan not found: " + planId));

        Set<UUID> demandPlanIds = plan.getDemandPlanningCycleId() == null ? Set.of() :
            demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(tenantId, plan.getDemandPlanningCycleId())
                .stream().filter(p -> companyId.equals(p.getCompanyId())).map(DemandPlan::getId).collect(Collectors.toSet());
        List<PurchaseRequest> requests = demandPlanIds.isEmpty() ? List.of() : requestRepository
                .findByTenantIdAndCompanyIdAndDemandPlanIdInAndIsDeletedFalse(tenantId, companyId, demandPlanIds);
        Set<UUID> requestIds = requests.stream().map(PurchaseRequest::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> directOrderIds = new LinkedHashSet<>();
        Set<UUID> linkedContractIds = new LinkedHashSet<>();
        Set<UUID> linkedRfqIds = new LinkedHashSet<>();
        campaignRepository.findByTenantIdAndCompanyIdAndProcurementPlanIdAndIsDeletedFalse(tenantId, companyId, planId).forEach(c -> {
            if (c.getPurchaseRequestId() != null) requestIds.add(c.getPurchaseRequestId());
            if (c.getRfqId() != null) linkedRfqIds.add(c.getRfqId());
        });
        procedureRepository.findByTenantIdAndCompanyIdAndProcurementPlanIdAndIsDeletedFalse(tenantId, companyId, planId).forEach(p -> {
            if (p.getPurchaseRequestId() != null) requestIds.add(p.getPurchaseRequestId());
            if (p.getPurchaseOrderId() != null) directOrderIds.add(p.getPurchaseOrderId());
            if (p.getContractId() != null) linkedContractIds.add(p.getContractId());
            if (p.getRfqId() != null) linkedRfqIds.add(p.getRfqId());
        });
        List<PurchaseOrder> orders = resolveOrders(tenantId, companyId, requestIds, directOrderIds, linkedContractIds, linkedRfqIds);
        Map<UUID, PurchaseOrder> ordersById = orders.stream().collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        if (ordersById.isEmpty()) return response(plan, List.of(), plan.getDemandPlanningCycleId() == null && requestIds.isEmpty() && directOrderIds.isEmpty() && linkedContractIds.isEmpty() && linkedRfqIds.isEmpty()
            ? List.of("Нет связей плана с закупочными кампаниями или процедурами; заказы не сопоставлены") : List.of());

        List<PurchaseOrderItem> items = itemRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdInAndIsDeletedFalse(
            tenantId, companyId, ordersById.keySet());
        List<ProcurementPlanExecutionResponse.OrderLine> lines = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (PurchaseOrderItem item : items) {
            PurchaseOrder order = ordersById.get(item.getPurchaseOrderId());
            if (order == null || item.getLineType() != ProcurementLineType.MATERIAL || !inPlanPeriod(plan, order) || !matchesPlan(plan, item)) continue;
            Material material = item.getMaterial();
            Optional<BigDecimal> conversion = currencyFactor(plan, order, tenantId, companyId);
            if (conversion.isEmpty()) {
                warnings.add("Не найден курс для пересчёта заказа " + order.getCode() + " в валюту плана " + plan.getCurrencyCode());
                continue;
            }
            BigDecimal factor = conversion.get();
            BigDecimal orderedQty = nvl(item.getQuantity()).max(ZERO);
            BigDecimal acceptedQty = nvl(item.getAcceptedQuantity()).max(ZERO).min(orderedQty);
            BigDecimal price = nvl(item.getUnitPrice());
            lines.add(new ProcurementPlanExecutionResponse.OrderLine(
                order.getId(), order.getCode(), order.getStatus(), order.getOrderDate(), item.getId(), item.getMaterialId(),
                material == null ? null : material.getCode(), material == null ? item.getName() : material.getName(),
                orderedQty, acceptedQty, price,
                money(orderedQty.multiply(price).multiply(factor)), money(acceptedQty.multiply(price).multiply(factor)), plan.getCurrencyCode()
            ));
        }
        return response(plan, lines, List.copyOf(warnings));
    }

    private List<PurchaseOrder> resolveOrders(String tenantId, UUID companyId, Set<UUID> requestIds, Set<UUID> directOrderIds, Set<UUID> linkedContractIds, Set<UUID> linkedRfqIds) {
        if (requestIds.isEmpty() && directOrderIds.isEmpty() && linkedContractIds.isEmpty() && linkedRfqIds.isEmpty()) return List.of();
        List<PurchaseOrder> candidates = orderRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId);
        List<UUID> contractIds = candidates.stream().map(PurchaseOrder::getContractId).filter(id -> id != null).distinct().toList();
        Map<UUID, Contract> contracts = contractIds.isEmpty() ? Map.of() : contractRepository
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(tenantId, companyId, contractIds).stream()
            .collect(Collectors.toMap(Contract::getId, Function.identity()));
        List<UUID> rfqIds = contracts.values().stream().map(Contract::getSourceRfqId).filter(id -> id != null).distinct().toList();
        Map<UUID, RFQ> rfqs = rfqIds.isEmpty() ? Map.of() : rfqRepository
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(tenantId, companyId, rfqIds).stream()
            .collect(Collectors.toMap(RFQ::getId, Function.identity()));
        return candidates.stream().filter(order -> {
            UUID requestId = order.getPurchaseRequestId();
            if (directOrderIds.contains(order.getId()) || order.getContractId() != null && linkedContractIds.contains(order.getContractId())) return true;
            if (requestId != null && requestIds.contains(requestId)) return true;
            Contract contract = order.getContractId() == null ? null : contracts.get(order.getContractId());
            RFQ rfq = contract == null || contract.getSourceRfqId() == null ? null : rfqs.get(contract.getSourceRfqId());
            return rfq != null && (linkedRfqIds.contains(rfq.getId()) || rfq.getPurchaseRequestId() != null && requestIds.contains(rfq.getPurchaseRequestId()));
        }).toList();
    }

    private ProcurementPlanExecutionResponse response(ProcurementPlan plan, List<ProcurementPlanExecutionResponse.OrderLine> lines, List<String> warnings) {
        BigDecimal ordered = lines.stream().map(ProcurementPlanExecutionResponse.OrderLine::orderedAmount).reduce(ZERO, BigDecimal::add);
        BigDecimal accepted = lines.stream().map(ProcurementPlanExecutionResponse.OrderLine::acceptedAmount).reduce(ZERO, BigDecimal::add);
        BigDecimal planned = nvl(plan.getPlannedSpendAmount());
        Set<UUID> orderIds = lines.stream().map(ProcurementPlanExecutionResponse.OrderLine::orderId).collect(Collectors.toSet());
        BigDecimal remaining = nvl(plan.getApprovedBudgetAmount()).subtract(ordered);
        BigDecimal percent = nvl(plan.getApprovedBudgetAmount()).signum() == 0 ? ZERO :
            accepted.multiply(BigDecimal.valueOf(100)).divide(plan.getApprovedBudgetAmount(), 2, RoundingMode.HALF_UP);
        return new ProcurementPlanExecutionResponse(plan.getId(), plan.getPlanNumber(), plan.getStatus(), plan.getPlanYear(),
            plan.getCurrencyCode(), money(planned), money(nvl(plan.getApprovedBudgetAmount())), money(ordered), money(accepted),
            money(ordered.subtract(accepted)), money(remaining), percent, orderIds.size(), lines.size(), warnings, List.copyOf(lines));
    }

    private boolean matchesPlan(ProcurementPlan plan, PurchaseOrderItem item) {
        if (plan.getBudgetItemId() != null && !plan.getBudgetItemId().equals(item.getBudgetItemId())) return false;
        if (plan.getMaterialGroupId() != null) {
            Material material = item.getMaterial();
            return material != null && plan.getMaterialGroupId().equals(material.getMaterialGroupId());
        }
        return true;
    }

    private boolean inPlanPeriod(ProcurementPlan plan, PurchaseOrder order) {
        LocalDate date = order.getOrderDate() != null ? order.getOrderDate() :
            order.getCreatedAt() == null ? null : order.getCreatedAt().toLocalDate();
        if (date == null) return true;
        LocalDate start = plan.getPlanStartDate() != null ? plan.getPlanStartDate() : LocalDate.of(plan.getPlanYear(), 1, 1);
        LocalDate end = plan.getPlanEndDate() != null ? plan.getPlanEndDate() : LocalDate.of(plan.getPlanYear(), 12, 31);
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private Optional<BigDecimal> currencyFactor(ProcurementPlan plan, PurchaseOrder order, String tenantId, UUID companyId) {
        String planCurrency = plan.getCurrencyCode() == null ? "UZS" : plan.getCurrencyCode();
        String orderCurrency = order.getCurrencyCode() == null ? planCurrency : order.getCurrencyCode();
        if (planCurrency.equalsIgnoreCase(orderCurrency)) return Optional.of(BigDecimal.ONE);
        LocalDate rateDate = order.getOrderDate() != null ? order.getOrderDate() :
            order.getCreatedAt() == null ? LocalDate.now() : order.getCreatedAt().toLocalDate();
        Optional<BigDecimal> orderToBase = "UZS".equalsIgnoreCase(orderCurrency)
            ? Optional.of(BigDecimal.ONE)
            : Optional.ofNullable(order.getExchangeRate()).filter(rate -> rate.signum() > 0 && rate.compareTo(BigDecimal.ONE) != 0)
                .or(() -> latestBaseRate(orderCurrency, tenantId, companyId, rateDate));
        Optional<BigDecimal> planToBase = "UZS".equalsIgnoreCase(planCurrency)
            ? Optional.of(BigDecimal.ONE)
            : latestBaseRate(planCurrency, tenantId, companyId, rateDate);
        if (orderToBase.isEmpty() || planToBase.isEmpty() || planToBase.get().signum() <= 0) return Optional.empty();
        return Optional.of(orderToBase.get().divide(planToBase.get(), 8, RoundingMode.HALF_UP));
    }

    private Optional<BigDecimal> latestBaseRate(String isoCode, String tenantId, UUID companyId, LocalDate rateDate) {
        Currency currency = currencyRepository.findByTenantIdAndIsoCodeIgnoreCaseAndIsDeletedFalse(tenantId, isoCode).orElse(null);
        if (currency == null) return Optional.empty();
        return exchangeRateRepository.findLatestOnOrBefore(tenantId, companyId, true, currency.getId(), rateDate, PageRequest.of(0, 1))
            .stream().findFirst().map(rate -> rate.getRateToBase()).filter(rate -> rate != null && rate.signum() > 0);
    }

    private BigDecimal nvl(BigDecimal value) { return value == null ? ZERO : value; }
    private BigDecimal money(BigDecimal value) { return value.setScale(4, RoundingMode.HALF_UP); }
}
