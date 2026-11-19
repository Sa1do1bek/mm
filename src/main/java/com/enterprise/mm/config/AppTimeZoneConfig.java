package com.enterprise.mm.config;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppTimeZoneConfig {
    private final String timeZone;

    public AppTimeZoneConfig(@Value("${app.time-zone:Asia/Tashkent}") String timeZone) {
        this.timeZone = timeZone;
    }

    @PostConstruct
    void configureDefaultTimeZone() {
        System.setProperty("user.timezone", timeZone);
        TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
    }
}
