package com.enterprise.mm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Same config-property style as {@link RfqMailProperties}, new property namespace for
 * the supplier PO dispatch mail poller (Phase B, section 3 of docs/CURRENT_TASK.md). */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "mm.po-dispatch-mail")
public class PoDispatchMailProperties {
    private boolean enabled = true;
    private long pollDelayMs = 30000L;
    private int batchSize = 20;
    private int maxAutomaticRetries = 5;
}
