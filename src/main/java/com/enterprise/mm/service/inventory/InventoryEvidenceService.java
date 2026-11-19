package com.enterprise.mm.service.inventory;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.AttachmentRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class InventoryEvidenceService {
    private final AttachmentRepository attachments;
    public List<UUID> validate(InventoryDocument d, UUID lineId, List<UUID> ids, boolean required) {
        if (ids != null && (ids.size() > 30 || ids.stream().anyMatch(Objects::isNull))) throw new BadRequestException("Choose up to 30 evidence files");
        var result = ids == null ? List.<UUID>of() : List.copyOf(new LinkedHashSet<>(ids));
        if (required && result.isEmpty()) throw new BadRequestException("Attach evidence for this observation or stock decision");
        for (var id : result) {
            var a = attachments.findById(id).filter(f -> !f.isDeleted() && d.getTenantId().equals(f.getTenantId()) && d.getCompanyId().equals(f.getCompanyId()))
                .orElseThrow(() -> new BadRequestException("Evidence file is outside this company"));
            boolean owned = Set.of("INVENTORY_DOCUMENT", "INVENTORY_COUNT_EVIDENCE").contains(a.getEntityType()) && d.getId().equals(a.getEntityId())
                || Set.of("INVENTORY_ITEM", "INVENTORY_LINE_COUNT_EVIDENCE").contains(a.getEntityType()) && lineId != null && lineId.equals(a.getEntityId());
            if (!owned) throw new BadRequestException("Evidence must belong to this physical count or line");
        }
        return result;
    }
}
