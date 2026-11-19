package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetDepreciationEntry;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetCategory;
import com.enterprise.mm.enums.DepreciationMethod;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetDepreciationEntryResponse;
import com.enterprise.mm.payload.AssetDepreciationRunRequest;
import com.enterprise.mm.payload.AssetDepreciationRunResponse;
import com.enterprise.mm.payload.AssetDepreciationScheduleResponse;
import com.enterprise.mm.payload.FixedAssetAnalyticsSummaryResponse;
import com.enterprise.mm.payload.FixedAssetDepreciationConfigRequest;
import com.enterprise.mm.payload.FixedAssetResponse;
import com.enterprise.mm.payload.FixedAssetRevaluationRequest;
import com.enterprise.mm.repository.AssetDepreciationEntryRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.FIXED_ASSET_EXECUTION, reads = {"getDepreciationEntries", "getDepreciationSchedule", "getAnalyticsSummary"})
public class FixedAssetDepreciationService {

    private final FixedAssetRepository fixedAssetRepository;
    private final AssetDepreciationEntryRepository entryRepository;
    private final AssetEventRepository eventRepository;
    private final AssetFoundationMapper mapper;
    private final IdempotencyService idempotencyService;

    @Transactional(readOnly = true)
    public Page<AssetDepreciationEntryResponse> getDepreciationEntries(UUID assetId, Pageable pageable) {
        findAsset(assetId);
        return entryRepository.findByTenantIdAndCompanyIdAndFixedAssetIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), assetId, pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AssetDepreciationScheduleResponse getDepreciationSchedule(UUID assetId) {
        FixedAsset asset = findAsset(assetId);
        BigDecimal acquisition = asset.getAcquisitionValue() != null ? asset.getAcquisitionValue() : BigDecimal.ZERO;
        BigDecimal salvage = asset.getSalvageValue() != null ? asset.getSalvageValue() : BigDecimal.ZERO;
        BigDecimal accumulated = asset.getAccumulatedDepreciation() != null ? asset.getAccumulatedDepreciation() : BigDecimal.ZERO;
        BigDecimal currentBook = asset.getCurrentBookValue() != null ? asset.getCurrentBookValue() : acquisition.subtract(accumulated);
        int usefulLife = asset.getUsefulLifeMonths() != null && asset.getUsefulLifeMonths() > 0 ? asset.getUsefulLifeMonths() : 60;
        DepreciationMethod method = asset.getDepreciationMethod() != null ? asset.getDepreciationMethod() : DepreciationMethod.STRAIGHT_LINE;

        List<AssetDepreciationEntryResponse> scheduleEntries = new ArrayList<>();
        BigDecimal depreciableAmount = acquisition.subtract(salvage).max(BigDecimal.ZERO);
        BigDecimal monthlyAmount = BigDecimal.ZERO;

        if (method == DepreciationMethod.STRAIGHT_LINE && usefulLife > 0) {
            monthlyAmount = depreciableAmount.divide(BigDecimal.valueOf(usefulLife), 2, RoundingMode.HALF_UP);
        }

        LocalDate startDate = asset.getDepreciationStartDate() != null
            ? asset.getDepreciationStartDate()
            : (asset.getFirstCommissionedOn() != null ? asset.getFirstCommissionedOn() : LocalDate.now());

        BigDecimal runningBookValue = acquisition;
        BigDecimal runningAccumulated = BigDecimal.ZERO;

        for (int monthIndex = 1; monthIndex <= usefulLife; monthIndex++) {
            LocalDate periodDate = startDate.plusMonths(monthIndex - 1);
            BigDecimal depAmount = monthlyAmount;
            if (runningBookValue.subtract(depAmount).compareTo(salvage) < 0) {
                depAmount = runningBookValue.subtract(salvage).max(BigDecimal.ZERO);
            }
            BigDecimal closingBookValue = runningBookValue.subtract(depAmount);
            runningAccumulated = runningAccumulated.add(depAmount);

            AssetDepreciationEntryResponse entry = new AssetDepreciationEntryResponse();
            entry.setFixedAssetId(asset.getId());
            entry.setFixedAssetName(asset.getName());
            entry.setAssetCode(asset.getAssetCode());
            entry.setInventoryNumber(asset.getInventoryNumber());
            entry.setPeriodYear(periodDate.getYear());
            entry.setPeriodMonth(periodDate.getMonthValue());
            entry.setPeriodDate(periodDate);
            entry.setOpeningBookValue(runningBookValue);
            entry.setDepreciationAmount(depAmount);
            entry.setClosingBookValue(closingBookValue);
            entry.setAccumulatedDepreciation(runningAccumulated);
            entry.setCalculationMethod(method.name());
            entry.setPosted(runningAccumulated.compareTo(accumulated) <= 0);
            scheduleEntries.add(entry);

            runningBookValue = closingBookValue;
            if (runningBookValue.compareTo(salvage) <= 0) {
                break;
            }
        }

        int remainingMonths = usefulLife;
        if (monthlyAmount.compareTo(BigDecimal.ZERO) > 0) {
            remainingMonths = currentBook.subtract(salvage).max(BigDecimal.ZERO)
                .divide(monthlyAmount, 0, RoundingMode.CEILING).intValue();
        }

        return AssetDepreciationScheduleResponse.builder()
            .fixedAssetId(asset.getId())
            .assetName(asset.getName())
            .assetCode(asset.getAssetCode())
            .inventoryNumber(asset.getInventoryNumber())
            .acquisitionValue(acquisition)
            .currentBookValue(currentBook)
            .salvageValue(salvage)
            .usefulLifeMonths(usefulLife)
            .remainingLifeMonths(remainingMonths)
            .depreciationMethod(method.name())
            .monthlyDepreciationAmount(monthlyAmount)
            .scheduleEntries(scheduleEntries)
            .build();
    }

