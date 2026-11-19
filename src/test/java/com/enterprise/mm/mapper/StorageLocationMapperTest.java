package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.enums.StorageZoneType;
import com.enterprise.mm.payload.StorageLocationCreateRequest;
import com.enterprise.mm.payload.StorageLocationUpdateRequest;
import org.junit.jupiter.api.Test;

class StorageLocationMapperTest {
    private final StorageLocationMapper mapper = new StorageLocationMapper();

    @Test
    void createWithoutZoneCodeStillMapsBinAndZoneType() {
        StorageLocationCreateRequest request = new StorageLocationCreateRequest();
        request.setName("Main storage");
        request.setBinCode("A-01");
        request.setStorageZoneType(StorageZoneType.STORAGE);

        StorageLocation entity = mapper.toEntity(request);

        assertThat(entity.getZoneCode()).isNull();
        assertThat(entity.getName()).isEqualTo("Main storage");
        assertThat(entity.getBinCode()).isEqualTo("A-01");
        assertThat(entity.getStorageZoneType()).isEqualTo(StorageZoneType.STORAGE);
    }

    @Test
    void updatePreservesExistingZoneCodeAndReturnsItInResponse() {
        StorageLocation entity = new StorageLocation();
        entity.setZoneCode("LEGACY-A");
        entity.setBinCode("A-01");
        entity.setStorageZoneType(StorageZoneType.STORAGE);
        StorageLocationUpdateRequest request = new StorageLocationUpdateRequest();
        request.setName("Updated storage");
        request.setBinCode("A-02");
        request.setStorageZoneType(StorageZoneType.PICKING);

        mapper.updateEntity(entity, request);

        assertThat(entity.getZoneCode()).isEqualTo("LEGACY-A");
        assertThat(entity.getName()).isEqualTo("Updated storage");
        assertThat(entity.getBinCode()).isEqualTo("A-02");
        assertThat(entity.getStorageZoneType()).isEqualTo(StorageZoneType.PICKING);
        assertThat(mapper.toResponse(entity).getZoneCode()).isEqualTo("LEGACY-A");
    }
}
