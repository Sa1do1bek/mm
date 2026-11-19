package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NonMovingStockDetectionPeriodRepositoryTest {
    @Autowired NonMovingStockRepository stockRepository;
    @Autowired NonMovingStockCaseRepository caseRepository;

    @Test
    void workspacePeriodUsesBusinessDetectionDateInsteadOfRecordCreationDate() {
        String tenant = "tenant-detection-period-" + UUID.randomUUID();
        UUID company = UUID.randomUUID();
        LocalDateTime periodStart = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime periodEnd = LocalDateTime.of(2026, 7, 31, 23, 59, 59);

        NonMovingStock detectedInPeriod = stock(tenant, company, "NMS-IN", periodStart.plusDays(4));
        detectedInPeriod.setCreatedAt(periodStart.minusMonths(2));
        NonMovingStock createdInPeriodButDetectedEarlier = stock(tenant, company, "NMS-OUT", periodStart.minusDays(1));
        createdInPeriodButDetectedEarlier.setCreatedAt(periodStart.plusDays(4));
        stockRepository.saveAllAndFlush(List.of(detectedInPeriod, createdInPeriodButDetectedEarlier));

        NonMovingStockCase caseDetectedInPeriod = stockCase(tenant, company, "CASE-IN", periodStart.plusDays(5));
        caseDetectedInPeriod.setCreatedAt(periodStart.minusMonths(2));
        NonMovingStockCase caseCreatedInPeriodButDetectedEarlier = stockCase(tenant, company, "CASE-OUT", periodStart.minusDays(1));
        caseCreatedInPeriodButDetectedEarlier.setCreatedAt(periodStart.plusDays(5));
        caseRepository.saveAllAndFlush(List.of(caseDetectedInPeriod, caseCreatedInPeriodButDetectedEarlier));

        assertThat(stockRepository.search(
            tenant, company, false, true, "", periodStart, periodEnd,
            List.of("__NO_STATUS_FILTER__"), true, null, List.of(), true, List.of(UUID.randomUUID()), true,
            null, false, Pageable.unpaged()
        ).getContent()).extracting(NonMovingStock::getCode).containsExactly("NMS-IN");

        assertThat(caseRepository.search(
            tenant, company, false, true, "", periodStart, periodEnd,
            List.of("__NO_STATUS_FILTER__"), true, null, List.of(UUID.randomUUID()), true,
            null, false, List.of("__NO_DECISION_FILTER__"), true,
            List.of("__NO_EXECUTION_FILTER__"), true, null, null, null,
            List.of("__NO_BLOCKER_FILTER__"), true, List.of("__NO_INTEGRATION_FILTER__"), true,
            List.of("__NO_EXCLUSION_FILTER__"), true, false, Pageable.unpaged()
        ).getContent()).extracting(NonMovingStockCase::getCaseNo).containsExactly("CASE-IN");
    }

    private NonMovingStock stock(String tenant, UUID company, String code, LocalDateTime detectedAt) {
        NonMovingStock stock = new NonMovingStock();
        stock.setTenantId(tenant);
        stock.setCompanyId(company);
        stock.setCode(code);
        stock.setName(code);
        stock.setStatus("DETECTED");
        stock.setMaterialId(UUID.randomUUID());
        stock.setWarehouseId(UUID.randomUUID());
        stock.setQuantity(BigDecimal.ONE);
        stock.setDetectedAt(detectedAt);
        return stock;
    }

    private NonMovingStockCase stockCase(String tenant, UUID company, String caseNo, LocalDateTime detectedAt) {
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setTenantId(tenant);
        stockCase.setCompanyId(company);
        stockCase.setCode(caseNo);
        stockCase.setName(caseNo);
        stockCase.setCaseNo(caseNo);
        stockCase.setStatus("UNDER_REVIEW");
        stockCase.setDetectedAt(detectedAt);
        stockCase.setTotalQuantity(BigDecimal.ONE);
        stockCase.setTotalBookValue(BigDecimal.TEN);
        return stockCase;
    }
}
