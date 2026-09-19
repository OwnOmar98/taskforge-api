package com.taskforge.task.validation;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FutureOrPresentDueDateValidatorTest {

	private final FutureOrPresentDueDateValidator validator = new FutureOrPresentDueDateValidator();

	@Test
	void nullIsValid() {
		assertTrue(validator.isValid(null, null));
	}

	@Test
	void todayIsValid() {
		assertTrue(validator.isValid(LocalDate.now(), null));
	}

	@Test
	void futureDateIsValid() {
		assertTrue(validator.isValid(LocalDate.now().plusDays(1), null));
	}

	@Test
	void pastDateIsInvalid() {
		assertFalse(validator.isValid(LocalDate.now().minusDays(1), null));
	}

}
