package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.enums.NonMovingStockDecisionType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.NonMovingStockDecisionLineRequest;
import com.enterprise.mm.repository.NonMovingStockDecisionLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NonMovingStockDecisionServiceTest {

    @Mock
    private NonMovingStockDecisionRepository decisionRepository;
    @Mock
    private NonMovingStockDecisionLineRepository decisionLineRepository;

    private NonMovingStockDecisionService service;

    @BeforeEach
    void setUp() {
        service = new NonMovingStockDecisionService(decisionRepository, decisionLineRepository);
    }

    @Test
    void rejectsAllocationsThatExceedCaseLineQuantity() {
        UUID caseLineId = UUID.randomUUID();
        NonMovingStockCaseLine caseLine = caseLine(caseLineId, "10", "100");

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLineId, NonMovingStockDecisionType.WRITE_OFF, "6", null, "Obsolete"),
            request(caseLineId, NonMovingStockDecisionType.TRANSFER, "5", UUID.randomUUID(), null)
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("exceeds eligible quantity");
    }

    @Test
    void supportsMixedLineDecisionsWithinEligibleQuantity() {
        NonMovingStockCaseLine first = caseLine(UUID.randomUUID(), "10", "100");
        NonMovingStockCaseLine second = caseLine(UUID.randomUUID(), "4", "40");

        NonMovingStockDecisionLineRequest impairment = request(
            second.getId(), NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "4", null, null
        );
        impairment.setFinancialAmount(new BigDecimal("40"));

        assertThatCode(() -> service.validateAllocations(List.of(first, second), List.of(
            request(first.getId(), NonMovingStockDecisionType.WRITE_OFF, "6", null, "Obsolete"),
            request(first.getId(), NonMovingStockDecisionType.TRANSFER, "4", UUID.randomUUID(), null),
            impairment
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedDecisionTypeInNestedPackage() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "3", "30");

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLine.getId(), NonMovingStockDecisionType.SELL, "3", null, null)
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("not executable");
    }

    @Test
    void impairmentLineRequiresPositiveFinancialAmount() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "3", "30");

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLine.getId(), NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "3", null, null)
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("impairment amount");
    }

    @Test
    void splitImpairmentCannotExceedCaseLineBookValueInAggregate() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "10", "100");
        NonMovingStockDecisionLineRequest first = request(
            caseLine.getId(), NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "5", null, null
        );
        first.setFinancialAmount(new BigDecimal("60"));
        NonMovingStockDecisionLineRequest second = request(
            caseLine.getId(), NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "5", null, null
        );
        second.setFinancialAmount(new BigDecimal("60"));

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(first, second)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("aggregate impairment amount");
    }

    @Test
    void impairmentCannotConsumeBookValueBelongingToQuantityAllocatedToAnotherAction() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "10", "100");
        NonMovingStockDecisionLineRequest impairment = request(
            caseLine.getId(), NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "2", null, null
        );
        impairment.setFinancialAmount(new BigDecimal("30"));

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            impairment,
            request(caseLine.getId(), NonMovingStockDecisionType.TRANSFER, "8", UUID.randomUUID(), null)
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("proportional carrying value");
    }

    @Test
    void rejectsUnallocatedEligibleQuantity() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "10", "100");

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLine.getId(), NonMovingStockDecisionType.WRITE_OFF, "6", null, "Obsolete")
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("fully allocated");
    }

    @Test
    void explicitDecisionCannotAllocateReservedOrBlockedQuantityWhenAvailabilityIsZero() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "10", "100");
        caseLine.setAvailableQuantity(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLine.getId(), NonMovingStockDecisionType.WRITE_OFF, "10", null, "Obsolete")
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("exceeds eligible quantity");
    }

    @Test
    void transferDestinationCannotEqualSourceWarehouse() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "3", "30");

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLine.getId(), NonMovingStockDecisionType.TRANSFER, "3", caseLine.getWarehouseId(), null)
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("different from source");
    }

    @Test
    void destructiveDecisionRequiresControlledReason() {
        NonMovingStockCaseLine caseLine = caseLine(UUID.randomUUID(), "3", "30");

        assertThatThrownBy(() -> service.validateAllocations(List.of(caseLine), List.of(
            request(caseLine.getId(), NonMovingStockDecisionType.SCRAP, "3", null, " ")
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("reason");
    }

    @Test
    void submittedDecisionIsImmutable() {
        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setImmutable(true);
        decision.setStatus("APPROVAL_PENDING");

        assertThatThrownBy(() -> service.requireEditable(decision))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("immutable");
    }

    private NonMovingStockCaseLine caseLine(UUID id, String quantity, String bookValue) {
        NonMovingStockCaseLine line = new NonMovingStockCaseLine();
        line.setId(id);
        line.setWarehouseId(UUID.randomUUID());
        line.setQuantity(new BigDecimal(quantity));
        line.setAvailableQuantity(new BigDecimal(quantity));
        line.setBookValue(new BigDecimal(bookValue));
        return line;
    }

    private NonMovingStockDecisionLineRequest request(
        UUID caseLineId,
        NonMovingStockDecisionType type,
        String quantity,
        UUID targetWarehouseId,
        String reason
    ) {
        NonMovingStockDecisionLineRequest request = new NonMovingStockDecisionLineRequest();
        request.setCaseLineId(caseLineId);
        request.setDecisionType(type.name());
        request.setApprovedQuantity(new BigDecimal(quantity));
        request.setTargetWarehouseId(targetWarehouseId);
        request.setControlledReason(reason);
        return request;
    }
}
