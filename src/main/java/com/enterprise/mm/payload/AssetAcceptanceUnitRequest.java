package com.enterprise.mm.payload;

public record AssetAcceptanceUnitRequest(
    Integer lineSequence,
    String manufacturerSerialNumber,
    String receivingUnitNumber,
    String serialNumber,
    String temporaryTag,
    String condition
) {
    public AssetAcceptanceUnitRequest(Integer lineSequence, String serialNumber, String temporaryTag, String condition) {
        this(lineSequence, null, null, serialNumber, temporaryTag, condition);
    }
}
