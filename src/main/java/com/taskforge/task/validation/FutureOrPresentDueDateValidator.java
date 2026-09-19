package com.taskforge.task.validation;

import java.time.LocalDate;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FutureOrPresentDueDateValidator implements ConstraintValidator<FutureOrPresentDueDate, LocalDate> {

	@Override
	public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
		// null is a valid "no due date", same convention as every other Bean
		// Validation constraint - pair with @NotNull separately if required.
		return value == null || !value.isBefore(LocalDate.now());
	}

}
