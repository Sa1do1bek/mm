package com.enterprise.mm.common;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BusinessDisplayNamePolicyTest {
    @Test
    void acceptsAndTrimsHumanReadableNames() {
        assertThat(BusinessDisplayNamePolicy.requireHumanName(
            "Purchase request",
            "  Chemical reagents for ammonia production  ",
            "PURCHASEREQUEST-eee581f3"
        )).isEqualTo("Chemical reagents for ammonia production");
    }

    @Test
    void usesTheHumanEntityLabelForBlankNames() {
        assertThat(BusinessDisplayNamePolicy.requireHumanName(
            "Purchase request",
            " ",
            "PURCHASEREQUEST-eee581f3"
        )).isEqualTo("Purchase request");
    }

    @Test
    void replacesNamesEqualToTheInternalCodeWithTheHumanEntityLabel() {
        assertThat(BusinessDisplayNamePolicy.requireHumanName(
            "Purchase request",
            "PURCHASEREQUEST-eee581f3",
            "PURCHASEREQUEST-eee581f3"
        )).isEqualTo("Purchase request");
    }

    @Test
    void replacesUuidNamesWithTheHumanEntityLabel() {
        assertThat(BusinessDisplayNamePolicy.requireHumanName(
            "Purchase request",
            "1be1f69c-31cc-49dc-b170-a2eeb7894f98",
            null
        )).isEqualTo("Purchase request");
    }

    @Test
    void turnsJavaEntityNamesIntoReadableFallbacks() {
        assertThat(BusinessDisplayNamePolicy.requireHumanName(
            "WorkCompletionAct",
            null,
            "WORK_COMPLETION_ACT-eee581f3"
        )).isEqualTo("Work Completion Act");
    }

    @Test
    void recognizesUuidAndShortRandomInternalIdentifiers() {
        assertThat(BusinessDisplayNamePolicy.isInternalIdentifier(
            "1be1f69c-31cc-49dc-b170-a2eeb7894f98"
        )).isTrue();
        assertThat(BusinessDisplayNamePolicy.isInternalIdentifier(
            "PURCHASEREQUEST-eee581f3"
        )).isTrue();
        assertThat(BusinessDisplayNamePolicy.isInternalIdentifier(
            "Chemical reagents for ammonia production"
        )).isFalse();
    }
}
