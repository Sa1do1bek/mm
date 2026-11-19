package com.enterprise.mm.inventory.policy;

import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.error.InventoryLineMutationNotAllowedException;
import org.springframework.stereotype.Component;

@Component
public class InventoryLineMutationPolicy {
    public void checkCreate(InventoryStatus status) {
        if (!status.editable()) {
            throw new InventoryLineMutationNotAllowedException(
                    "Inventory line cannot be created in status: " + status
            );
        }
    }

    public void checkUpdate(InventoryStatus status) {
        if (!status.editable()) {
            throw new InventoryLineMutationNotAllowedException(
                    "Inventory line cannot be updated in status: " + status
            );
        }
    }

    public void checkDelete(InventoryStatus status) {
        if (!status.editable()) {
            throw new InventoryLineMutationNotAllowedException(
                    "Inventory line cannot be deleted in status: " + status
            );
        }
    }

    public void checkRestore(InventoryStatus status) {
        if (!status.editable()) {
            throw new InventoryLineMutationNotAllowedException(
                    "Inventory line cannot be restored in status: " + status
            );
        }
    }

    public void checkCount(InventoryStatus status) {
        if (!status.countable()) {
            throw new InventoryLineMutationNotAllowedException(
                    "Inventory line count is not allowed in status: " + status
            );
        }
    }
}