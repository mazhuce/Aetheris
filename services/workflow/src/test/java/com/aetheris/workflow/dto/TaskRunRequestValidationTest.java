package com.aetheris.workflow.dto;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import static org.junit.jupiter.api.Assertions.*;
class TaskRunRequestValidationTest {
    @Test void beanValidationChecksCalendarInsteadOfOnlyShape() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertFalse(validator.validate(new TaskRunRequest("2025-02-29")).isEmpty());
            assertFalse(validator.validate(new TaskRunRequest("2026-04-31")).isEmpty());
            assertTrue(validator.validate(new TaskRunRequest("2024-02-29")).isEmpty());
            assertTrue(validator.validate(new TaskRunRequest(null)).isEmpty());
        }
    }
}
