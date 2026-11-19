package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ExitWeighedIssueTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode transfer() {
        ObjectNode value = json.createObjectNode();
        value.put("id", 41).put("fromWarehouseId", 12).put("productId", 9)
            .put("lastStatus", "EXIT_ACCEPTED").put("confirmedNettoKg", 125.5).put("measureUnit", "kg");
        value.putArray("confirmedStatuses").add("EXIT_ACCEPTED");
        return value;
    }

    @Test void acceptsConfirmedExitAndLaterStatuses() {
        for (String status : new String[]{"EXIT_ACCEPTED", "EXIT_CONFIRMED", "EXIT_ENTERED", "EXIT_ENTERED_FACTORY"}) {
            ObjectNode value = transfer().put("lastStatus", status);
            assertThat(ExitWeighedIssue.from(value)).isNotNull();
        }
    }

    @Test void refusesInferredOrIncompleteExitEvidence() {
        ObjectNode value = transfer();
        value.put("lastStatus", "END_LOAD");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value = transfer(); value.putArray("confirmedStatuses");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value = transfer(); value.remove("confirmedNettoKg");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value = transfer(); value.put("confirmedNettoKg", 0);
        assertThat(ExitWeighedIssue.from(value)).isNull();
    }

    @Test void refusesClosedReenteredInternalAndCylinderFlows() {
        ObjectNode value = transfer(); value.putObject("administrativeClosure");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value = transfer().put("reentryAllowed", true);
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value = transfer().put("transferType", "INTERNAL");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value = transfer().put("cylinderHandoverId", 3);
        assertThat(ExitWeighedIssue.from(value)).isNull();
    }

    @Test void acceptsActiveFeedRecordWithoutDeletionField() {
        ObjectNode value = transfer(); value.remove("deleted");
        assertThat(ExitWeighedIssue.from(value)).isNotNull();
    }

    @Test void requiresMassDimensionForVersionedQuantityModels() {
        ObjectNode value = transfer().put("quantityModelVersion", 3);
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value.putObject("warehouseQuantity").put("dimension", "VOLUME");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value.withObject("/warehouseQuantity").put("dimension", "MASS");
        assertThat(ExitWeighedIssue.from(value)).isNotNull();
    }

    @Test void refusesUnknownOrNonMassLegacyUnits() {
        ObjectNode value = transfer().put("measureUnit", "dona");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value.remove("measureUnit");
        assertThat(ExitWeighedIssue.from(value)).isNull();
        value.put("measureUnit", "тонна");
        assertThat(ExitWeighedIssue.from(value)).isNotNull();
    }
}
