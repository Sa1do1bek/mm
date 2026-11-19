package com.enterprise.mm.security.pilot;

import com.enterprise.mm.error.BusinessException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Combines code-enabled capabilities with the deployment allowlist. */
@Component
public class PilotCapabilityPolicy {
    private final Set<PilotCapability> enabled;

    @Autowired
    public PilotCapabilityPolicy(@Value("${mm.pilot.enabled-capabilities:}") String configured,
                                 @Value("${mm.pilot.code-enabled-capabilities:}") String codeEnabled) {
        EnumSet<PilotCapability> values = EnumSet.noneOf(PilotCapability.class);
        Arrays.stream((configured + "," + codeEnabled).split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .map(PilotCapability::valueOf).forEach(values::add);
        enabled = Set.copyOf(values);
    }

    public PilotCapabilityPolicy(String configured) { this(configured, ""); }

    public boolean enabled(PilotCapability capability) { return enabled.contains(capability); }

    public void require(PilotCapability capability) {
        if (!enabled(capability)) {
            throw new BusinessException("This operation is disabled for this deployment: " + capability,
                "pilot.capability.disabled", Map.of("capability", capability.name()), capability.name());
        }
    }

    public void requireMaterialExecution(com.enterprise.mm.entity.Material material) {
        if (material.getSerialTrackingPolicy() == com.enterprise.mm.enums.SerialTrackingPolicy.REQUIRED)
            require(PilotCapability.SERIALIZED_EXECUTION);
        if (material.getMaterialType() == com.enterprise.mm.enums.MaterialType.FIXED_ASSET)
            require(PilotCapability.FIXED_ASSET_EXECUTION);
        if (material.getMaterialType() == com.enterprise.mm.enums.MaterialType.SERVICES)
            require(PilotCapability.SERVICE_EXECUTION);
    }

    public PilotCapability integrationCapability(String eventType, String aggregateType) {
        String event = eventType == null ? "" : eventType.toUpperCase(java.util.Locale.ROOT);
        String aggregate = aggregateType == null ? "" : aggregateType.toUpperCase(java.util.Locale.ROOT);
        if (event.startsWith("TREASURY.") || aggregate.equals("TREASURY") || aggregate.equals("CONTRACTPAYMENT") || aggregate.equals("CONTRACTINVOICE")) return PilotCapability.MM_FINANCE_EXECUTION;
        if (event.startsWith("ASSET_") || event.startsWith("ASSET.") || aggregate.equals("FIXED_ASSET")) return PilotCapability.FIXED_ASSET_EXECUTION;
        if (event.startsWith("MM_MRP_") || aggregate.equals("MRP")) return PilotCapability.MRP_GENERATION;
        if (event.startsWith("SERVICE_") || event.startsWith("SERVICE.") || aggregate.startsWith("SERVICE") || aggregate.equals("WORKCOMPLETIONACT")) return PilotCapability.SERVICE_EXECUTION;
        return null;
    }

    public boolean permitsIntegration(com.enterprise.mm.entity.IntegrationEvent event) {
        var capability = integrationCapability(event.getEventType(), event.getAggregateType());
        return capability == null || enabled(capability);
    }

    public void requireIntegration(com.enterprise.mm.entity.IntegrationEvent event) {
        var capability = integrationCapability(event.getEventType(), event.getAggregateType());
        if (capability != null) require(capability);
    }

    public Set<PilotCapability> enabledCapabilities() { return enabled; }
}
