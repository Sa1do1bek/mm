package com.enterprise.mm.security.pilot;

import java.lang.annotation.*;

/** Gate application commands before execution; explicitly enumerated history/read methods remain available. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface PilotCapabilityBoundary {
    PilotCapability value();
    String[] reads() default {};
}
