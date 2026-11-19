package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceCaseWorkspaceQueryRepositoryImplTest {

    @Test
    void decodesUuidFromNativeQueryRepresentations() {
        UUID expected = UUID.fromString("5e6858d4-803a-4bbb-8e72-2f429b87dc65");
        byte[] binary = ByteBuffer.allocate(16)
            .putLong(expected.getMostSignificantBits())
            .putLong(expected.getLeastSignificantBits())
            .array();

        assertThat(ServiceCaseWorkspaceQueryRepositoryImpl.uuid(expected)).isEqualTo(expected);
        assertThat(ServiceCaseWorkspaceQueryRepositoryImpl.uuid(binary)).isEqualTo(expected);
        assertThat(ServiceCaseWorkspaceQueryRepositoryImpl.uuid(expected.toString())).isEqualTo(expected);
        assertThat(ServiceCaseWorkspaceQueryRepositoryImpl.uuid(null)).isNull();
    }

    @Test
    void rejectsMalformedBinaryUuid() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ServiceCaseWorkspaceQueryRepositoryImpl.uuid(new byte[15]))
            .withMessage("Expected 16 bytes for UUID but received 15");
    }
}
