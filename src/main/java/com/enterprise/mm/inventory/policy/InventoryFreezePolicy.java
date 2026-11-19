package com.enterprise.mm.inventory.policy;
import com.enterprise.mm.enums.InventoryStatus;
import java.util.Set;
public final class InventoryFreezePolicy {
    private InventoryFreezePolicy() {}
    public static final Set<InventoryStatus> FROZEN = Set.of(InventoryStatus.IN_PROGRESS,InventoryStatus.COUNTING_COMPLETED,
        InventoryStatus.DISCREPANCY_REVIEW,InventoryStatus.WAITING_APPROVAL,InventoryStatus.APPROVED,InventoryStatus.REJECTED);
    public static boolean holdsFreeze(InventoryStatus status,boolean freezeOperations) {return freezeOperations && FROZEN.contains(status);}
}
