package com.taskforge.task.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FutureOrPresentDueDateValidator.class)
public @interface FutureOrPresentDueDate {

	String message() default "Due date must be today or in the future";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
