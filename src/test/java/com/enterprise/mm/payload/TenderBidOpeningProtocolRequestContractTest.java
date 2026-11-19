package com.enterprise.mm.payload;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TenderBidOpeningProtocolRequestContractTest {

    @Test
    void openBidsActionRequestUsesNotesName() {
        assertThat(Arrays.stream(BusinessActionDtos.TenderBidOpeningRequest.class.getRecordComponents())
            .map(component -> component.getName()))
            .containsExactly("openedBy", "quorumPresent", "notes");
    }

    @Test
    void createRequestContainsOnlyManuallyInsertableFields() {
        assertThat(Arrays.stream(TenderBidOpeningProtocolCreateRequest.class.getDeclaredFields())
            .map(Field::getName))
            .containsExactlyInAnyOrder(
                "code",
                "name",
                "description",
                "rfqId",
                "openedBy",
                "quorumPresent",
                "notes"
            );
    }
}
