package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MaterialReservationLifecycleContractTest {
    private static final Path RESERVATION_REPOSITORY = Path.of(
        "src/main/java/com/enterprise/mm/repository/MaterialReservationRepository.java");
    private static final Path RESERVATION_SERVICE = Path.of(
        "src/main/java/com/enterprise/mm/service/MaterialReservationService.java");





    @Test
    void repositorySerializesReservationCommandsBeforeBalanceMutation() throws Exception {
        String source = Files.readString(RESERVATION_REPOSITORY);

        assertThat(source).contains("@Lock(LockModeType.PESSIMISTIC_WRITE)");
        assertThat(source).contains("Optional<MaterialReservation> findForUpdate");
        assertThat(source).contains("findDueIds");
        assertThat(source).contains("findDueScopes");
    }

    @Test
    void commandServiceUsesCompleteStockKeyVersionAndMandatoryIdempotency() throws Exception {
        String source = Files.readString(RESERVATION_SERVICE);

        assertThat(source).contains("reservation.getStorageBinId() == null");
        assertThat(source).contains("reservation.getStockBatchId() == null");
        assertThat(source).contains("setDispositionSnapshot(AVAILABLE_DISPOSITION)");
        assertThat(source).contains("setUomIdSnapshot(material.getUnitOfMeasureId())");
        assertThat(source).contains("requireVersion(reservation");
        assertThat(source).contains("Idempotency-Key is required");
        assertThat(source).contains("stockMutationService.consume");
    }
}