    @Transactional
    public FixedAssetResponse configureDepreciation(UUID assetId, FixedAssetDepreciationConfigRequest request, String idempotencyKey) {
        FixedAsset asset = findAsset(assetId);
        requireVersion(asset, request == null ? null : request.expectedVersion());

        if (request != null) {
            if (request.category() != null) {
                asset.setCategory(request.category());
            }
            if (request.depreciationMethod() != null) {
                asset.setDepreciationMethod(request.depreciationMethod());
            }
            if (request.usefulLifeMonths() != null && request.usefulLifeMonths() > 0) {
                asset.setUsefulLifeMonths(request.usefulLifeMonths());
            }
            if (request.salvageValue() != null) {
                asset.setSalvageValue(request.salvageValue());
            }
            if (request.depreciationStartDate() != null) {
                asset.setDepreciationStartDate(request.depreciationStartDate());
            }
        }

        BigDecimal acquisition = asset.getAcquisitionValue() != null ? asset.getAcquisitionValue() : BigDecimal.ZERO;
        BigDecimal accumulated = asset.getAccumulatedDepreciation() != null ? asset.getAccumulatedDepreciation() : BigDecimal.ZERO;
        asset.setCurrentBookValue(acquisition.subtract(accumulated));

        appendAssetEvent(asset, "ASSET_DEPRECIATION_CONFIGURED", asset.getLifecycleStatus(), asset.getCustodyKind(),
            request != null ? request.reason() : null, idempotencyKey);

        return mapper.toResponse(fixedAssetRepository.save(asset));
    }

