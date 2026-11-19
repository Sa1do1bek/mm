package com.enterprise.mm.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A single deployment switch; native 1C owns all network initiation by default. */
@Component
public class OneCTransport {
    private final boolean pull;
    public OneCTransport(@Value("${mm.one-c-api.pull-enabled:true}") boolean pull) { this.pull=pull; }
    public boolean pullEnabled() { return pull; }
    public String mode() { return pull ? "ONE_C_INITIATED" : "MM_INITIATED"; }
}
