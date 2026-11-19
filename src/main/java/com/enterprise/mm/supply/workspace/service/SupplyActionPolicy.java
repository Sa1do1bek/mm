package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AllowedAction;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SupplyActionPolicy {
    /**
     * Task 2 exposes only the non-mutating dossier action. Assignment and lifecycle
     * mutations are added by their dedicated aggregates in later tasks.
     */
    public List<AllowedAction> actions(SupplyCommitmentRecord record, String encodedKey) {
        DeepLinkTarget target = new DeepLinkTarget(
            "supply-control",
            record.key().id(),
            record.key().canonical(),
            Map.of("commitment", encodedKey));
        return List.of(new AllowedAction(
            "OPEN_COMMITMENT",
            "supply.workspace.action.openCommitment",
            "default",
            false,
            false,
            record.assignmentVersion(),
            target));
    }
}
