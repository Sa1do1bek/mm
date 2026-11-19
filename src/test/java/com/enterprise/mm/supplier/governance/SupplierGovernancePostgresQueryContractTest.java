package com.enterprise.mm.supplier.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupplierGovernancePostgresQueryContractTest {
    @Test
    void existsOnlySupplierQueriesDoNotCombineDistinctWithComputedOrdering() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/supplier/governance/repository/"
                + "SupplierGovernanceQueryRepository.java"
        ));

        assertThat(source)
            .as("PostgreSQL rejects SELECT DISTINCT entity rows ordered by a non-selected coalesce expression")
            .doesNotContain("query.select(root).distinct(true)");
    }
}
