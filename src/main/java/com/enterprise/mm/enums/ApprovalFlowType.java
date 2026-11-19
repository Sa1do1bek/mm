package com.enterprise.mm.enums;

public enum ApprovalFlowType {
    SEQUENTIAL,
    PARALLEL_ALL,
    /** Parallel agreement (KELISHUVCHI) followed by one final approval (TASDIQLOVCHI). */
    PARALLEL_THEN_FINAL
}
