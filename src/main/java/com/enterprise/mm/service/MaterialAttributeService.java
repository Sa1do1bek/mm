package com.enterprise.mm.service;

import com.enterprise.mm.entity.MaterialAttribute;
import com.enterprise.mm.mapper.MaterialAttributeMapper;
import com.enterprise.mm.payload.MaterialAttributeRequest;
import com.enterprise.mm.payload.MaterialAttributeResponse;
import com.enterprise.mm.repository.MaterialAttributeRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialAttributeService {
    private final MaterialAttributeRepository repository;
    private final MaterialAttributeMapper mapper;
    private final MaterialAttributeValidationService validationService;

    @Transactional(readOnly = true)
    public List<MaterialAttributeResponse> getByMaterialId(UUID materialId) {
        return repository.findActiveByMaterialId(materialId)
            .stream()
            .map(mapper::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<MaterialAttributeResponse>> getByMaterialIds(List<UUID> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            return Map.of();
        }
        return repository.findActiveByMaterialIds(materialIds)
            .stream()
            .collect(Collectors.groupingBy(
                MaterialAttribute::getMaterialId,
                Collectors.mapping(mapper::toResponse, Collectors.toList())
            ));
    }

    @Transactional
    public void replaceAttributes(UUID materialId, List<MaterialAttributeRequest> requests) {
        List<MaterialAttributeRequest> safeRequests = requests == null ? List.of() : requests;
        validationService.validate(safeRequests);

        List<MaterialAttribute> existing = repository.findActiveByMaterialId(materialId);
        Map<String, MaterialAttribute> existingByKey = existing.stream()
            .collect(Collectors.toMap(
                attribute -> validationService.normalizeKey(attribute.getKey()),
                Function.identity()
            ));

        Set<String> requestedKeys = new HashSet<>();
        List<MaterialAttribute> toSave = new ArrayList<>();
        for (int index = 0; index < safeRequests.size(); index++) {
            MaterialAttributeRequest request = safeRequests.get(index);
            String normalizedKey = validationService.normalizeKey(request.key());
            requestedKeys.add(normalizedKey);

            MaterialAttribute attribute = existingByKey.getOrDefault(
                normalizedKey,
                MaterialAttribute.builder()
                    .materialId(materialId)
                    .isDeleted(Boolean.FALSE)
                    .build()
            );
            attribute.setKey(validationService.trim(request.key()));
            attribute.setValue(validationService.trim(request.value()));
            attribute.setSortOrder(index);
            attribute.setIsDeleted(Boolean.FALSE);
            toSave.add(attribute);
        }

        for (MaterialAttribute attribute : existing) {
            if (!requestedKeys.contains(validationService.normalizeKey(attribute.getKey()))) {
                attribute.setIsDeleted(Boolean.TRUE);
                toSave.add(attribute);
            }
        }

        repository.saveAll(toSave);
    }

    @Transactional
    public void softDeleteByMaterialId(UUID materialId) {
        List<MaterialAttribute> existing = repository.findActiveByMaterialId(materialId);
        if (existing.isEmpty()) {
            return;
        }
        existing.forEach(attribute -> attribute.setIsDeleted(Boolean.TRUE));
        repository.saveAll(existing);
    }
}
