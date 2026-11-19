package com.enterprise.mm.payload;

import lombok.Data;

/**
 * Optional body for the campaign cancel action. The reason is forwarded to the linked
 * procurement procedure, whose cancel operation requires one.
 */
@Data
public class ProcurementCampaignCancelRequest {
    private String cancellationReason;
}
