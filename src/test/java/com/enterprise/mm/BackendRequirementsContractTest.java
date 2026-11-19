package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.MaterialReturnDocument;
import com.enterprise.mm.entity.MaterialReturnLine;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.enums.IntegrationStatus;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.MaterialReturnCondition;
import com.enterprise.mm.enums.MaterialReturnStatus;
import com.enterprise.mm.enums.TransferStatus;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.mapper.IssueRequestMapper;
import com.enterprise.mm.mapper.MaterialReturnMapper;
import com.enterprise.mm.mapper.TransferDocumentMapper;
import com.enterprise.mm.service.StockBalanceMutationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackendRequirementsContractTest {

    @Test
    void issueRequestResponseCalculatesBackendOnlyQuantitiesAndActions() {
        IssueRequest request = new IssueRequest();
        request.setStatus(IssueRequestStatus.DRAFT.name());
        IssueRequestLine line = new IssueRequestLine();
        line.setApprovedQuantity(new BigDecimal("10"));
        line.setIssuedQuantity(new BigDecimal("4"));
        line.setUnitCost(new BigDecimal("12.50"));

        var response = new IssueRequestMapper().toResponse(request, List.of(line));

        assertThat(response.getAllowedActions()).containsExactly("submit", "cancel");
        assertThat(response.getLines().get(0).getRemainingQuantity()).isEqualByComparingTo("6");
        assertThat(response.getLines().get(0).getTotalCost()).isEqualByComparingTo("50.00");
    }

    @Test
    void partiallyIssuedRequestStaysExecutableAndCanBeClosedAsAnException() {
        IssueRequest request = new IssueRequest();
        request.setStatus("PARTIALLY_ISSUED");
        IssueRequestLine line = new IssueRequestLine();
        line.setApprovedQuantity(new BigDecimal("10"));
        line.setIssuedQuantity(new BigDecimal("4"));

        var response = new IssueRequestMapper().toResponse(request, List.of(line));

        assertThat(response.getAllowedActions()).containsExactly("issue", "close");
        assertThat(response.getLines().get(0).getRemainingQuantity()).isEqualByComparingTo("6");
    }

    @Test
    void transferMapperKeepsInTransitValueVisibleWithoutProjectingActorActions() {
        TransferDocument document = new TransferDocument();
        document.setStatus(TransferStatus.IN_TRANSIT.name());
        TransferDocumentLine line = new TransferDocumentLine();
        line.setPlannedQuantity(new BigDecimal("100"));
        line.setDispatchedQuantity(new BigDecimal("100"));
        line.setInTransitQuantity(new BigDecimal("40"));
        line.setUnitCost(new BigDecimal("5"));

        var response = new TransferDocumentMapper().toResponse(document, List.of(line));

        assertThat(response.getAllowedActions()).isEmpty();
        assertThat(response.getLines().get(0).getInTransitValue()).isEqualByComparingTo("200");
    }

    @Test
    void partiallyDispatchedTransferKeepsBothRemainingAndInTransitQuantities() {
        TransferDocument document = new TransferDocument();
        document.setStatus(TransferStatus.IN_TRANSIT.name());
        TransferDocumentLine line = new TransferDocumentLine();
        line.setPlannedQuantity(new BigDecimal("100"));
        line.setDispatchedQuantity(new BigDecimal("60"));
        line.setInTransitQuantity(new BigDecimal("60"));

        var response = new TransferDocumentMapper().toResponse(document, List.of(line));

        assertThat(response.getLines().get(0).getRemainingQuantity()).isEqualByComparingTo("40");
        assertThat(response.getLines().get(0).getInTransitQuantity()).isEqualByComparingTo("60");
        assertThat(response.getAllowedActions()).isEmpty();
    }

    @Test
    void returnResponseUsesOriginalIssueCost() {
        MaterialReturnDocument document = new MaterialReturnDocument();
        document.setStatus(MaterialReturnStatus.DRAFT.name());
        MaterialReturnLine line = new MaterialReturnLine();
        line.setCondition(MaterialReturnCondition.USABLE);
        line.setIssuedQuantity(new BigDecimal("40"));
        line.setPreviouslyReturnedQuantity(new BigDecimal("5"));
        line.setReturnQuantity(new BigDecimal("10"));
        line.setAcceptedQuantity(BigDecimal.ZERO);
        line.setOriginalUnitCost(new BigDecimal("12500"));

        var response = new MaterialReturnMapper().toResponse(document, List.of(line));

        assertThat(response.getLines().get(0).getReturnableQuantity()).isEqualByComparingTo("35");
        assertThat(response.getLines().get(0).getTotalReturnValue()).isEqualByComparingTo("125000");
    }

    @Test
    void stockMutationReturnsStructuredShortageAndSupportsQuarantine() {
        StockBalance balance = new StockBalance();
        balance.setMaterialId(UUID.randomUUID());
        balance.setWarehouseId(UUID.randomUUID());
        balance.setQuantity(new BigDecimal("5"));
        balance.setAvailableQuantity(new BigDecimal("5"));
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        StockBalanceMutationService service = new StockBalanceMutationService();

        assertThatThrownBy(() -> service.decreaseOnHand(balance, new BigDecimal("6"), BigDecimal.ZERO, false))
            .isInstanceOf(InsufficientStockException.class)
            .satisfies(error -> assertThat(((InsufficientStockException) error).details())
                .containsEntry("availableQuantity", new BigDecimal("5")));

        service.moveAvailableToQuarantine(balance, new BigDecimal("2"));
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("3");
        assertThat(balance.getQuarantineQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void integrationStatusesAreNormalizedForFrontend() {
        assertThat(IntegrationStatus.fromLegacy("NEW")).isEqualTo(IntegrationStatus.PENDING);
        assertThat(IntegrationStatus.fromLegacy("PUBLISHED")).isEqualTo(IntegrationStatus.SUCCEEDED);
        assertThat(IntegrationStatus.fromLegacy("RETRY")).isEqualTo(IntegrationStatus.RETRY_SCHEDULED);
    }
}
