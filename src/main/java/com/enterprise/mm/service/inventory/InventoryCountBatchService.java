package com.enterprise.mm.service.inventory;

import com.enterprise.mm.payload.InventoryCountBatchDtos.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class InventoryCountBatchService {
    private final InventoryCountMutationService mutations;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BatchResult save(UUID documentId, BatchCommand command, String key) {
        mutations.admitBatch(documentId, command, key);
        var results = new ArrayList<RowResult>();
        for (var row : command.rows()) results.add(mutations.saveOne(documentId, row, key.trim() + ":" + row.lineId()));
        var revision = mutations.currentRevision(documentId);
        return new BatchResult(revision.version(), revision.countRevision(), List.copyOf(results));
    }
}
