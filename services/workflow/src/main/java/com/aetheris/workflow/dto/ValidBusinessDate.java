package com.aetheris.workflow.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BusinessDateValidator.class)
public @interface ValidBusinessDate {
    String message() default "业务日期必须为有效的 yyyy-MM-dd 日期";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
