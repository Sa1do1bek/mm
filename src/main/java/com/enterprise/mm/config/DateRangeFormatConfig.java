package com.enterprise.mm.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DateRangeFormatConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new IsoDateRangeLocalDateConverter());
    }

    static class IsoDateRangeLocalDateConverter implements Converter<String, LocalDate> {
        @Override
        public LocalDate convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            String value = source.trim();
            try {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // Try the timezone-aware list filter format next.
            }
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // Some clients send local ISO datetimes without offset.
            }
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // Fall through to Instant parsing for Z-normalized strings.
            }
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate();
        }
    }
}
