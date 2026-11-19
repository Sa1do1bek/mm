package com.enterprise.mm.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MaterialCommandViewConfig {
    private static final ZoneId UZBEKISTAN_ZONE = ZoneId.of("Asia/Tashkent");

    @Bean
    public Clock materialCommandViewClock() {
        return Clock.system(UZBEKISTAN_ZONE);
    }
}
