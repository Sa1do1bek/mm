package com.enterprise.mm.service;

import com.enterprise.mm.enums.MeasurementUnitType;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UnitTypeLabelResolver {

    private final MessageSource messageSource;

    public String resolve(MeasurementUnitType unitType, Locale locale) {
        if (unitType == null) {
            return null;
        }
        return messageSource.getMessage("unitType." + unitType.name(), null, unitType.name(), locale);
    }
}