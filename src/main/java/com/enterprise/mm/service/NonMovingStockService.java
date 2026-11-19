package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.NonMovingStockResponse;
import com.enterprise.mm.payload.NonMovingStockUpdateRequest;
import com.enterprise.mm.payload.NonMovingStockCreateRequest;
import com.enterprise.mm.payload.NonMovingStockSearchRequest;
import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.payload.WarehouseSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.NonMovingStockMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NonMovingStockService {
    private static final List<String> ACTIVE_DETECTION_STATUSES = List.of(
        NonMovingStockStatus.DETECTED.name(),
        NonMovingStockStatus.UNDER_REVIEW.name(),
        NonMovingStockStatus.CASE_CREATED.name(),
        NonMovingStockStatus.COMMISSION_REVIEW.name(),
        NonMovingStockStatus.DECISION_APPROVED.name(),
        NonMovingStockStatus.IN_EXECUTION.name()
    );

    private final NonMovingStockRepository repository;
    private final NonMovingStockMapper mapper;
    private final String entityName = "NonMovingStock";
    private final CompanyRepository companyRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final WarehouseService warehouseService;
    private final MaterialCatalogGuard materialCatalogGuard;

    @Scheduled(cron = "0 20 2 * * *")
    @Transactional
    public void scheduledAnalyze() {
        analyzeAllTenants();
    }
    @Transactional
    public List<NonMovingStockResponse> analyzeAllTenants() {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        List<NonMovingStockResponse> result = new ArrayList<>();
        try {
            for (String tenantId : stockBalanceRepository.findDistinctTenantIdsWithPositiveStock()) {
                TenantContext.setTenant(tenantId);
                result.addAll(analyzeEnabledCompaniesForTenant());
            }
            return result;
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }
    @Transactional
    public List<NonMovingStockResponse> analyze() {
        UUID companyId = CompanyContext.currentCompany();
        int thresholdMonths = companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId)
            .map(company -> company.getNonMovingStockThresholdMonths() == null ? 12 : company.getNonMovingStockThresholdMonths())
            .orElse(12);
        return analyzeCompany(companyId, thresholdMonths);
    }

    private List<NonMovingStockResponse> analyzeEnabledCompaniesForTenant() {
        List<NonMovingStockResponse> result = new ArrayList<>();
        for (var company : companyRepository.findByTenantIdAndIsDeletedFalseOrderByNameAsc(TenantContext.currentTenant())) {
            if (!Boolean.TRUE.equals(company.getNonMovingStockAutoDetectEnabled())) {
                continue;
            }
            UUID previousCompany = CompanyContext.currentCompany();
            try {
                CompanyContext.setCompany(company.getId());
                result.addAll(analyzeCompany(
                    company.getId(),
                    company.getNonMovingStockThresholdMonths() == null ? 12 : company.getNonMovingStockThresholdMonths()
                ));
            } finally {
                CompanyContext.setCompany(previousCompany);
            }
        }
        return result;
    }

    private List<NonMovingStockResponse> analyzeCompany(UUID companyId, int thresholdMonths) {
        List<NonMovingStockResponse> result = new ArrayList<>();
        Map<DetectionKey, List<StockBalance>> groupedBalances = new LinkedHashMap<>();
        for (StockBalance balance : stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            companyId
        )) {
            if (nvl(balance.getQuantity()).signum() <= 0 || balance.getMaterialId() == null || balance.getWarehouseId() == null) {
                continue;
            }
            if (!isTmbStockMaterial(balance.getMaterialId())) {
                continue;
            }
            groupedBalances.computeIfAbsent(
                new DetectionKey(balance.getCompanyId(), balance.getMaterialId(), balance.getWarehouseId()),
                ignored -> new ArrayList<>()
            ).add(balance);
        }

        for (Map.Entry<DetectionKey, List<StockBalance>> entry : groupedBalances.entrySet()) {
            DetectionKey key = entry.getKey();
            List<StockBalance> balanceList = entry.getValue();
            LocalDateTime lastMovementAt = lastMovementAt(key, balanceList);
            if (lastMovementAt == null) {
                continue;
            }
            long months = ChronoUnit.MONTHS.between(lastMovementAt.toLocalDate(), LocalDateTime.now().toLocalDate());
            if (months < thresholdMonths) {
                continue;
            }
            int bucket = bucket(months);
            BigDecimal totalQuantity = balanceList.stream()
                .map(StockBalance::getQuantity)
                .map(this::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            var activeDetection = repository
                .findFirstByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(),
                    key.companyId(),
                    key.materialId(),
                    key.warehouseId(),
                    ACTIVE_DETECTION_STATUSES
                );
            boolean newlyDetected = activeDetection.isEmpty();
            NonMovingStock stock = activeDetection.orElseGet(NonMovingStock::new);
            stock.setCompanyId(key.companyId());
            stock.setCode("NMS-" + key.materialId().toString().substring(0, 8) + "-" + bucket);
            stock.setName("Non-moving stock " + months + " months");
            if (stock.getStatus() == null || stock.getStatus().isBlank()) {
                stock.setStatus(NonMovingStockStatus.DETECTED.name());
            }
            stock.setMaterialId(key.materialId());
            stock.setWarehouseId(key.warehouseId());
            stock.setQuantity(totalQuantity);
            stock.setMonthsWithoutMovement((int) months);
            if (stock.getDetectedAt() == null) {
                stock.setDetectedAt(LocalDateTime.now());
            }
            result.add(mapper.toResponse(repository.save(stock)));
            auditService.log(
                "NonMovingStock", stock.getId(), newlyDetected ? "DETECT" : "REFRESH_ANALYSIS", months + " months"
            , null, null);
        }
        return result;
    }
    @Transactional
    public NonMovingStockResponse applyAction(UUID id, String action, String comment) {
        NonMovingStock stock = findEntity(id);
        NonMovingStockStatus status = NonMovingStockStatus.valueOf(action);
        if (!isAllowedManualAction(stock.getStatus(), status)) {
            throw new BusinessException("Action is not allowed from status " + stock.getStatus() + " to " + status.name());
        }
        stock.setStatus(status.name());
        stock.setPlannedAction(status.name());
        stock.setActionComment(comment);
        auditService.log("NonMovingStock", id, "ACTION_" + status.name(), comment, null, null);
        return mapper.toResponse(repository.save(stock));
    }
    private int bucket(long months) {
        if (months >= 24) {
            return 24;
        }
        if (months >= 12) {
            return 12;
        }
        if (months >= 6) {
            return 6;
        }
        if (months >= 3) {
            return 3;
        }
        return 0;
    }

    private LocalDateTime lastMovementAt(DetectionKey key, List<StockBalance> balances) {
        LocalDateTime balanceCreatedAt = balances.stream()
            .map(StockBalance::getCreatedAt)
            .filter(Objects::nonNull)
            .min(LocalDateTime::compareTo)
            .orElse(null);
        return stockMovementRepository
            .findFirstByTenantIdAndCompanyIdAndMaterial_IdAndWarehouse_IdAndStatusInAndIsDeletedFalseOrderByPostedAtDesc(
                TenantContext.currentTenant(),
                key.companyId(),
                key.materialId(),
                key.warehouseId(),
                StockMovementStatus.effectiveStatusNames()
            )
            .map(movement -> movement.getPostedAt() == null ? movement.getUpdatedAt() : movement.getPostedAt())
            .orElse(balanceCreatedAt);
    }

    private boolean isAllowedManualAction(String currentStatus, NonMovingStockStatus targetStatus) {
        if (targetStatus == NonMovingStockStatus.DETECTED) {
            return false;
        }
        return switch (NonMovingStockStatus.valueOf(currentStatus)) {
            case DETECTED -> targetStatus == NonMovingStockStatus.UNDER_REVIEW;
            case UNDER_REVIEW -> targetStatus == NonMovingStockStatus.COMMISSION_REVIEW;
            case COMMISSION_REVIEW -> targetStatus == NonMovingStockStatus.CASE_CREATED;
            default -> false;
        };
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record DetectionKey(UUID companyId, UUID materialId, UUID warehouseId) {
    }
    @Transactional(readOnly = true)
    public Page<NonMovingStockResponse> search(NonMovingStockSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        String search = filter.normalizedSearch();
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            search.isBlank(),
            searchPattern(search),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            null,
            false,
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<WarehouseResponse> searchTransferWarehouses(
        String search,
        Pageable pageable
    ) {
        WarehouseSearchRequest request = new WarehouseSearchRequest(
            search,
            List.of("ACTIVE"),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        Pageable effectivePageable =
            pageable.isPaged()
                ? pageable
                : PageRequest.of(0, 200, Sort.by("createdAt").descending());
        return warehouseService.search(request, effectivePageable);
    }
    @Transactional(readOnly = true)
    public NonMovingStockResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public NonMovingStockResponse create(NonMovingStockCreateRequest dto) {
        NonMovingStock entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        NonMovingStock saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public NonMovingStockResponse update(UUID id, NonMovingStockUpdateRequest dto) {
        NonMovingStock entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        NonMovingStock saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        NonMovingStock entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public NonMovingStockResponse restore(UUID id) {
        NonMovingStock entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        NonMovingStock saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected NonMovingStock findEntity(UUID id) {
        NonMovingStock entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeCreate(NonMovingStock entity, NonMovingStockCreateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(NonMovingStockStatus.DETECTED.name());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(NonMovingStock entity, NonMovingStockUpdateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private String searchPattern(String value) {
        return value == null || value.isBlank() ? "" : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private boolean isTmbStockMaterial(UUID materialId) {
        try {
            materialCatalogGuard.requireActiveTmbMaterial(materialId, "materialId");
            return true;
        } catch (BusinessException | NotFoundException ex) {
            return false;
        }
    }
}
