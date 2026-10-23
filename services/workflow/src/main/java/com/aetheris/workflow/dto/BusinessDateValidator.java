package com.aetheris.workflow.dto;

import com.aetheris.workflow.config.BusinessCalendar;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BusinessDateValidator implements ConstraintValidator<ValidBusinessDate, String> {
    @Override public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true;
        try { BusinessCalendar.parseExact(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
