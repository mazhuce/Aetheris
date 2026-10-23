package com.aetheris.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy=UsernameValidator.class)
@Target({ElementType.FIELD,ElementType.PARAMETER,ElementType.RECORD_COMPONENT,ElementType.ANNOTATION_TYPE,ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidUsername {
    String message() default "用户名须为1至64个字符，且不得包含空白、控制符、格式字符、|或无效Unicode字符";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