    @Transactional
    public FixedAssetResponse revalue(UUID assetId, FixedAssetRevaluationRequest request, String idempotencyKey) {
        if (request == null || request.newAcquisitionValue() == null) {
            throw new BadRequestException("newAcquisitionValue is required for revaluation", "ASSET_REVALUATION_VALUE_REQUIRED");
        }
        FixedAsset asset = findAsset(assetId);
        requireVersion(asset, request.expectedVersion());

        BigDecimal oldAcquisition = asset.getAcquisitionValue() != null ? asset.getAcquisitionValue() : BigDecimal.ZERO;
        BigDecimal oldBookValue = asset.getCurrentBookValue() != null ? asset.getCurrentBookValue() : oldAcquisition;

        asset.setAcquisitionValue(request.newAcquisitionValue());
        if (request.newUsefulLifeMonths() != null && request.newUsefulLifeMonths() > 0) {
            asset.setUsefulLifeMonths(request.newUsefulLifeMonths());
        }
        if (request.newSalvageValue() != null) {
            asset.setSalvageValue(request.newSalvageValue());
        }

        BigDecimal accumulated = asset.getAccumulatedDepreciation() != null ? asset.getAccumulatedDepreciation() : BigDecimal.ZERO;
        asset.setCurrentBookValue(request.newAcquisitionValue().subtract(accumulated));

        String reason = (request.reason() != null ? request.reason() : "Asset revaluation")
            + " [Old Book Value: " + oldBookValue + ", New Value: " + asset.getCurrentBookValue() + "]";

        appendAssetEvent(asset, "ASSET_REVALUED", asset.getLifecycleStatus(), asset.getCustodyKind(), reason, idempotencyKey);

        return mapper.toResponse(fixedAssetRepository.save(asset));
    }

    @Transactional
    public AssetDepreciationRunResponse executeDepreciationRun(AssetDepreciationRunRequest request, String idempotencyKey) {
        if (request == null || request.year() == null || request.month() == null) {
            throw new BadRequestException("year and month are required", "ASSET_DEPRECIATION_PERIOD_REQUIRED");
        }

        int year = request.year();
        int month = request.month();
        LocalDate periodDate = LocalDate.of(year, month, 1);
        String runReference = "DEP-RUN-" + year + String.format(Locale.ROOT, "%02d", month) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);

