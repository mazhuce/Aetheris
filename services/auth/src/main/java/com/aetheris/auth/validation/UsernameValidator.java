package com.aetheris.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class UsernameValidator implements ConstraintValidator<ValidUsername,String> {
    @Override public boolean isValid(String value,ConstraintValidatorContext context){return UsernameRules.isValid(value);}
}