        List<FixedAsset> candidates;
        if (request.assetIds() != null && !request.assetIds().isEmpty()) {
            candidates = fixedAssetRepository.findAllById(request.assetIds()).stream()
                .filter(a -> !a.isDeleted()
                    && Objects.equals(a.getTenantId(), TenantContext.currentTenant())
                    && Objects.equals(a.getCompanyId(), CompanyContext.currentCompany()))
                .toList();
        } else {
            candidates = fixedAssetRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), Pageable.unpaged()
            ).getContent();
        }

        List<AssetDepreciationEntryResponse> entryResponses = new ArrayList<>();
        BigDecimal totalRunDepreciation = BigDecimal.ZERO;
        int processedCount = 0;

        for (FixedAsset asset : candidates) {
            if (asset.getLifecycleStatus() == FixedAssetLifecycleStatus.DISPOSED
                || asset.getLifecycleStatus() == FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER
                || asset.getLifecycleStatus() == FixedAssetLifecycleStatus.CANCELLED) {
                continue;
            }

            DepreciationMethod method = asset.getDepreciationMethod() != null ? asset.getDepreciationMethod() : DepreciationMethod.STRAIGHT_LINE;
            if (method == DepreciationMethod.NO_DEPRECIATION) {
                continue;
            }

            if (entryRepository.existsByTenantIdAndCompanyIdAndFixedAssetIdAndPeriodYearAndPeriodMonthAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), asset.getId(), year, month
            )) {
                continue;
            }

            BigDecimal acquisition = asset.getAcquisitionValue() != null ? asset.getAcquisitionValue() : BigDecimal.ZERO;
            BigDecimal salvage = asset.getSalvageValue() != null ? asset.getSalvageValue() : BigDecimal.ZERO;
            BigDecimal accumulated = asset.getAccumulatedDepreciation() != null ? asset.getAccumulatedDepreciation() : BigDecimal.ZERO;
            BigDecimal currentBook = asset.getCurrentBookValue() != null ? asset.getCurrentBookValue() : acquisition.subtract(accumulated);

            if (currentBook.compareTo(salvage) <= 0) {
                continue;
            }

            int usefulLife = asset.getUsefulLifeMonths() != null && asset.getUsefulLifeMonths() > 0 ? asset.getUsefulLifeMonths() : 60;
            BigDecimal depreciableBase = acquisition.subtract(salvage).max(BigDecimal.ZERO);
            BigDecimal monthlyAmount = depreciableBase.divide(BigDecimal.valueOf(usefulLife), 2, RoundingMode.HALF_UP);

            if (currentBook.subtract(monthlyAmount).compareTo(salvage) < 0) {
                monthlyAmount = currentBook.subtract(salvage).max(BigDecimal.ZERO);
            }

            if (monthlyAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal openingBookValue = currentBook;
            BigDecimal closingBookValue = openingBookValue.subtract(monthlyAmount);
            BigDecimal newAccumulated = accumulated.add(monthlyAmount);

            AssetDepreciationEntry entry = new AssetDepreciationEntry();
            entry.setCode("ADE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            entry.setName("Depreciation " + asset.getInventoryNumber() + " " + year + "/" + month);
            entry.setStatus("POSTED");
            entry.setFixedAssetId(asset.getId());
            entry.setPeriodYear(year);
            entry.setPeriodMonth(month);
            entry.setPeriodDate(periodDate);
            entry.setOpeningBookValue(openingBookValue);
            entry.setDepreciationAmount(monthlyAmount);
            entry.setClosingBookValue(closingBookValue);
            entry.setAccumulatedDepreciation(newAccumulated);
            entry.setCalculationMethod(method);
            entry.setPosted(true);
            entry.setPostedAt(LocalDateTime.now());
            entry.setPostedBy("system");
            entry.setRunReference(runReference);

            entryRepository.save(entry);

            asset.setAccumulatedDepreciation(newAccumulated);
            asset.setCurrentBookValue(closingBookValue);
            asset.setLastDepreciationDate(periodDate);
            fixedAssetRepository.save(asset);

            appendAssetEvent(asset, "ASSET_DEPRECIATION_POSTED", asset.getLifecycleStatus(), asset.getCustodyKind(),
                "Monthly depreciation for " + year + "-" + month + ": " + monthlyAmount, idempotencyKey);

            entryResponses.add(mapper.toResponse(entry));
            totalRunDepreciation = totalRunDepreciation.add(monthlyAmount);
            processedCount++;
        }

        return AssetDepreciationRunResponse.builder()
            .runReference(runReference)
            .year(year)
            .month(month)
            .processedAssetCount(processedCount)
            .totalDepreciationAmount(totalRunDepreciation)
            .entries(entryResponses)
            .build();
    }

    @Transactional(readOnly = true)
    public FixedAssetAnalyticsSummaryResponse getAnalyticsSummary() {
        List<FixedAsset> assets = fixedAssetRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), Pageable.unpaged()
        ).getContent();

        long totalCount = assets.size();
        long inWarehouseCount = 0;
        long inUseCount = 0;
        long idleCount = 0;
        long underRepairCount = 0;
        long disposedCount = 0;

        BigDecimal totalAcquisition = BigDecimal.ZERO;
        BigDecimal totalAccumulated = BigDecimal.ZERO;
        BigDecimal totalNetBook = BigDecimal.ZERO;

        Map<String, FixedAssetAnalyticsSummaryResponse.CategoryMetric> categoryMap = new HashMap<>();
        Map<String, FixedAssetAnalyticsSummaryResponse.DepartmentMetric> departmentMap = new HashMap<>();

        for (FixedAsset asset : assets) {
            FixedAssetLifecycleStatus status = asset.getLifecycleStatus();
            if (status == FixedAssetLifecycleStatus.IN_WAREHOUSE || status == FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING) {
                inWarehouseCount++;
            } else if (status == FixedAssetLifecycleStatus.IN_USE) {
                inUseCount++;
            } else if (status == FixedAssetLifecycleStatus.IDLE) {
                idleCount++;
            } else if (status == FixedAssetLifecycleStatus.UNDER_REPAIR) {
                underRepairCount++;
            } else if (status == FixedAssetLifecycleStatus.DISPOSED || status == FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER) {
                disposedCount++;
            }

            BigDecimal acq = asset.getAcquisitionValue() != null ? asset.getAcquisitionValue() : BigDecimal.ZERO;
            BigDecimal acc = asset.getAccumulatedDepreciation() != null ? asset.getAccumulatedDepreciation() : BigDecimal.ZERO;
            BigDecimal book = asset.getCurrentBookValue() != null ? asset.getCurrentBookValue() : acq.subtract(acc);

            totalAcquisition = totalAcquisition.add(acq);
            totalAccumulated = totalAccumulated.add(acc);
            totalNetBook = totalNetBook.add(book);

            String catName = asset.getCategory() != null ? asset.getCategory().name() : AssetCategory.MACHINERY_EQUIPMENT.name();
            var catMetric = categoryMap.computeIfAbsent(catName, k -> FixedAssetAnalyticsSummaryResponse.CategoryMetric.builder()
                .category(k)
                .count(0)
                .totalAcquisitionValue(BigDecimal.ZERO)
                .totalBookValue(BigDecimal.ZERO)
                .totalAccumulatedDepreciation(BigDecimal.ZERO)
                .build());
            catMetric.setCount(catMetric.getCount() + 1);
            catMetric.setTotalAcquisitionValue(catMetric.getTotalAcquisitionValue().add(acq));
            catMetric.setTotalBookValue(catMetric.getTotalBookValue().add(book));
            catMetric.setTotalAccumulatedDepreciation(catMetric.getTotalAccumulatedDepreciation().add(acc));

            String deptName = asset.getDepartment() != null ? asset.getDepartment().getName() : (asset.getDepartmentId() != null ? asset.getDepartmentId().toString() : "Unassigned");
            var deptMetric = departmentMap.computeIfAbsent(deptName, k -> FixedAssetAnalyticsSummaryResponse.DepartmentMetric.builder()
                .departmentName(k)
                .count(0)
                .totalBookValue(BigDecimal.ZERO)
                .build());
            deptMetric.setCount(deptMetric.getCount() + 1);
            deptMetric.setTotalBookValue(deptMetric.getTotalBookValue().add(book));
        }

        return FixedAssetAnalyticsSummaryResponse.builder()
            .totalCount(totalCount)
            .inWarehouseCount(inWarehouseCount)
            .inUseCount(inUseCount)
            .idleCount(idleCount)
            .underRepairCount(underRepairCount)
            .disposedCount(disposedCount)
            .totalAcquisitionValue(totalAcquisition)
            .totalAccumulatedDepreciation(totalAccumulated)
            .totalNetBookValue(totalNetBook)
            .categoryMetrics(categoryMap)
            .departmentMetrics(departmentMap)
            .build();
    }

    private FixedAsset findAsset(UUID id) {
        return fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id
        ).orElseThrow(() -> new NotFoundException("Fixed asset not found: " + id));
    }

    private void requireVersion(FixedAsset asset, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required", "ASSET_VERSION_CONFLICT");
        }
        if (!Objects.equals(asset.getVersion(), expectedVersion)) {
            throw new ConflictException("Fixed asset version conflict", "ASSET_VERSION_CONFLICT");
        }
    }

    private void appendAssetEvent(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String reason,
        String idempotencyKey
    ) {
        AssetEvent event = new AssetEvent();
        event.setCode("AE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        event.setName(eventType);
        event.setStatus("POSTED");
        event.setFixedAssetId(asset.getId());
        event.setEventType(eventType);
        event.setIdempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null);
        event.setBeforeLifecycleStatus(beforeLifecycle);
        event.setAfterLifecycleStatus(asset.getLifecycleStatus());
        event.setBeforeCustodyKind(beforeCustody);
        event.setAfterCustodyKind(asset.getCustodyKind());
        event.setEffectiveDate(LocalDate.now());
        event.setRecordedAt(LocalDateTime.now());
        event.setActorUsername("system");
        event.setReason(reason != null && !reason.isBlank() ? reason.trim() : null);
        event.setSourceDocumentType("FIXED_ASSET");
        event.setSourceDocumentId(asset.getId());
        event.setAggregateVersion(asset.getVersion());
        eventRepository.save(event);
    }
}
